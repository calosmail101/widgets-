package dev.palanca.launcher.system

import android.accessibilityservice.AccessibilityService
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.StatFs
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.widget.Toast
import dev.palanca.launcher.Env
import dev.palanca.launcher.data.AppEntry
import dev.palanca.launcher.data.Usage
import dev.palanca.launcher.input.Btn
import dev.palanca.launcher.ui.DrawerScreen
import dev.palanca.launcher.ui.KeyboardScreen
import dev.palanca.launcher.ui.QuickPanel
import dev.palanca.launcher.ui.SettingsScreen
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

enum class ActionId(val title: String) {
    NONE("Nada"), ANDROID_SETTINGS("Ajustes de Android"), QUICK_PANEL("CNTRL"),
    RECENTS("Apps recientes"), NOTIFICATIONS("Notificaciones"), SEARCH("Buscar"),
    CUSTOMIZE("Custom"), DRAWER("Cajón de apps"), LAST_APP("Última app"),
}

/** Atajos configurables. Solo se disparan si la pantalla activa no usa ese botón. */
enum class Trigger(val title: String, val btn: Btn, val long: Boolean, val default: ActionId) {
    SELECT_HOLD("Mantener SELECT", Btn.SELECT, true, ActionId.ANDROID_SETTINGS),
    START_HOLD("Mantener START", Btn.START, true, ActionId.LAST_APP),
    L2("L2", Btn.L2, false, ActionId.QUICK_PANEL),
    R2("R2", Btn.R2, false, ActionId.RECENTS),
    L3("L3 (pulsar stick izq.)", Btn.L3, false, ActionId.SEARCH),
    R3("R3 (pulsar stick der.)", Btn.R3, false, ActionId.NOTIFICATIONS),
}

object Actions {
    private val ctx get() = Env.ctx

    fun toast(text: String) = Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()

    fun actionFor(trigger: Trigger): ActionId =
        Env.cfg.value.shortcuts[trigger.name]?.let { n -> ActionId.entries.firstOrNull { it.name == n } } ?: trigger.default

    fun run(id: ActionId) {
        when (id) {
            ActionId.NONE -> Unit
            ActionId.ANDROID_SETTINGS -> open(Settings.ACTION_SETTINGS)
            ActionId.QUICK_PANEL -> if (Env.nav.top !is QuickPanel) Env.nav.push(QuickPanel())
            ActionId.RECENTS -> global(AccessibilityService.GLOBAL_ACTION_RECENTS)
            ActionId.NOTIFICATIONS -> notifications()
            ActionId.SEARCH -> Env.nav.push(KeyboardScreen.search())
            ActionId.CUSTOMIZE -> if (Env.nav.top !is SettingsScreen) Env.nav.push(SettingsScreen())
            ActionId.DRAWER -> if (Env.nav.top !is DrawerScreen) Env.nav.push(DrawerScreen())
            ActionId.LAST_APP -> lastApp()?.let(::launch) ?: toast("Todavía no has abierto ninguna app")
        }
    }

    fun open(action: String, data: Uri? = null): Boolean = runCatching {
        ctx.startActivity(Intent(action).apply { this.data = data; addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
    }.isSuccess

    private fun global(action: Int) {
        val service = PointerService.instance
        if (service == null) toast("Activa el servicio de Heaven en Custom › Mandos") else service.performGlobalAction(action)
    }

    private fun notifications() {
        PointerService.instance?.let { it.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS); return }
        runCatching {
            val bar = ctx.getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(bar)
        }.onFailure { toast("Activa el servicio de Heaven en Custom › Mandos") }
    }

    // ---- Lanzar apps y medir sesiones --------------------------------------------------

    /** Abre [app]. [origin] es el punto de la pantalla (fracciones 0..1) desde el que crece la ventana. */
    fun launch(app: AppEntry, origin: Pair<Float, Float> = 0.5f to 0.5f) {
        val now = System.currentTimeMillis()
        val options = runCatching {
            val view = Env.host!!.activity.window.decorView
            val side = view.height / 6
            android.app.ActivityOptions.makeScaleUpAnimation(
                view, (view.width * origin.first).toInt() - side / 2, (view.height * origin.second).toInt() - side / 2, side, side,
            ).toBundle()
        }.getOrNull()
        if (!Env.apps.launch(app, options)) { toast("No se pudo abrir ${app.label}"); return }
        Env.stats.update { s ->
            val u = s.usage[app.key] ?: Usage()
            s.copy(usage = s.usage + (app.key to u.copy(last = now, count = u.count + 1)), pendingKey = app.key, pendingAt = now)
        }
    }

    /** Al volver al launcher: cierra la sesión de la app que se había abierto. */
    fun onReturn() {
        val now = System.currentTimeMillis()
        Env.stats.update { s ->
            val key = s.pendingKey ?: return@update s
            val ms = now - s.pendingAt
            val u = s.usage[key]
            if (u == null || ms < 5_000 || ms > 12 * 3_600_000L) s.copy(pendingKey = null)
            else s.copy(usage = s.usage + (key to u.copy(totalMs = u.totalMs + ms, lastSessionMs = ms)), pendingKey = null)
        }
    }

    fun lastApp(): AppEntry? {
        val usage = Env.stats.value.usage
        return Env.apps.apps.value.filter { (usage[it.key]?.last ?: 0) > 0 }.maxByOrNull { usage[it.key]!!.last }
    }

    fun uninstall(app: AppEntry) {
        open(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}"))
    }

    // ---- Brillo, volumen y datos del sistema -------------------------------------------

    fun canWriteSettings() = Settings.System.canWrite(ctx)
    fun askWriteSettings() = open(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}"))

    /** Brillo 0..100, o -1 si no se puede leer. */
    fun brightness(): Int = runCatching {
        Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS) * 100 / 255
    }.getOrDefault(-1)

    fun setBrightness(percent: Int) {
        runCatching {
            val cr = ctx.contentResolver
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, (percent.coerceIn(1, 100) * 255 / 100))
        }
    }

    private val audio get() = ctx.getSystemService(AudioManager::class.java)
    fun volume(): Int = audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    /** Sube o baja el volumen en pasos reales del sistema (los grandes, de tres en tres). */
    fun volumeStep(delta: Int) {
        val direction = if (delta > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        repeat(if (kotlin.math.abs(delta) > 5) 3 else 1) {
            runCatching { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0) }
        }
    }

    /** Valor fijo para pruebas y capturas. */
    var storageOverride: Pair<Long, Long>? = null

    /** Espacio libre y total del almacenamiento interno, en bytes. */
    fun storage(): Pair<Long, Long> = storageOverride ?: runCatching {
        val s = StatFs(Environment.getDataDirectory().path)
        s.availableBytes to s.totalBytes
    }.getOrDefault(0L to 0L)

    fun pointerEnabled() = PointerService.instance != null
}

val SOUND_PACKS = listOf("toque" to "Toque (suave y grave)", "burbuja" to "Burbuja", "cristal" to "Cristal", "arcade" to "Arcade (8 bits)")

/**
 * Sonidos de la interfaz, sintetizados al vuelo (sin archivos), y vibración corta.
 * Moverse suena siempre igual; solo abrir (A) y volver (B) tienen su propio sonido, con el mismo timbre.
 */
class Feedback(private val ctx: Context) {
    private val vibrator = ctx.getSystemService(Vibrator::class.java)
    private var tracks: Map<String, List<AudioTrack>> = emptyMap()
    private var builtPack: String? = null
    private var turn = 0

    // Los sonidos se lanzan desde su propio hilo: el de la interfaz no espera nunca al audio.
    private val audioThread = HandlerThread("palanca-fx").apply { start() }
    private val audio = Handler(audioThread.looper)

    /** Genera [ms] milisegundos de sonido a partir de una función del tiempo en segundos (salida -1..1). */
    private fun clip(ms: Int, wave: (Double) -> Double): AudioTrack? = runCatching {
        val rate = 44100
        val n = rate * ms / 1000
        val pcm = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            // Entrada de 2 ms y salida de 4 ms para que no haga "clic" al empezar ni al cortar.
            val edge = minOf(1.0, t / 0.002, (ms / 1000.0 - t) / 0.004)
            pcm[i] = (wave(t).coerceIn(-1.0, 1.0) * edge * Short.MAX_VALUE).toInt().toShort()
        }
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            )
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(n * 2)
            .build().apply { write(pcm, 0, n) }
    }.getOrNull()

    private fun sine(f: Double, t: Double) = sin(2 * PI * f * t)
    private fun square(f: Double, t: Double) = if (sine(f, t) >= 0) 1.0 else -1.0
    /** Campana: fundamental más dos armónicos que se apagan a distinto ritmo. */
    private fun bell(f: Double, t: Double, decay: Double) =
        sine(f, t) * exp(-decay * t) + 0.35 * sine(f * 2, t) * exp(-decay * 1.8 * t) + 0.12 * sine(f * 3.01, t) * exp(-decay * 3 * t)
    /** Dos notas seguidas: la segunda entra en [at] segundos. */
    private fun twoNotes(t: Double, at: Double, first: (Double) -> Double, second: (Double) -> Double) =
        if (t < at) first(t) else first(t) * 0.4 + second(t - at)

    /** Barrido de frecuencia de [from] a [to] en [seconds]: el "pop" de una burbuja. */
    private fun sweep(from: Double, to: Double, seconds: Double, t: Double): Double {
        val k = (to - from) / seconds
        return sin(2 * PI * (from * t + k * t * t / 2))
    }

    private fun build(pack: String): Map<String, List<AudioTrack>> {
        fun one(clip: AudioTrack?) = listOfNotNull(clip)
        return when (pack) {
            "arcade" -> mapOf(
                "move" to one(clip(30) { t -> 0.055 * square(988.0, t) * exp(-34 * t) }),
                "select" to one(clip(110) { t -> 0.045 * twoNotes(t, 0.045, { square(988.0, it) * exp(-30 * it) }, { square(1480.0, it) * exp(-26 * it) }) }),
                "back" to one(clip(110) { t -> 0.045 * twoNotes(t, 0.045, { square(988.0, it) * exp(-30 * it) }, { square(660.0, it) * exp(-26 * it) }) }),
            )
            "burbuja" -> mapOf(
                "move" to one(clip(60) { t -> 0.15 * sweep(380.0, 760.0, 0.06, t) * exp(-38 * t) }),
                "select" to one(clip(110) { t -> 0.13 * sweep(420.0, 1250.0, 0.11, t) * exp(-28 * t) }),
                "back" to one(clip(110) { t -> 0.13 * sweep(900.0, 320.0, 0.11, t) * exp(-28 * t) }),
            )
            "cristal" -> mapOf(
                "move" to one(clip(110) { t -> 0.085 * bell(1568.0, t, 46.0) }),
                "select" to one(clip(200) { t -> 0.07 * twoNotes(t, 0.06, { bell(1568.0, it, 34.0) }, { bell(2093.0, it, 26.0) }) }),
                "back" to one(clip(200) { t -> 0.07 * twoNotes(t, 0.06, { bell(1568.0, it, 34.0) }, { bell(1046.5, it, 26.0) }) }),
            )
            // "toque": un golpecito grave y corto, casi táctil.
            else -> mapOf(
                "move" to one(clip(46) { t -> (0.17 * sine(430.0, t) + 0.045 * sine(860.0, t)) * exp(-62 * t) }),
                "select" to one(clip(110) { t -> 0.15 * twoNotes(t, 0.045, { sine(430.0, it) * exp(-55 * it) }, { (sine(645.0, it) + 0.2 * sine(1290.0, it)) * exp(-40 * it) }) }),
                "back" to one(clip(110) { t -> 0.15 * twoNotes(t, 0.045, { sine(430.0, it) * exp(-55 * it) }, { sine(287.0, it) * exp(-40 * it) }) }),
            )
        }
    }

    private fun play(name: String) {
        if (!Env.cfg.value.sound) return
        audio.post { playNow(name) }
    }

    private fun playNow(name: String) {
        val pack = Env.cfg.value.soundPack
        if (pack != builtPack) {
            tracks.values.flatten().forEach { runCatching { it.release() } }
            tracks = build(pack); builtPack = pack
        }
        val options = tracks[name].orEmpty()
        if (options.isEmpty()) return
        runCatching {
            options[turn++ % options.size].run {
                if (playState == AudioTrack.PLAYSTATE_PLAYING) stop()
                reloadStaticData()
                play()
            }
        }
    }

    fun move() = play("move")
    fun back() = play("back")
    fun select() {
        play("select")
        if (Env.cfg.value.haptics) runCatching { vibrator?.vibrate(VibrationEffect.createOneShot(12, 80)) }
    }
    fun heavy() {
        if (Env.cfg.value.haptics) runCatching { vibrator?.vibrate(VibrationEffect.createOneShot(28, 160)) }
    }
}

/** Anfitrión de widgets de Android. La Activity lo pone a escuchar mientras está visible. */
class WidgetHost(ctx: Context) {
    val host = AppWidgetHost(ctx, 0x504C)
    val manager: AppWidgetManager? = AppWidgetManager.getInstance(ctx)
    fun providers() = runCatching { manager?.installedProviders.orEmpty() }.getOrDefault(emptyList())
    fun info(id: Int) = runCatching { manager?.getAppWidgetInfo(id) }.getOrNull()
}
