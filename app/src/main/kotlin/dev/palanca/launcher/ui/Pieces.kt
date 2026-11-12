package dev.palanca.launcher.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.palanca.launcher.Env
import dev.palanca.launcher.data.AppEntry
import dev.palanca.launcher.data.Look
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

// ---- Icono de app -----------------------------------------------------------------------

@Composable
fun AppIcon(app: AppEntry, size: Dp, modifier: Modifier = Modifier) {
    val cfg = LocalCfg.current
    val generation by Env.apps.generation.collectAsState()
    val scale = (cfg.look.iconScale * (cfg.icons[app.key]?.scale ?: 1f)).coerceIn(0.6f, 1.4f)
    val px = with(LocalDensity.current) { (size * scale).roundToPx() }
    val key = Env.icons.keyFor(app, cfg, px, generation)
    var bitmap by remember(key) {
        mutableStateOf(Env.icons.cached(key) ?: if (Env.syncIcons) Env.icons.render(key, app, cfg, px) else null)
    }
    if (bitmap == null) LaunchedEffect(key) {
        bitmap = withContext(Dispatchers.Default) { Env.icons.render(key, app, cfg, px) }
    }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(BitmapPainter(it), null, Modifier.requiredSize(size * scale)) }
    }
}

// ---- Reloj ------------------------------------------------------------------------------

val CLOCK_STYLES = listOf("matriz" to "Matriz de puntos", "segmentos" to "Segmentos", "grande" to "Grande", "fino" to "Fino")

/** Hora fija para pruebas y capturas; null = hora real. */
var fixedNow: LocalDateTime? = null

@Composable
fun rememberNow(seconds: Boolean): State<LocalDateTime> =
    produceState(fixedNow ?: LocalDateTime.now(), seconds) {
        while (fixedNow == null) {
            val now = LocalDateTime.now()
            value = now
            val nanoMs = now.nano / 1_000_000L
            delay(if (seconds) 1000L - nanoMs else (60 - now.second) * 1000L - nanoMs)
        }
    }

fun timeText(look: Look, t: LocalDateTime): String {
    val pattern = (if (look.clock24) "HH:mm" else "hh:mm") + if (look.clockSeconds) ":ss" else ""
    return t.format(DateTimeFormatter.ofPattern(pattern))
}

fun dateText(t: LocalDateTime): String =
    t.format(DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", Locale("es")))

private val DOT_FONT = mapOf(
    '0' to "01110 10001 10011 10101 11001 10001 01110", '1' to "00100 01100 00100 00100 00100 00100 01110",
    '2' to "01110 10001 00001 00010 00100 01000 11111", '3' to "11110 00001 00001 01110 00001 00001 11110",
    '4' to "00010 00110 01010 10010 11111 00010 00010", '5' to "11111 10000 11110 00001 00001 10001 01110",
    '6' to "00110 01000 10000 11110 10001 10001 01110", '7' to "11111 00001 00010 00100 01000 01000 01000",
    '8' to "01110 10001 10001 01110 10001 10001 01110", '9' to "01110 10001 10001 01111 00001 00010 01100",
    ':' to "0 0 1 0 1 0 0",
).mapValues { it.value.split(' ') }

// Segmentos a b c d e f g por dígito.
private val SEGMENTS = mapOf(
    '0' to "abcdef", '1' to "bc", '2' to "abged", '3' to "abgcd", '4' to "fgbc",
    '5' to "afgcd", '6' to "afgedc", '7' to "abc", '8' to "abcdefg", '9' to "abfgcd",
)

@Composable
fun ClockView(look: Look, height: Dp, time: LocalDateTime, modifier: Modifier = Modifier) {
    val text = timeText(look, time)
    val accent = LocalAccent.current
    when (look.clockStyle) {
        "grande" -> T(text, (height.value * 1.02f).toInt(), weight = FontWeight.Medium, spacing = -height.value * 0.035f, modifier = modifier)
        "fino" -> T(text, (height.value * 1.02f).toInt(), weight = FontWeight.Light, spacing = height.value * 0.01f, modifier = modifier)
        "segmentos" -> {
            val digitW = height * 0.52f; val gap = height * 0.16f; val colonW = height * 0.14f
            val width = text.fold(0.dp) { w, c -> w + (if (c == ':') colonW else digitW) + gap } - gap
            Canvas(modifier.width(width).height(height)) {
                val h = size.height; val dw = digitW.toPx(); val g = gap.toPx(); val cw = colonW.toPx()
                val stroke = h * 0.085f; val inset = stroke * 0.75f
                var x = 0f
                for (c in text) {
                    if (c == ':') {
                        drawCircle(accent, stroke * 0.62f, Offset(x + cw / 2, h * 0.32f))
                        drawCircle(accent, stroke * 0.62f, Offset(x + cw / 2, h * 0.68f))
                        x += cw + g; continue
                    }
                    val on = SEGMENTS[c].orEmpty()
                    val l = x + stroke / 2; val r = x + dw - stroke / 2
                    val t = stroke / 2; val m = h / 2; val b = h - stroke / 2
                    fun seg(name: Char, a: Offset, z: Offset) =
                        drawLine(if (name in on) P.Ink else P.Line, a, z, stroke, StrokeCap.Butt)
                    seg('a', Offset(l + inset, t), Offset(r - inset, t))
                    seg('g', Offset(l + inset, m), Offset(r - inset, m))
                    seg('d', Offset(l + inset, b), Offset(r - inset, b))
                    seg('f', Offset(l, t + inset), Offset(l, m - inset))
                    seg('e', Offset(l, m + inset), Offset(l, b - inset))
                    seg('b', Offset(r, t + inset), Offset(r, m - inset))
                    seg('c', Offset(r, m + inset), Offset(r, b - inset))
                    x += dw + g
                }
            }
        }
        else -> {
            val pitch = height / 7
            val cols = text.sumOf { DOT_FONT[it]!![0].length + 1 } - 1
            Canvas(modifier.width(pitch * cols).height(height)) {
                val p = pitch.toPx()
                val lit = ArrayList<Offset>(); val unlit = ArrayList<Offset>(); val colon = ArrayList<Offset>()
                var col = 0
                for (c in text) {
                    val rows = DOT_FONT[c]!!
                    for (r in 0 until 7) for (k in rows[r].indices) {
                        val o = Offset((col + k + 0.5f) * p, (r + 0.5f) * p)
                        when {
                            rows[r][k] != '1' -> if (c != ':') unlit += o
                            c == ':' -> colon += o
                            else -> lit += o
                        }
                    }
                    col += rows[0].length + 1
                }
                drawPoints(unlit, PointMode.Points, P.Line.copy(alpha = 0.55f), p * 0.5f, StrokeCap.Round)
                drawPoints(lit, PointMode.Points, P.Ink, p * 0.74f, StrokeCap.Round)
                drawPoints(colon, PointMode.Points, accent, p * 0.74f, StrokeCap.Round)
            }
        }
    }
}

// ---- Batería ----------------------------------------------------------------------------

data class Battery(val percent: Int, val charging: Boolean)

/** Batería fija para capturas; null = lectura real. */
var fixedBattery: Battery? = null

@Composable
fun rememberBattery(): State<Battery> {
    val ctx = LocalContext.current
    val state = remember { mutableStateOf(fixedBattery ?: Battery(-1, false)) }
    DisposableEffect(Unit) {
        if (fixedBattery != null) return@DisposableEffect onDispose { }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent) {
                val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                state.value = Battery(
                    if (level < 0) -1 else level * 100 / scale,
                    status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
                )
            }
        }
        ctx.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        onDispose { runCatching { ctx.unregisterReceiver(receiver) } }
    }
    return state
}

// ---- Fondo ------------------------------------------------------------------------------

val WALLPAPERS = listOf(
    "trama" to "Trama de puntos", "negro" to "Negro AMOLED", "constelacion" to "Constelación (animado)",
    "ondas" to "Ondas (animado)", "imagen" to "Imagen propia", "sistema" to "Fondo de Android",
)

/**
 * Fondo del Inicio. Los animados solo avanzan mientras esta composición está en pantalla
 * (el launcher en primer plano) y redibujan sin recomponer: el tiempo se lee en la fase de dibujo.
 */
@Composable
fun Wallpaper(look: Look, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    val scrim = Color.Black.copy(alpha = look.wallpaperDim.coerceIn(0, 90) / 100f)
    when (look.wallpaper) {
        "negro" -> Box(modifier.fillMaxSize().background(Color.Black))
        "sistema" -> Box(modifier.fillMaxSize().background(scrim))
        "imagen" -> {
            var image by remember(look.wallpaperFile) { mutableStateOf<ImageBitmap?>(null) }
            LaunchedEffect(look.wallpaperFile) {
                image = withContext(Dispatchers.IO) {
                    look.wallpaperFile?.let { name ->
                        val path = File(Env.ctx.filesDir, name).path
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(path, bounds)
                        var sample = 1
                        while (bounds.outHeight / (sample * 2) >= 1080) sample *= 2
                        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
                    }
                }
            }
            Box(modifier.fillMaxSize().background(P.Bg)) {
                image?.let { Image(BitmapPainter(it), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                Box(Modifier.fillMaxSize().background(scrim))
            }
        }
        "constelacion", "ondas" -> {
            val time = remember { mutableFloatStateOf(0f) }
            LaunchedEffect(Unit) {
                val start = withFrameNanos { it }
                while (true) withFrameNanos { time.floatValue = (it - start) / 1e9f }
            }
            val waves = look.wallpaper == "ondas"
            Canvas(modifier.fillMaxSize().background(P.Bg)) {
                val t = time.floatValue
                if (waves) drawWaves(t, accent) else drawStars(t, accent)
            }
        }
        else -> Box(
            modifier.fillMaxSize().background(P.Bg).drawWithCache {
                val pitch = 26.dp.toPx()
                val points = ArrayList<Offset>()
                var y = pitch / 2
                while (y < size.height) {
                    var x = pitch / 2
                    while (x < size.width) { points += Offset(x, y); x += pitch }
                    y += pitch
                }
                onDrawBehind { drawPoints(points, PointMode.Points, Color.White.copy(alpha = 0.075f), 2.2.dp.toPx(), StrokeCap.Round) }
            }
        )
    }
}

private fun DrawScope.drawStars(t: Float, accent: Color) {
    val w = size.width; val h = size.height
    val far = ArrayList<Offset>(48); val near = ArrayList<Offset>(16)
    for (i in 0 until 64) {
        // Posición pseudoaleatoria estable por índice; cada punto deriva a su propia velocidad.
        val sx = ((i * 7919) % 1000) / 1000f; val sy = ((i * 104729) % 1000) / 1000f
        val speed = 0.004f + (i % 5) * 0.003f
        val x = ((sx + t * speed) % 1f) * w
        val y = (sy + 0.02f * sin(t * 0.3f + i)) * h
        if (i % 4 == 0) near += Offset(x, y) else far += Offset(x, y)
    }
    drawPoints(far, PointMode.Points, Color.White.copy(alpha = 0.22f), 2.5.dp.toPx(), StrokeCap.Round)
    drawPoints(near, PointMode.Points, accent.copy(alpha = 0.55f), 4.dp.toPx(), StrokeCap.Round)
}

private fun DrawScope.drawWaves(t: Float, accent: Color) {
    val w = size.width; val h = size.height
    for (line in 0 until 4) {
        val points = ArrayList<Offset>(50)
        val base = h * (0.52f + line * 0.11f)
        for (i in 0..48) {
            val x = w * i / 48f
            val phase = (i / 48f) * 2 * PI.toFloat() * (1.2f + line * 0.3f) + t * (0.35f + line * 0.12f)
            points += Offset(x, base + sin(phase) * h * 0.035f)
        }
        drawPoints(
            points, PointMode.Polygon,
            if (line == 1) accent.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.12f),
            1.5.dp.toPx(), StrokeCap.Round,
        )
    }
}
