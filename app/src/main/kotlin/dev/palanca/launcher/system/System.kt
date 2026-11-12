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
    NONE("Nada"), ANDROID_SETTINGS("Ajustes de Android"), QUICK_PANEL("Panel rápido"),
    RECENTS("Apps recientes"), NOTIFICATIONS("Notificaciones"), SEARCH("Buscar"),
    CUSTOMIZE("Personalizar"), DRAWER("Cajón de apps"), LAST_APP("Última app"),
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
        if (service == null) toast("Activa el servicio de Palanca en Personalizar › Mandos") else service.performGlobalAction(action)
    }

    private fun notifications() {
        PointerService.instance?.let { it.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS); return }
        runCatching {
            val bar = ctx.getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(bar)
        }.onFailure { toast("Activa el servicio de Palanca en Personalizar › Mandos") }
    }

    // ---- Lanzar apps y medir sesiones --------------------------------------------------

    fun launch(app: AppEntry) {
        val now = System.currentTimeMillis()
        if (!Env.apps.launch(app)) { toast("No se pudo abrir ${app.label}"); return }
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

/** Sonidos sintetizados (sin archivos) y vibración corta. */
class Feedback(private val ctx: Context) {
    private val vibrator = ctx.getSystemService(Vibrator::class.java)
    private var tracks: Map<String, AudioTrack>? = null

    private fun tone(freq: Double, toFreq: Double, ms: Int, gain: Double): AudioTrack? = runCatching {
        val rate = 44100
        val n = rate * ms / 1000
        val pcm = ShortArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / n
            phase += 2 * PI * (freq + (toFreq - freq) * t) / rate
            pcm[i] = (sin(phase) * exp(-4.0 * t) * gain * Short.MAX_VALUE).toInt().toShort()
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

    private fun play(name: String) {
        if (!Env.cfg.value.sound) return
        val all = tracks ?: buildMap {
            tone(1500.0, 1300.0, 22, 0.16)?.let { put("move", it) }
            tone(880.0, 1320.0, 70, 0.22)?.let { put("select", it) }
            tone(660.0, 440.0, 60, 0.20)?.let { put("back", it) }
        }.also { tracks = it }
        runCatching {
            all[name]?.run {
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
