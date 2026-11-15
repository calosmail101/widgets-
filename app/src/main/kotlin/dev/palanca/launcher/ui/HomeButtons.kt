package dev.palanca.launcher.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.palanca.launcher.Env
import dev.palanca.launcher.data.WidgetSlot
import dev.palanca.launcher.system.Media
import dev.palanca.launcher.system.Music
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Alto común de los botones de la segunda fila de Inicio. */
private val ROW_HEIGHT = 84.dp

/**
 * Óvalo de música, con la misma forma que el de jugar pero de cristal. Muestra lo que suena en otra app
 * (Spotify primero); si no suena nada fuera, la canción de fondo de Heaven. A la derecha, un visualizador
 * de siete barras con el sonido que sale por el altavoz.
 */
@Composable
fun MusicPill(selected: Boolean, modifier: Modifier = Modifier, onTap: () -> Unit) {
    val accent = LocalAccent.current
    val now by Media.now.collectAsState()
    val background by Music.title.collectAsState()
    val foreground by Env.foreground.collectAsState()
    // Permisos (con PServer), sesiones multimedia y visualizador: solo con Inicio a la vista.
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { Media.ensurePermissions() }; Media.start() }
    // La música de fondo no avisa al empezar o pararse: se mira cada medio segundo mientras Inicio está a la vista.
    val ownPlaying by produceState(false, foreground) { while (foreground) { value = Music.playing; delay(500) } ; value = false }
    val external = now
    // Barras reales solo con la música de Heaven (Android no deja medir el audio de otras apps aquí).
    LaunchedEffect(foreground, ownPlaying, external == null) { Media.visualize(if (foreground && ownPlaying && external == null) Music.session else 0) }
    val playing = external?.playing == true || (external == null && background.isNotEmpty() && ownPlaying)
    val title = external?.title ?: background.ifEmpty { "Sin música" }
    val subtitle = when {
        external != null -> external.artist.ifEmpty { appName(external.pkg) }
        background.isNotEmpty() -> "Música de Heaven"
        else -> "A para abrir Spotify"
    }
    Row(
        modifier.requiredHeight(ROW_HEIGHT).brackets(selected, P.Ink, gap = 4.dp, radius = ROW_HEIGHT / 2)
            .glassPanel(CircleShape).tap(onTap = onTap).padding(start = 12.dp, end = 26.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(ROW_HEIGHT - 22.dp).clip(CircleShape).background(accent.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
            val art = external?.art
            if (art != null) Image(art.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else NoteGlyph(if (playing) P.Ink else P.Dim, Modifier.size(28.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            T(title, 17, if (external != null || background.isNotEmpty()) P.Ink else P.Dim, FontWeight.Medium)
            T(subtitle, 13, P.Dim)
        }
        Spacer(Modifier.width(12.dp))
        Bars(playing, accent, Modifier.width(54.dp).height(34.dp))
    }
}

private fun appName(pkg: String) = Env.apps.apps.value.firstOrNull { it.pkg == pkg }?.label ?: "Reproduciendo"

/**
 * Siete barras. Con el visualizador del sistema siguen el sonido real; si Android no lo deja usar,
 * se mueven solas mientras suena algo. Paradas, quedan como puntos.
 */
@Composable
private fun Bars(playing: Boolean, color: Color, modifier: Modifier) {
    val levels by Media.bars.collectAsState()
    val real = levels.any { it > 0.02f }
    val wave = rememberInfiniteTransition(label = "barras")
    val phase by wave.animateFloat(0f, 6.2832f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "fase")
    val animated = List(Media.BARS) { i ->
        val target = when {
            !playing -> 0f
            real -> levels[i]
            else -> 0.35f + 0.3f * kotlin.math.sin(phase + i * 0.9f).toFloat()
        }
        animateFloatAsState(target, tween(120), label = "barra$i").value
    }
    Canvas(modifier) {
        val gap = size.width / (Media.BARS * 2 - 1)
        animated.forEachIndexed { i, v ->
            val h = (size.height * (0.12f + 0.88f * v.coerceIn(0f, 1f))).coerceAtLeast(gap)
            drawRoundRect(
                color.copy(alpha = if (playing) 1f else 0.4f),
                Offset(i * gap * 2, (size.height - h) / 2), Size(gap, h), CornerRadius(gap / 2, gap / 2),
            )
        }
    }
}

/** Corchea sencilla, del mismo trazo que los demás símbolos de Heaven. */
@Composable
private fun NoteGlyph(color: Color, modifier: Modifier) = Canvas(modifier) {
    val w = size.width; val h = size.height; val stroke = w * 0.09f
    drawCircle(color, w * 0.17f, Offset(w * 0.3f, h * 0.76f))
    drawLine(color, Offset(w * 0.45f, h * 0.76f), Offset(w * 0.45f, h * 0.14f), stroke, StrokeCap.Round)
    drawLine(color, Offset(w * 0.45f, h * 0.14f), Offset(w * 0.78f, h * 0.3f), stroke, StrokeCap.Round)
}

/** Botón redondo de archivos, a juego con el óvalo de música. */
@Composable
fun FilesButton(selected: Boolean, onTap: () -> Unit) {
    Box(
        // Mismo alto que el botón de jugar, que queda a su izquierda.
        Modifier.size(98.dp).brackets(selected, P.Ink, gap = 4.dp, radius = 49.dp)
            .glassPanel(CircleShape).tap(onTap = onTap),
        contentAlignment = Alignment.Center,
    ) { FolderGlyph(if (selected) P.Ink else P.Ink.copy(alpha = 0.8f), Modifier.size(36.dp)) }
}

@Composable
private fun FolderGlyph(color: Color, modifier: Modifier) = Canvas(modifier) {
    val w = size.width; val h = size.height
    val path = Path().apply {
        moveTo(w * 0.08f, h * 0.24f); lineTo(w * 0.4f, h * 0.24f); lineTo(w * 0.5f, h * 0.36f)
        lineTo(w * 0.92f, h * 0.36f); lineTo(w * 0.92f, h * 0.82f); lineTo(w * 0.08f, h * 0.82f); close()
    }
    drawPath(path, color, style = Stroke(w * 0.07f, join = StrokeJoin.Round))
}

/** Widget de la página de widgets mostrado también en Inicio. Responde al toque; con A se abre su app. */
@Composable
fun HomeWidget(slot: WidgetSlot, selected: Boolean) {
    Box(
        Modifier.fillMaxWidth().height(150.dp).brackets(selected, LocalAccent.current, gap = 3.dp, radius = 22.dp)
            .clip(RoundedCornerShape(22.dp)),
    ) { WidgetView(slot) }
}

