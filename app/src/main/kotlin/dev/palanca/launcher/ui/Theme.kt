package dev.palanca.launcher.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
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
    val Ink = Color(0xFFF2F3F5)
    val Dim = Color(0xFF9DA1AA)
    val Line = Color(0xFF282A30)
    val Panel = Color(0xFF131418)

    /** Texto sobre el color de acento: oscuro o claro según lo luminoso que sea el acento. */
    var OnAccent by mutableStateOf(Color(0xFF140A04))
        private set

    fun accentChanged(accent: Color) {
        OnAccent = if (accent.luminance() > 0.32f) Color(0xFF140A04) else Ink
    }
}

val ACCENTS = listOf(
    0xFF7C8CFF to "Índigo", 0xFF4FE3D0 to "Turquesa",
    0xFFFF6A2B to "Naranja", 0xFFFFB53D to "Ámbar", 0xFF5FE3B0 to "Menta",
    0xFF7FB0FF to "Azul", 0xFFFF7DA0 to "Rosa", 0xFFEDEAE3 to "Hueso",
)

/**
 * Fuente global: una familia para la interfaz y otra monoespaciada para los rótulos técnicos.
 * [scale] compensa las fuentes que dibujan más ancho o más pequeño que el resto.
 * Las familias se crean al usarlas: tener muchas no cuesta nada en marcha, solo tamaño de APK.
 */
class FontTheme(val id: String, val name: String, val scale: Float, ui: () -> FontFamily, mono: () -> FontFamily) {
    val ui by lazy(ui)
    val mono by lazy(mono)
}

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int) = FontFamily(
    listOf(300, 400, 500, 700).map { w -> Font(res, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w))) }
)
private fun fixed(regular: Int, bold: Int = regular) =
    FontFamily(Font(regular, FontWeight.Normal), Font(regular, FontWeight.Medium), Font(bold, FontWeight.Bold))

private val spaceMono = { fixed(R.font.space_mono, R.font.space_mono_bold) }
private val dmMono = { fixed(R.font.dm_mono) }
private val shareTech = { fixed(R.font.share_tech_mono) }
private val jetBrains = { variable(R.font.jetbrains_mono) }
private val vt323 = { fixed(R.font.vt323) }

val FONT_THEMES = listOf(
    FontTheme("halo", "Outfit (limpia)", 1f, { variable(R.font.outfit) }, { variable(R.font.outfit) }),
    FontTheme("grotesk", "Space Grotesk", 1f, { variable(R.font.space_grotesk) }, spaceMono),
    FontTheme("outfit", "Outfit + mono", 1f, { variable(R.font.outfit) }, dmMono),
    FontTheme("sora", "Sora", 0.94f, { variable(R.font.sora) }, jetBrains),
    FontTheme("rubik", "Rubik", 1f, { variable(R.font.rubik) }, dmMono),
    FontTheme("comfortaa", "Comfortaa", 0.94f, { variable(R.font.comfortaa) }, dmMono),
    FontTheme("exo", "Exo 2", 1f, { variable(R.font.exo2) }, shareTech),
    FontTheme("oxanium", "Oxanium", 1f, { variable(R.font.oxanium) }, shareTech),
    FontTheme("chakra", "Chakra Petch", 1f, { fixed(R.font.chakra_petch, R.font.chakra_petch_bold) }, shareTech),
    FontTheme("orbitron", "Orbitron", 0.86f, { variable(R.font.orbitron) }, shareTech),
    FontTheme("audiowide", "Audiowide", 0.86f, { fixed(R.font.audiowide) }, shareTech),
    FontTheme("bebas", "Bebas Neue", 1.12f, { fixed(R.font.bebas_neue) }, dmMono),
    FontTheme("playfair", "Playfair Display", 1f, { variable(R.font.playfair) }, dmMono),
    FontTheme("jetbrains", "JetBrains Mono", 0.92f, jetBrains, jetBrains),
    FontTheme("major", "Major Mono", 0.86f, { fixed(R.font.major_mono) }, spaceMono),
    FontTheme("pixelify", "Pixelify Sans", 1f, { variable(R.font.pixelify) }, vt323),
    FontTheme("silkscreen", "Silkscreen", 0.8f, { fixed(R.font.silkscreen) }, vt323),
    FontTheme("vt323", "VT323", 1.18f, vt323, vt323),
    FontTheme("press", "Press Start 2P", 0.6f, { fixed(R.font.press_start) }, { fixed(R.font.press_start) }),
)

fun fontTheme(id: String) = FONT_THEMES.firstOrNull { it.id == id } ?: FONT_THEMES[0]

val LocalFonts = staticCompositionLocalOf { FONT_THEMES[0] }

val LocalAccent = compositionLocalOf { Color(0xFFFF6A2B) }
val LocalCfg = compositionLocalOf { Config() }

/** Sube cuando cambian las apps instaladas: invalida los iconos sin que cada uno observe el flujo. */
val LocalIconGen = compositionLocalOf { 0 }

/** Forma base de paneles, filas y botones: esquinas redondeadas, proporcionales al tamaño de la pieza. */
fun chamfer(cut: Dp = 14.dp): Shape = RoundedCornerShape(cut * 1.4f)

// ---- Navegación entre pantallas --------------------------------------------------------

interface Screen {
    /** Las superposiciones se dibujan encima de la pantalla anterior. */
    val overlay: Boolean get() = false
    @Composable fun Content()
    /** @return true si la pantalla usó el evento. */
    fun onPad(e: PadEvent): Boolean
    /** Posición analógica del stick derecho (-1..1), para quien la quiera. */
    fun onStick(x: Float, y: Float) {}
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
    align: TextAlign = TextAlign.Start, outline: Float = 0f,
) {
    val fonts = LocalFonts.current
    BasicText(
        text, modifier,
        style = TextStyle(
            color = color, fontSize = (size * fonts.scale).sp, fontFamily = if (mono) fonts.mono else fonts.ui,
            fontWeight = weight, letterSpacing = spacing.sp, textAlign = align,
            drawStyle = if (outline > 0f) Stroke(outline, join = StrokeJoin.Round) else null,
        ),
        maxLines = maxLines, overflow = TextOverflow.Ellipsis,
    )
}

/** Rótulo técnico: mayúsculas, monoespaciado, atenuado. */
@Composable
fun Label(text: String, color: Color = P.Dim, modifier: Modifier = Modifier, size: Int = 12) =
    T(text.uppercase(), size, color, FontWeight.Medium, spacing = 0.9f, modifier = modifier)

/**
 * Indicador de foco: un anillo redondeado con un halo tenue que se ciñe al elemento.
 * (Conserva el nombre histórico "brackets"; [len] ya no se usa.)
 */
@Suppress("UNUSED_PARAMETER")
fun Modifier.brackets(on: Boolean, color: Color, gap: Dp = 7.dp, len: Dp = 14.dp, stroke: Dp = 3.dp): Modifier {
    // Sin foco no hay nada que animar ni que dibujar: cero coste para el resto de la rejilla.
    if (!on) return this
    return composed {
        val anim = remember { Animatable(0f) }
        LaunchedEffect(Unit) { anim.animateTo(1f, tween(160)) }
        drawWithContent {
            drawContent()
            val t = anim.value
            val g = gap.toPx() + (1f - t) * 10.dp.toPx()
            val w = stroke.toPx() * 0.85f
            val box = Size(size.width + 2 * g, size.height + 2 * g)
            val radius = (minOf(size.width, size.height) * 0.30f).coerceAtMost(30.dp.toPx()) + g
            val corner = CornerRadius(radius, radius)
            drawRoundRect(color.copy(alpha = 0.22f * t), Offset(-g, -g), box, corner, Stroke(w * 3.2f))
            drawRoundRect(color.copy(alpha = t), Offset(-g, -g), box, corner, Stroke(w))
        }
    }
}

/** Entrada de ventanas y pantallas: aparecen subiendo un poco y ganando opacidad. */
fun Modifier.enter(rise: Dp = 22.dp, fromScale: Float = 1f, millis: Int = 240): Modifier = composed {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(Unit) { anim.animateTo(1f, tween(millis, easing = FastOutSlowInEasing)) }
    graphicsLayer {
        val t = anim.value
        alpha = t
        translationY = (1f - t) * rise.toPx()
        val k = fromScale + (1f - fromScale) * t
        scaleX = k; scaleY = k
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
    Box(modifier.background(P.Panel, chamfer(cut)), content = content)

/** Barra de segmentos: nivel de batería, espacio, deslizadores. */
@Composable
fun Segments(fraction: Float, color: Color, modifier: Modifier = Modifier, count: Int = 16, height: Dp = 10.dp) {
    val lit = (fraction.coerceIn(0f, 1f) * count + 0.5f).toInt()
    Row(modifier.height(height), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(count) { i -> Box(Modifier.weight(1f).height(height).background(if (i < lit) color else P.Ink.copy(alpha = 0.13f), RoundedCornerShape(2.dp))) }
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
            .background(if (selected) P.Line else Color.Transparent, chamfer(12.dp))
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
