package dev.palanca.launcher.system

import android.content.ComponentName
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.audiofx.Visualizer
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import dev.palanca.launcher.Env
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.hypot
import kotlin.math.ln

/** Solo existe para que Android deje a Heaven leer qué suena en otras apps (Spotify, YouTube…). */
class MediaListener : NotificationListenerService()

/** Lo que está sonando en otra app. */
data class NowPlaying(val pkg: String, val title: String, val artist: String, val art: Bitmap?, val playing: Boolean)

/**
 * Música de otras apps (por las sesiones multimedia de Android) y un visualizador pequeño del sonido
 * que sale por el altavoz. El visualizador lee la mezcla de salida (sesión 0): no usa el micrófono,
 * aunque Android pida el permiso de audio para ello.
 */
object Media {
    val now = MutableStateFlow<NowPlaying?>(null)
    /** Siete barras de 0 a 1. */
    val bars = MutableStateFlow(FloatArray(BARS))
    const val BARS = 7

    private var manager: MediaSessionManager? = null
    private var controller: MediaController? = null
    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() { pick(null) }
    }

    private fun listener() = ComponentName(Env.ctx, MediaListener::class.java)

    /** Android guarda los permisos concedidos; con PServer, Heaven se los da él mismo la primera vez. */
    fun ensurePermissions() {
        val pkg = Env.ctx.packageName
        val enabled = android.provider.Settings.Secure.getString(Env.ctx.contentResolver, "enabled_notification_listeners").orEmpty()
        if (!enabled.contains(pkg)) Privileged.run("cmd notification allow_listener ${listener().flattenToString()}")
        if (Env.ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            Privileged.run("pm grant $pkg android.permission.RECORD_AUDIO")
    }

    fun start() {
        if (manager != null) return
        val m = Env.ctx.getSystemService(MediaSessionManager::class.java) ?: return
        runCatching {
            m.addOnActiveSessionsChangedListener({ list -> pick(list) }, listener())
            manager = m
            pick(m.getActiveSessions(listener()))
        }
    }

    /** Elige la sesión que suena (Spotify primero) y se suscribe a sus cambios. */
    private fun pick(list: List<MediaController>?) {
        val sessions = list ?: runCatching { manager?.getActiveSessions(listener()) }.getOrNull().orEmpty()
        val best = sessions.filter { it.packageName != Env.ctx.packageName }
            .sortedWith(compareBy({ it.playbackState?.state != PlaybackState.STATE_PLAYING }, { it.packageName != "com.spotify.music" }))
            .firstOrNull()
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = best
            best?.registerCallback(callback)
        }
        publish()
    }

    private fun publish() {
        val c = controller
        val md = c?.metadata
        now.value = if (c == null || md == null) null else NowPlaying(
            c.packageName,
            md.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty(),
            md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART),
            c.playbackState?.state == PlaybackState.STATE_PLAYING,
        ).takeIf { it.title.isNotBlank() }
    }

    fun playPause() {
        val c = controller ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
    }

    /**
     * Canción siguiente (+1) o anterior (-1): la de la otra app si hay una sonando; si no, la de fondo de Heaven.
     */
    fun skip(dir: Int) {
        val c = controller
        if (c != null && now.value != null) { if (dir > 0) c.transportControls.skipToNext() else c.transportControls.skipToPrevious() }
        else Music.skip(dir)
    }

    // ---- Visualizador ----

    private var visualizer: Visualizer? = null
    private var visualizerSession = -1

    /**
     * Visualizador del reproductor de fondo de Heaven ([session] > 0). Android no deja leer la mezcla
     * global (sesión 0) en esta consola, así que lo de otras apps (Spotify) no se puede medir: ahí las
     * barras se animan solas. Con [session] 0 se apaga y no consume nada.
     */
    fun visualize(session: Int) {
        if (session == visualizerSession) return
        runCatching { visualizer?.enabled = false; visualizer?.release() }
        visualizer = null; visualizerSession = session
        bars.value = FloatArray(BARS)
        if (session <= 0) return
        visualizer = runCatching {
            Visualizer(session).apply {
                captureSize = Visualizer.getCaptureSizeRange()[0].coerceAtLeast(128)
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, rate: Int) = Unit
                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, rate: Int) { if (fft != null) bars.value = toBars(fft) }
                }, (Visualizer.getMaxCaptureRate() / 2).coerceAtMost(20_000), false, true)
                enabled = true
            }
        }.getOrNull()
    }

    /** Agrupa el espectro en bandas logarítmicas (graves a la izquierda) y lo suaviza con lo anterior. */
    private fun toBars(fft: ByteArray): FloatArray {
        val n = fft.size / 2
        val prev = bars.value
        return FloatArray(BARS) { b ->
            val from = (Math.pow(n.toDouble(), b / BARS.toDouble())).toInt().coerceIn(1, n - 1)
            val to = (Math.pow(n.toDouble(), (b + 1) / BARS.toDouble())).toInt().coerceIn(from + 1, n)
            var sum = 0.0
            for (k in from until to) sum += hypot(fft[2 * k].toDouble(), fft[2 * k + 1].toDouble())
            val level = (ln(1 + sum / (to - from)) / 5.0).toFloat().coerceIn(0f, 1f)
            prev[b] * 0.55f + level * 0.45f
        }
    }
}
