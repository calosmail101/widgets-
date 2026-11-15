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
            val now = runCatching { out.readText() }.getOrNull()
            // Se da por terminado cuando hay contenido y lleva un intervalo sin crecer.
            if (!now.isNullOrEmpty() && now == text) break
            text = now
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
    // «cmd settings» habla con el servicio que ya está en marcha: es mucho más rápido que el comando «settings».
    private fun write(key: String, value: Int): Boolean = Privileged.run("cmd settings put system $key $value")

    fun performance(): Int = read("performance_mode")
    fun setPerformance(value: Int) = write("performance_mode", value)
    fun fan(): Int = read("fan_mode")
    fun setFan(value: Int) = write("fan_mode", value)
    // ---- Luces de los sticks: encendido "1,1", color "#aarrggbb,#aarrggbb" y brillo 0..1 ----
    private fun text(key: String): String? = runCatching { Settings.System.getString(Env.ctx.contentResolver, key) }.getOrNull()
    fun lightsSupported() = text("joystick_light_enabled") != null
    fun lightsOn() = text("joystick_light_enabled")?.contains('1') == true
    fun lightsColor(): Int = runCatching { android.graphics.Color.parseColor(text("joystick_led_light_picker_color")!!.substringBefore(',')) }.getOrDefault(0xFFFFFFFF.toInt())
    fun lightsBrightness(): Int = ((text("led_light_brightness_percent")?.toFloatOrNull() ?: 0.5f) * 100).toInt()
    /** Envía solo lo que haya cambiado (los parámetros null se dejan como están), en un único comando rápido. */
    fun sendLights(on: Boolean?, color: Int?, brightness: Int?): Boolean {
        val parts = ArrayList<String>()
        if (on != null) parts += "cmd settings put system joystick_light_enabled ${if (on) "1,1" else "0,0"}"
        if (color != null) {
            val hex = "#ff%06x".format(color and 0xFFFFFF)
            // Las comillas simples protegen la almohadilla: sin ellas la consola la tomaría por un comentario.
            parts += "cmd settings put system joystick_led_light_picker_color '$hex,$hex'"
        }
        if (brightness != null) parts += "cmd settings put system led_light_brightness_percent ${brightness.coerceIn(0, 100) / 100f}"
        return parts.isEmpty() || Privileged.run("sh -c \"${parts.joinToString("; ")}\"")
    }

    // ---- Detector: descubre qué ajuste cambia un mosaico de la consola ----

    /** Foto de todos los ajustes y propiedades del sistema. Tarda uno o dos segundos: fuera del hilo de la interfaz. */
    fun snapshot(): Map<String, String>? {
        val out = Privileged.output("(cmd settings list system; echo @@; cmd settings list secure; echo @@; cmd settings list global; echo @@; getprop)", 9000) ?: return null
        val sections = out.split("@@")
        if (sections.size < 4) return null
        val map = HashMap<String, String>()
        listOf("system", "secure", "global").forEachIndexed { i, table ->
            sections[i].lineSequence().forEach { line -> val eq = line.indexOf('='); if (eq > 0) map["$table:${line.substring(0, eq).trim()}"] = line.substring(eq + 1).trim() }
        }
        Regex("\\[(.+?)]: \\[(.*?)]").findAll(sections[3]).forEach { map["prop:${it.groupValues[1]}"] = it.groupValues[2] }
        return map
    }

    /** Claves que cambian solas y no interesan (cachés, relojes, brillo, volumen…). */
    private val NOISE = Regex("cache_key|time|uptime|brightness|volume|battery|last_|netstats", RegexOption.IGNORE_CASE)

    fun diff(before: Map<String, String>, after: Map<String, String>): List<Triple<String, String, String>> =
        after.filter { (k, v) -> before[k] != null && before[k] != v && !NOISE.containsMatchIn(k) }
            .map { (k, v) -> Triple(k, before[k]!!, v) }

    fun learnedValue(l: dev.palanca.launcher.data.Learned): String? = runCatching {
        val cr = Env.ctx.contentResolver
        when {
            l.kind == RETROID_PAD -> if (text("no_create_gamepad_button_layout") == "1") "-" else Settings.System.getString(cr, l.key)
            l.kind == "propiedad" -> Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, l.key) as String
            l.table == "secure" -> Settings.Secure.getString(cr, l.key)
            l.table == "global" -> Settings.Global.getString(cr, l.key)
            else -> Settings.System.getString(cr, l.key)
        }
    }.getOrNull()

    fun setLearned(l: dev.palanca.launcher.data.Learned, value: String): Boolean = when (l.kind) {
        // El estilo de mando de Retroid necesita dos claves a la vez (con una sola, el mando no cambia o
        // el mosaico de Android se queda con el nombre viejo). Comprobado por ADB en una Pocket 6.
        RETROID_PAD -> Privileged.run(
            "sh -c \"cmd settings put system no_create_gamepad_button_layout 0; cmd settings put system flip_button_layout $value; " +
                "cmd settings put system temp_abxy_layout_mode ${if (value == "1") 0 else 1}\"",
        )
        "propiedad" -> Privileged.run("setprop ${l.key} $value")
        else -> Privileged.run("cmd settings put ${l.table} ${l.key} $value")
    }

    /**
     * Controles que ya se conocen en las consolas Retroid, sin tener que enseñárselos a Heaven.
     * Solo se usan si la consola tiene esas claves; si no, queda el detector.
     */
    fun builtin(id: String): dev.palanca.launcher.data.Learned? = when (id) {
        "mando" -> if (text("flip_button_layout") != null && text("no_create_gamepad_button_layout") != null)
            dev.palanca.launcher.data.Learned(RETROID_PAD, "system", "flip_button_layout", listOf("0", "1"), listOf("Retro", "Xbox")) else null
        "gatillos" -> if (text("trigger_input_mode") != null)
            dev.palanca.launcher.data.Learned("ajuste", "system", "trigger_input_mode", listOf("0", "1", "2"), listOf("Analógico", "Digital", "Ambos")) else null
        else -> null
    }
    private const val RETROID_PAD = "retroid-mando"

    fun fanSpeed(): Int = read("fan_speed").coerceAtLeast(0)
    fun setFanSpeed(value: Int) = write("fan_speed", value)

    // ---- Pantalla y carga (mosaicos Smooth Display y Charge limit de Retroid) ----

    val REFRESH = listOf(60 to "60 Hz", 120 to "120 Hz")
    /** -1 si la consola no expone la frecuencia como ajuste. */
    fun refresh(): Int = text("peak_refresh_rate")?.toFloatOrNull()?.let { if (it > 90f) 120 else 60 } ?: -1
    /** El mosaico de la consola escribe las dos claves a la vez, con 60.0 o 120.00001. */
    fun setRefresh(hz: Int): Boolean {
        val v = if (hz >= 120) "120.00001" else "60.0"
        return Privileged.run("sh -c \"cmd settings put system peak_refresh_rate $v; cmd settings put system min_refresh_rate $v\"")
    }

    // ---- Botones traseros (Retroid): «remap_custom_to_m1_value» y «…_m2_value» guardan la tecla que envían ----

    /**
     * Tecla que Heaven asigna al M2 para el puntero: BUTTON_Z, el valor de fábrica de Retroid para el M2.
     * Con 0 (ninguna) el M2 no envía nada. Una tecla sin equivalente en el mando virtual de Retroid
     * (por ejemplo BUTTON_16) se guarda pero el servicio de mapeo la descarta en silencio.
     */
    const val M2_KEY = android.view.KeyEvent.KEYCODE_BUTTON_Z
    fun backButtonsSupported() = text("remap_custom_to_m2_value") != null
    fun m2Key(): Int = read("remap_custom_to_m2_value")
    /**
     * Guardar el valor no basta: el panel de Retroid mete M1 y M2 en la configuración del mando
     * (StandardGamepadConfig) y solo la envía al servicio de mapeo cuando el mando se recrea. Por eso,
     * tras escribirlo, se cambia el estilo de mando un instante y se devuelve al que estaba.
     */
    fun setM2Key(code: Int): Boolean {
        val flip = read("flip_button_layout").coerceIn(0, 1)
        return write("remap_custom_to_m2_value", code) && Privileged.run(
            "sh -c \"sleep 0.3; cmd settings put system flip_button_layout ${1 - flip}; sleep 1.2; cmd settings put system flip_button_layout $flip\"",
        )
    }

    val CHARGE = listOf(0 to "Sin límite", 1 to "Hasta el 80 %")
    fun chargeLimit(): Int = if (text("percent_80_charge_limit") == null) -1 else read("percent_80_charge_limit")
    fun setChargeLimit(on: Int) = write("percent_80_charge_limit", on)

    // ---- Puntero: activar el servicio de accesibilidad sin pasar por los ajustes de Android ----

    /**
     * Android 13 bloquea la accesibilidad de las apps instaladas fuera de la tienda («ajustes
     * restringidos»). Con PServer, Heaven levanta ese bloqueo y añade su servicio a los que ya estén
     * activos, sin quitar ninguno. Devuelve false si la consola no tiene PServer.
     */
    fun enablePointer(): Boolean {
        val me = "${Env.ctx.packageName}/.system.PointerService"
        val current = runCatching { Settings.Secure.getString(Env.ctx.contentResolver, "enabled_accessibility_services") }.getOrNull().orEmpty()
        val list = current.split(':').filter { it.isNotBlank() && !it.endsWith("PointerService") } + me
        // Tres comandos cortos: PServer no acepta más de 255 bytes por comando y la lista de servicios
        // (por ejemplo, el Game Assistant de Retroid) ya ocupa bastante.
        val services = "cmd settings put secure enabled_accessibility_services '${list.joinToString(":")}'"
        if (services.toByteArray().size > 255) return false
        return Privileged.run("appops set ${Env.ctx.packageName} ACCESS_RESTRICTED_SETTINGS allow") &&
            Privileged.run(services) &&
            Privileged.run("cmd settings put secure accessibility_enabled 1")
    }
}

/** FPS reales de lo que haya en pantalla, a partir del contador de fotogramas del compositor de Android. */
object FpsMeter {
    private var lastFrames = -1L
    private var lastTime = 0L

    fun start() { lastFrames = -1; Privileged.run("dumpsys SurfaceFlinger --timestats -enable -clear") }
    fun stop() { Privileged.run("dumpsys SurfaceFlinger --timestats -disable") }

    /** Bloquea unos 300 ms. Devuelve null si la consola no da el contador. */
    fun read(): Int? {
        val out = Privileged.output("(dumpsys SurfaceFlinger --timestats -dump | head -c 700)", 1200) ?: return null
        val frames = Regex("totalFrames\\s*=\\s*(\\d+)").find(out)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        val now = android.os.SystemClock.uptimeMillis()
        val fps = if (lastFrames >= 0 && now > lastTime) ((frames - lastFrames) * 1000 / (now - lastTime)).toInt() else null
        lastFrames = frames; lastTime = now
        return fps?.coerceIn(0, 240)
    }
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
    /** Nombre de la canción de fondo que suena ahora («» si no suena ninguna). */
    val title = kotlinx.coroutines.flow.MutableStateFlow("")
    val playing get() = runCatching { player?.isPlaying == true }.getOrDefault(false)
    /** Sesión de audio del reproductor de fondo (para el visualizador); 0 si no hay. */
    val session get() = runCatching { player?.audioSessionId ?: 0 }.getOrDefault(0)

    private fun nameOf(uri: Uri): String = runCatching {
        Env.ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()?.substringBeforeLast('.')?.ifBlank { null } ?: "Música de Heaven"

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
        title.value = nameOf(uri)
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
