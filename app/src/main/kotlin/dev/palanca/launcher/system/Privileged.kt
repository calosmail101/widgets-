package dev.palanca.launcher.system

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.provider.Settings
import dev.palanca.launcher.Env
import java.io.File

/**
 * Comandos con privilegios a través de PServer, el servicio que traen de fábrica las consolas
 * Retroid y AYN (el mismo que usa ClusterTune para funcionar sin root).
 *
 * Formato del mensaje, según la documentación de ClusterTune (MIT, © Aurelio Bernal):
 * servicio "PServerBinder", transacción 0, un entero con el número de cadenas y a continuación
 * el comando y "0" (sin captura de salida). El comando no puede pasar de 255 bytes.
 *
 * Heaven solo envía comandos fijos escritos en este código, nunca texto que venga de fuera.
 */
object Privileged {
    @SuppressLint("DiscouragedPrivateApi", "PrivateApi")
    private fun service(): IBinder? = runCatching {
        Class.forName("android.os.ServiceManager").getMethod("checkService", String::class.java)
            .invoke(null, "PServerBinder") as? IBinder
    }.getOrNull()?.takeIf { it.isBinderAlive }

    fun available(): Boolean = service() != null

    /** @return true si el servicio aceptó el comando (no garantiza que el comando funcionara). */
    fun run(command: String): Boolean {
        if (command.isBlank() || command.toByteArray().size > 255) return false
        val binder = service() ?: return false
        val request = Parcel.obtain(); val response = Parcel.obtain()
        return try {
            request.writeInt(2); request.writeString(command); request.writeString("0")
            binder.transact(0, request, response, 0)
        } catch (e: Exception) {
            false
        } finally {
            request.recycle(); response.recycle()
        }
    }

    /**
     * Ejecuta [command] y devuelve lo que imprime, pasando por un archivo temporal en la carpeta
     * de la app. Bloquea hasta [waitMs]: llamar fuera del hilo de la interfaz.
     */
    fun output(command: String, waitMs: Long = 1500): String? {
        val out = File(Env.ctx.getExternalFilesDir(null) ?: return null, "pserver_out.txt")
        runCatching { out.delete() }
        if (!run("sh -c \"$command > ${out.path} 2>&1\"")) return null
        val deadline = System.currentTimeMillis() + waitMs
        var text: String? = null
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(150)
            text = runCatching { out.readText() }.getOrNull()
            if (!text.isNullOrEmpty()) break
        }
        runCatching { out.delete() }
        return text
    }
}

/**
 * Modos propios de la consola (ajustes del fabricante): se leen directamente y se cambian con [Privileged].
 * Los valores de rendimiento y ventilador coinciden con los que documenta ClusterTune para AYN y Retroid.
 */
object DeviceModes {
    val PERFORMANCE = listOf(0 to "Standard", 1 to "Performance", 2 to "High performance")
    val FAN = listOf(4 to "Smart", 5 to "Sport", 6 to "Personalizado")

    fun <T> name(options: List<Pair<Int, T>>, value: Int): String = options.firstOrNull { it.first == value }?.second?.toString() ?: "modo $value"

    private fun read(key: String): Int = runCatching { Settings.System.getInt(Env.ctx.contentResolver, key) }.getOrDefault(-1)
    private fun write(key: String, value: Int): Boolean = Privileged.run("settings put system $key $value")

    fun performance(): Int = read("performance_mode")
    fun setPerformance(value: Int) = write("performance_mode", value)
    fun fan(): Int = read("fan_mode")
    fun setFan(value: Int) = write("fan_mode", value)
    /** Disposición de los botones ABXY (el mosaico "controller style" de la consola). */
    val CONTROLLER = listOf(0 to "Retro", 1 to "Xbox")
    fun controller(): Int = read("temp_abxy_layout_mode")
    fun setController(value: Int) = write("temp_abxy_layout_mode", value)

    // ---- Luces de los sticks: encendido "1,1", color "#aarrggbb,#aarrggbb" y brillo 0..1 ----
    private fun text(key: String): String? = runCatching { Settings.System.getString(Env.ctx.contentResolver, key) }.getOrNull()
    fun lightsSupported() = text("joystick_light_enabled") != null
    fun lightsOn() = text("joystick_light_enabled")?.contains('1') == true
    fun lightsColor(): Int = runCatching { android.graphics.Color.parseColor(text("joystick_led_light_picker_color")!!.substringBefore(',')) }.getOrDefault(0xFFFFFFFF.toInt())
    fun lightsBrightness(): Int = ((text("led_light_brightness_percent")?.toFloatOrNull() ?: 0.5f) * 100).toInt()
    fun setLights(on: Boolean, color: Int, brightness: Int): Boolean {
        val hex = "#ff%06x".format(color and 0xFFFFFF)
        val v = if (on) "1,1" else "0,0"
        // Las comillas simples protegen la almohadilla: sin ellas la consola la tomaría por un comentario.
        return Privileged.run("sh -c \"settings put system joystick_light_enabled $v; settings put system joystick_led_light_picker_color '$hex,$hex'; settings put system led_light_brightness_percent ${brightness.coerceIn(0, 100) / 100f}\"")
    }

    fun fanSpeed(): Int = read("fan_speed").coerceAtLeast(0)
    fun setFanSpeed(value: Int) = write("fan_speed", value)
}

/**
 * Música de fondo con canciones del usuario. Suena solo donde se le indique (Inicio y cajón),
 * muy baja, y entra y sale con un fundido corto.
 */
object Music {
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var tracks: List<String> = emptyList()
    private var index = 0
    private var target = 0f
    private var level = 0f
    private var wanted = false
    private var failures = 0

    private val fade = object : Runnable {
        override fun run() {
            val p = player ?: return
            val goal = if (wanted) target else 0f
            level += (goal - level).coerceIn(-0.02f, 0.02f)
            runCatching { p.setVolume(level, level) }
            if (level <= 0.001f && !wanted) { runCatching { p.pause() }; return }
            if (kotlin.math.abs(goal - level) > 0.001f) handler.postDelayed(this, 40)
        }
    }

    /** [active]: si debe sonar ahora. [volume]: 0..100. */
    fun update(active: Boolean, list: List<String>, volume: Int) {
        target = (volume.coerceIn(0, 100) / 100f)
        if (list != tracks) { tracks = list; index = 0; release() }
        wanted = active && tracks.isNotEmpty()
        if (wanted) {
            val p = player
            if (p == null) start() else runCatching { if (!p.isPlaying) p.start() }
        }
        handler.removeCallbacks(fade); handler.post(fade)
    }

    private fun start() {
        if (tracks.isEmpty() || failures > tracks.size) return
        val uri = Uri.parse(tracks[index % tracks.size])
        runCatching {
            val p = MediaPlayer()
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            p.setDataSource(Env.ctx, uri)
            p.setOnPreparedListener {
                failures = 0; level = 0f; it.setVolume(0f, 0f)
                if (wanted) { it.start(); handler.removeCallbacks(fade); handler.post(fade) }
            }
            p.setOnCompletionListener { next() }
            p.setOnErrorListener { _, _, _ -> failures++; next(); true }
            p.prepareAsync()
            player = p
        }.onFailure { failures++; next() }
    }

    private fun next() {
        release()
        index = (index + 1) % tracks.size.coerceAtLeast(1)
        if (wanted) start()
    }

    private fun release() {
        runCatching { player?.release() }
        player = null
    }
}
