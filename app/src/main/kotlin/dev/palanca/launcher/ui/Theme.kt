package dev.palanca.launcher.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.palanca.launcher.R
import dev.palanca.launcher.data.Config
import dev.palanca.launcher.input.PadEvent

/**
 * Lenguaje visual: negro AMOLED, tinta hueso, un solo acento, texto técnico en monoespaciado,
 * esquinas en chaflán y corchetes como indicador de foco.
 *
 * Todas las medidas están en "unidades de diseño": la pantalla mide siempre 720 de alto
 * (ver Root), así la interfaz se ve igual en cualquier resolución.
 */
object P {
    val Bg = Color(0xFF050505)
    val Ink = Color(0xFFEDEAE3)
    val Dim = Color(0xFF9C988F)
    val Line = Color(0xFF2E2C29)
    val Panel = Color(0xFF10100F)
    val OnAccent = Color(0xFF140A04)
}

val ACCENTS = listOf(
    0xFFFF6A2B to "Naranja", 0xFFFFB53D to "Ámbar", 0xFF5FE3B0 to "Menta",
    0xFF7FB0FF to "Azul", 0xFFFF7DA0 to "Rosa", 0xFFEDEAE3 to "Hueso",
)

@OptIn(ExperimentalTextApi::class)
val Grotesk = FontFamily(
    listOf(300, 400, 500, 700).map { w ->
        Font(R.font.space_grotesk, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    }
)
val Mono = FontFamily(Font(R.font.space_mono, FontWeight.Normal), Font(R.font.space_mono_bold, FontWeight.Bold))

val LocalAccent = compositionLocalOf { Color(0xFFFF6A2B) }
val LocalCfg = compositionLocalOf { Config() }

/** Sube cuando cambian las apps instaladas: invalida los iconos sin que cada uno observe el flujo. */
val LocalIconGen = compositionLocalOf { 0 }

/** Esquina superior derecha cortada: el gesto que se repite en paneles, etiquetas y botones. */
fun chamfer(cut: Dp = 14.dp): Shape = CutCornerShape(topStart = 0.dp, topEnd = cut, bottomEnd = 0.dp, bottomStart = 0.dp)

// ---- Navegación entre pantallas --------------------------------------------------------

interface Screen {
    /** Las superposiciones se dibujan encima de la pantalla anterior. */
    val overlay: Boolean get() = false
    @Composable fun Content()
    /** @return true si la pantalla usó el evento. */
    fun onPad(e: PadEvent): Boolean
}

class Navigator {
    val stack = mutableStateListOf<Screen>()
    val top: Screen? get() = stack.lastOrNull()
    fun push(s: Screen) { stack.add(s) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun home() { while (stack.size > 1) stack.removeAt(stack.lastIndex) }
}

// ---- Piezas comunes ---------------------------------------------------------------------

@Composable
fun T(
    text: String, size: Int = 16, color: Color = P.Ink, weight: FontWeight = FontWeight.Normal,
    mono: Boolean = false, modifier: Modifier = Modifier, maxLines: Int = 1, spacing: Float = 0f,
    align: TextAlign = TextAlign.Start,
) = BasicText(
    text, modifier,
    style = TextStyle(
        color = color, fontSize = size.sp, fontFamily = if (mono) Mono else Grotesk, fontWeight = weight,
        letterSpacing = spacing.sp, textAlign = align,
    ),
    maxLines = maxLines, overflow = TextOverflow.Ellipsis,
)

/** Rótulo técnico: mayúsculas, monoespaciado, atenuado. */
@Composable
fun Label(text: String, color: Color = P.Dim, modifier: Modifier = Modifier, size: Int = 12) =
    T(text.uppercase(), size, color, mono = true, spacing = 1.4f, modifier = modifier)

/** Corchetes de foco: cuatro esquinas que se cierran sobre el elemento seleccionado. */
fun Modifier.brackets(on: Boolean, color: Color, gap: Dp = 7.dp, len: Dp = 14.dp, stroke: Dp = 3.dp): Modifier {
    // Sin foco no hay nada que animar ni que dibujar: cero coste para el resto de la rejilla.
    if (!on) return this
    return composed {
        val anim = remember { Animatable(0f) }
        LaunchedEffect(Unit) { anim.animateTo(1f, tween(110)) }
        drawWithContent {
            drawContent()
            val t = anim.value
            val g = gap.toPx() + (1f - t) * 12.dp.toPx()
            val l = len.toPx(); val w = stroke.toPx()
            val c = color.copy(alpha = t)
            val x0 = -g; val y0 = -g; val x1 = size.width + g; val y1 = size.height + g
            fun line(ax: Float, ay: Float, bx: Float, by: Float) =
                drawLine(c, Offset(ax, ay), Offset(bx, by), w, StrokeCap.Square)
            line(x0, y0, x0 + l, y0); line(x0, y0, x0, y0 + l)
            line(x1, y0, x1 - l, y0); line(x1, y0, x1, y0 + l)
            line(x0, y1, x0 + l, y1); line(x0, y1, x0, y1 - l)
            line(x1, y1, x1 - l, y1); line(x1, y1, x1, y1 - l)
        }
    }
}

/** Crece al recibir el foco; al perderlo vuelve a su tamaño al instante. */
fun Modifier.focusScale(on: Boolean, to: Float = 1.12f): Modifier {
    if (!on) return this
    return composed {
        val anim = remember { Animatable(1f) }
        LaunchedEffect(Unit) { anim.animateTo(to, tween(110)) }
        graphicsLayer { scaleX = anim.value; scaleY = anim.value }
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, cut: Dp = 16.dp, content: @Composable BoxScope.() -> Unit) =
    Box(modifier.background(P.Panel, chamfer(cut)).border(1.dp, P.Line, chamfer(cut)), content = content)

/** Barra de segmentos: nivel de batería, espacio, deslizadores. */
@Composable
fun Segments(fraction: Float, color: Color, modifier: Modifier = Modifier, count: Int = 16, height: Dp = 10.dp) {
    val lit = (fraction.coerceIn(0f, 1f) * count + 0.5f).toInt()
    Row(modifier.height(height), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(count) { i -> Box(Modifier.weight(1f).height(height).background(if (i < lit) color else P.Line)) }
    }
}

@Composable
fun Glyph(name: String, primary: Boolean = false) {
    val accent = LocalAccent.current
    val round = name.length == 1
    val shape = if (round) CircleShape else RoundedCornerShape(5.dp)
    Box(
        Modifier.height(24.dp).defaultMinSize(minWidth = 24.dp)
            .then(if (primary) Modifier.background(accent, shape) else Modifier.border(1.5.dp, P.Dim, shape))
            .padding(horizontal = if (round) 0.dp else 7.dp),
        contentAlignment = Alignment.Center,
    ) { T(name, 11, if (primary) P.OnAccent else P.Dim, FontWeight.Bold, mono = true) }
}

/** Leyenda de botones al pie. La primera pareja es la acción principal. */
@Composable
fun HintBar(hints: List<Pair<String, String>>, modifier: Modifier = Modifier, left: String? = null) {
    Row(modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
        if (left != null) Label(left)
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            hints.forEachIndexed { i, (glyphs, text) ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    glyphs.split(' ').forEach { Glyph(it, primary = i == 0) }
                    Spacer(Modifier.width(2.dp))
                    T(text, 14, P.Dim)
                }
            }
        }
    }
}

/** Marco de las pantallas secundarias: cabecera numerada, contenido y leyenda. */
@Composable
fun Frame(
    index: String, title: String, subtitle: String, hints: List<Pair<String, String>>,
    footer: String? = null, content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().background(P.Bg).padding(start = 56.dp, end = 56.dp, top = 30.dp, bottom = 24.dp)) {
        Row(Modifier.height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            T(index, 15, LocalAccent.current, FontWeight.Bold, mono = true)
            Spacer(Modifier.width(14.dp))
            T(title, 30, weight = FontWeight.Medium)
            Spacer(Modifier.width(18.dp))
            T(subtitle, 15, P.Dim, modifier = Modifier.weight(1f))
        }
        Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(1.dp).background(P.Line))
        Column(Modifier.weight(1f).padding(vertical = 20.dp), content = content)
        HintBar(hints, left = footer)
    }
}

/** Fila genérica de lista: título a la izquierda, valor a la derecha, corchetes si tiene el foco. */
@Composable
fun ListRow(
    title: String, value: String, selected: Boolean, modifier: Modifier = Modifier,
    arrows: Boolean = false, height: Dp = 54.dp, trailing: (@Composable () -> Unit)? = null,
) {
    val accent = LocalAccent.current
    Row(
        modifier.fillMaxWidth().height(height)
            .background(if (selected) P.Panel else Color.Transparent, chamfer(12.dp))
            .brackets(selected, accent, gap = 2.dp, len = 10.dp, stroke = 2.5.dp)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        T(title, 16, if (selected) P.Ink else P.Dim, if (selected) FontWeight.Medium else FontWeight.Normal, modifier = Modifier.weight(1f))
        if (trailing != null) trailing()
        else {
            if (arrows && selected) T("‹ ", 16, accent, FontWeight.Bold, mono = true)
            T(value, 14, if (selected) accent else P.Dim, mono = true)
            if (arrows && selected) T(" ›", 16, accent, FontWeight.Bold, mono = true)
        }
    }
}

@Composable
fun Dot(color: Color, size: Dp = 6.dp) = Box(Modifier.size(size).background(color, CircleShape))
