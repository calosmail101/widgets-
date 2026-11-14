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
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.lerp
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

/** Multiplicador del tamaño de todo el texto (ajuste "Tamaño del texto"). */
val LocalTextScale = staticCompositionLocalOf { 1f }

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
    /** Si esta pantalla, siendo superposición, desenfoca lo que tiene debajo. */
    val blursBelow: Boolean get() = overlay
}

/** Pantalla que tiene animación de salida: [closing] pasa a true y poco después se retira. */
interface Closable {
    var closing: Boolean
}

class Navigator {
    val stack = mutableStateListOf<Screen>()

    /** La pantalla que recibe el mando: la de más arriba que no se esté cerrando. */
    val top: Screen? get() = stack.lastOrNull { !(it is Closable && it.closing) }

    fun push(s: Screen) { stack.add(s) }

    fun pop() {
        val screen = top ?: return
        if (stack.indexOf(screen) <= 0) return
        if (screen is Closable) {
            // Deja que la ventana se recoja antes de quitarla.
            screen.closing = true
            dev.palanca.launcher.Env.scope.launch { kotlinx.coroutines.delay(EXIT_MS.toLong()); stack.remove(screen) }
        } else stack.remove(screen)
    }

    fun home() { while (stack.size > 1) stack.removeAt(stack.lastIndex) }

    companion object { const val EXIT_MS = 260 }
}

// ---- Piezas comunes ---------------------------------------------------------------------

@Composable
fun T(
    text: String, size: Int = 16, color: Color = P.Ink, weight: FontWeight = FontWeight.Normal,
    mono: Boolean = false, modifier: Modifier = Modifier, maxLines: Int = 1, spacing: Float = 0f,
    align: TextAlign = TextAlign.Start, outline: Float = 0f, tight: Boolean = false,
) {
    val fonts = LocalFonts.current
    val scale = fonts.scale * LocalTextScale.current
    BasicText(
        text, modifier,
        style = TextStyle(
            color = color, fontSize = (size * scale).sp, fontFamily = if (mono) fonts.mono else fonts.ui,
            fontWeight = weight, letterSpacing = spacing.sp, textAlign = align,
            drawStyle = if (outline > 0f) Stroke(outline, join = StrokeJoin.Round) else null,
            // "tight": sin el relleno que la fuente añade arriba y abajo, que descentra las letras sueltas.
            platformStyle = if (tight) PlatformTextStyle(includeFontPadding = false) else null,
            lineHeight = if (tight) (size * scale).sp else TextUnit.Unspecified,
            lineHeightStyle = if (tight) LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both) else null,
        ),
        maxLines = maxLines, overflow = TextOverflow.Ellipsis,
    )
}

/** Rótulo técnico: mayúsculas, monoespaciado, atenuado. */
@Composable
fun Label(text: String, color: Color = P.Dim, modifier: Modifier = Modifier, size: Int = 12) =
    T(text.uppercase(), size, color, FontWeight.Medium, spacing = 0.9f, modifier = modifier)

/**
 * Indicador de foco: un anillo con un halo tenue que sigue el contorno del elemento.
 * [radius] es el radio de esquina del propio elemento; sin él se estima a partir del tamaño.
 * (Conserva el nombre histórico "brackets"; [len] ya no se usa.)
 */
@Suppress("UNUSED_PARAMETER")
fun Modifier.brackets(
    on: Boolean, color: Color, gap: Dp = 4.dp, len: Dp = 14.dp, stroke: Dp = 3.dp, radius: Dp? = null,
): Modifier {
    // Sin foco no hay nada que animar ni que dibujar: cero coste para el resto de la rejilla.
    if (!on) return this
    return composed {
        val anim = remember { Animatable(0f) }
        LaunchedEffect(Unit) { anim.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 420f)) }
        drawWithContent {
            drawContent()
            val t = anim.value
            // Entra desde fuera y rebota un poco al ceñirse.
            val g = gap.toPx() + (1f - t) * 14.dp.toPx()
            val w = stroke.toPx() * 0.85f
            val box = Size(size.width + 2 * g, size.height + 2 * g)
            val own = radius?.toPx()?.coerceAtMost(minOf(size.width, size.height) / 2f)
                ?: (minOf(size.width, size.height) * 0.30f).coerceAtMost(30.dp.toPx())
            val corner = CornerRadius(own + g, own + g)
            val alpha = t.coerceIn(0f, 1f)
            drawRoundRect(color.copy(alpha = 0.22f * alpha), Offset(-g, -g), box, corner, Stroke(w * 3.2f))
            drawRoundRect(color.copy(alpha = alpha), Offset(-g, -g), box, corner, Stroke(w))
        }
    }
}

/** Anillo de foco para iconos: usa el mismo contorno que la máscara del icono (squircle, círculo, hexágono…). */
fun Modifier.iconFocus(on: Boolean, shape: String, color: Color, gap: Dp = 4.dp): Modifier {
    if (!on) return this
    if (shape == "ninguna") return brackets(true, color, gap)
    return composed {
        val anim = remember { Animatable(0f) }
        LaunchedEffect(Unit) { anim.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 420f)) }
        drawWithCache {
            val outline = dev.palanca.launcher.icons.shapePath(shape, size.minDimension).asComposePath()
            onDrawWithContent {
                drawContent()
                val t = anim.value
                val g = gap.toPx() + (1f - t) * 14.dp.toPx()
                val k = (size.minDimension + 2 * g) / size.minDimension
                val alpha = t.coerceIn(0f, 1f)
                withTransform({ translate(-g, -g); scale(k, k, Offset.Zero) }) {
                    drawPath(outline, color.copy(alpha = 0.22f * alpha), style = Stroke(8.dp.toPx() / k))
                    drawPath(outline, color.copy(alpha = alpha), style = Stroke(2.6.dp.toPx() / k))
                }
            }
        }
    }
}

/**
 * Entrada de ventanas y pantallas: suben desde [rise], crecen desde [fromScale] y se asientan
 * con un rebote corto. [delay] permite escalonar varias piezas.
 */
fun Modifier.enter(rise: Dp = 60.dp, fromScale: Float = 0.94f, millis: Int = 420, delay: Int = 0): Modifier = composed {
    val anim = remember { Animatable(0f) }
    val fade = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (delay > 0) kotlinx.coroutines.delay(delay.toLong())
        launch { fade.animateTo(1f, tween(millis / 2)) }
        // Sale rápido y frena pasándose un poco: da sensación de peso.
        anim.animateTo(1f, tween(millis, easing = CubicBezierEasing(0.18f, 1.25f, 0.4f, 1f)))
    }
    graphicsLayer {
        val t = anim.value
        alpha = fade.value
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
        LaunchedEffect(Unit) { anim.animateTo(to, spring(dampingRatio = 0.5f, stiffness = 380f)) }
        graphicsLayer { scaleX = anim.value; scaleY = anim.value }
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, cut: Dp = 16.dp, content: @Composable BoxScope.() -> Unit) =
    Box(modifier.glassPanel(chamfer(cut)), content = content)

/** Fondo de las piezas de Inicio: translúcido con un filo de luz si el cristal está activo; opaco si no. */
fun Modifier.glassPanel(shape: Shape): Modifier = composed {
    if (LocalCfg.current.look.glass) {
        background(Color.White.copy(alpha = 0.075f), shape)
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.26f), Color.White.copy(alpha = 0.04f))), shape)
    } else background(P.Panel, shape)
}

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
    val arrow = name == "◀" || name == "▶" || name == "▼"
    val round = name.length == 1
    val shape = if (round) CircleShape else RoundedCornerShape(7.dp)
    val ink = if (primary) P.OnAccent else P.Dim
    val side = 24.dp * LocalTextScale.current.coerceAtLeast(1f)
    Box(
        Modifier.height(side).defaultMinSize(minWidth = side)
            .then(if (primary) Modifier.background(accent, shape) else Modifier.border(1.5.dp, P.Dim, shape))
            .padding(horizontal = if (round) 0.dp else 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (arrow) {
            // Las flechas se dibujan: los caracteres ◀ ▶ nunca quedan centrados en un círculo.
            Canvas(Modifier.size(9.dp)) {
                val path = Path().apply {
                    if (name == "▼") { moveTo(0f, size.height * 0.15f); lineTo(size.width, size.height * 0.15f); lineTo(size.width / 2, size.height * 0.95f) }
                    else if (name == "◀") { moveTo(size.width * 0.85f, 0f); lineTo(size.width * 0.85f, size.height); lineTo(0.05f * size.width, size.height / 2) }
                    else { moveTo(size.width * 0.15f, 0f); lineTo(size.width * 0.15f, size.height); lineTo(0.95f * size.width, size.height / 2) }
                    close()
                }
                drawPath(path, ink)
            }
        } else T(name, 11, ink, FontWeight.Bold, mono = true, tight = true)
    }
}

/**
 * Ventana flotante. Con el modo cristal es translúcida y deja ver, desenfocado, lo que hay detrás
 * (el desenfoque lo aplica Root a las pantallas de debajo); sin él es opaca.
 */
@Composable
fun Window(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val glass = LocalCfg.current.look.glass
    val shape = RoundedCornerShape(36.dp)
    Column(
        modifier
            .background(if (glass) Color(0xFF1B1C22).copy(alpha = 0.52f) else Color(0xFF191A1F), shape)
            .then(
                if (glass) Modifier.border(
                    1.2.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.34f), Color.White.copy(alpha = 0.06f))), shape,
                ) else Modifier
            )
            .tap { }.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 18.dp),
        content = content,
    )
}

/**
 * Capa de las ventanas: un velo oscuro y, encima, el contenido creciendo como una gota desde
 * [origin] (un punto de la pantalla en fracciones 0..1). Al cerrarse ([closing]) se recoge en
 * ese mismo punto. Tocar el velo llama a [onDismiss].
 */
@Composable
fun Popup(origin: TransformOrigin, closing: Boolean, onDismiss: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    val glass = LocalCfg.current.look.glass
    val grow = remember { Animatable(0f) }
    val veil = remember { Animatable(0f) }
    LaunchedEffect(closing) {
        if (closing) {
            launch { veil.animateTo(0f, tween(Navigator.EXIT_MS)) }
            grow.animateTo(0f, tween(Navigator.EXIT_MS, easing = CubicBezierEasing(0.4f, 0f, 0.85f, 0.5f)))
        } else {
            // Dos fotogramas de margen: el contenido de la ventana se compone antes de empezar a crecer,
            // así ese trabajo no se come los primeros fotogramas de la animación.
            withFrameNanos { }; withFrameNanos { }
            launch { veil.animateTo(1f, tween(260)) }
            // Muelle suave: crece de forma continua y se asienta con un rebote mínimo.
            grow.animateTo(1f, spring(dampingRatio = 0.78f, stiffness = 210f))
        }
    }
    Box(
        Modifier.fillMaxSize()
            .drawBehind { drawRect(Color.Black.copy(alpha = (if (glass) 0.38f else 0.62f) * veil.value)) }
            .tap { onDismiss() }
    ) {
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                val t = grow.value
                scaleX = t.coerceAtLeast(0.02f); scaleY = t.coerceAtLeast(0.02f)
                alpha = (t * 2.2f).coerceIn(0f, 1f)
                transformOrigin = origin
            },
            content = content,
        )
    }
}

/** Esfera de agua: el tirador de deslizadores e interruptores. [stretch] la alarga en horizontal al moverse. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.waterSphere(center: Offset, radius: Float, color: Color, stretch: Float = 1f) {
    val w = radius * stretch; val h = radius / kotlin.math.sqrt(stretch)
    drawOval(color, Offset(center.x - w, center.y - h), Size(w * 2, h * 2))
    // Brillo arriba a la izquierda y un reflejo tenue abajo: lo que la hace parecer una gota.
    drawOval(Color.White.copy(alpha = 0.60f), Offset(center.x - w * 0.52f, center.y - h * 0.58f), Size(w * 0.46f, h * 0.36f))
    drawOval(Color.White.copy(alpha = 0.16f), Offset(center.x - w * 0.1f, center.y + h * 0.28f), Size(w * 0.7f, h * 0.38f))
}

/**
 * Deslizador: una línea que se llena de color hasta una esfera de agua. En reposo la línea es recta;
 * solo mientras la esfera se mueve se levanta bajo ella, y la esfera llega con un rebote leve.
 */
@Composable
fun DropSlider(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    val target = fraction.coerceIn(0f, 1f)
    val at by animateFloatAsState(target, spring(dampingRatio = 0.8f, stiffness = 170f), label = "drop")
    // La línea sube al primer cambio y se queda arriba mientras sigan llegando cambios; solo baja
    // cuando el control lleva un momento quieto. Así la esfera planea en vez de dar un salto por paso.
    val lift = remember { Animatable(0f) }
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(target) {
        if (!started) { started = true; return@LaunchedEffect }
        lift.animateTo(1f, tween(140))
        kotlinx.coroutines.delay(520)
        lift.animateTo(0f, tween(420))
    }
    Canvas(modifier.height(24.dp)) {
        val r = 8.5.dp.toPx()
        val left = r; val right = size.width - r
        val base = size.height * 0.56f
        val x = left + (right - left) * at.coerceIn(-0.04f, 1.04f)
        val rise = 5.dp.toPx() * lift.value; val spread = 30.dp.toPx()
        fun y(px: Float): Float { val d = (px - x) / spread; return base - rise * kotlin.math.exp(-d * d) }
        val filled = Path(); val rest = Path()
        val steps = if (rise < 0.2f) 2 else 56
        for (i in 0..steps) {
            val px = left + (right - left) * i / steps
            if (px <= x || i == 0) { if (filled.isEmpty) filled.moveTo(px, y(px)) else filled.lineTo(px, y(px)) }
            if (px >= x) { if (rest.isEmpty) { rest.moveTo(x, y(x)); rest.lineTo(px, y(px)) } else rest.lineTo(px, y(px)) }
        }
        if (!filled.isEmpty) filled.lineTo(x, y(x))
        drawPath(rest, P.Ink.copy(alpha = 0.22f), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        drawPath(filled, color, style = Stroke(3.6.dp.toPx(), cap = StrokeCap.Round))
        // Se alarga un poco en la dirección del movimiento y recupera la forma al llegar.
        waterSphere(Offset(x, y(x)), r, color, 1f + (kotlin.math.abs(at - target) * 5f).coerceAtMost(0.22f))
    }
}

/** Interruptor: una cápsula redonda con una esfera de agua que cruza de lado a lado con rebote. */
@Composable
fun DropSwitch(on: Boolean) {
    val accent = LocalAccent.current
    val at by animateFloatAsState(if (on) 1f else 0f, spring(dampingRatio = 0.5f, stiffness = 320f), label = "switch")
    Canvas(Modifier.size(54.dp, 30.dp)) {
        val r = size.height / 2
        val t = at.coerceIn(0f, 1f)
        drawRoundRect(lerp(P.Ink.copy(alpha = 0.16f), accent.copy(alpha = 0.45f), t), size = size, cornerRadius = CornerRadius(r))
        val x = r + (size.width - 2 * r) * at.coerceIn(-0.08f, 1.08f)
        val travel = (1f - kotlin.math.abs(t - 0.5f) * 2f).coerceIn(0f, 1f)
        waterSphere(Offset(x, r), r - 4.dp.toPx(), lerp(P.Dim, accent, t), 1f + 0.3f * travel)
    }
}

/** Tuerca en dibujo de línea: un hexágono con su agujero. Mismo grosor de trazo que el resto de iconos. */
@Composable
fun GearGlyph(color: Color, modifier: Modifier = Modifier) = Canvas(modifier) {
    val c = Offset(size.width / 2, size.height / 2)
    val r = size.minDimension / 2 * 0.86f
    val w = size.minDimension * 0.07f
    val hex = Path().apply {
        for (i in 0 until 6) {
            val a = i * 60.0 * kotlin.math.PI / 180
            val p = Offset(c.x + r * kotlin.math.cos(a).toFloat(), c.y + r * kotlin.math.sin(a).toFloat())
            if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
        }
        close()
    }
    drawPath(hex, color, style = Stroke(w, join = StrokeJoin.Round))
    drawCircle(color, r * 0.38f, c, style = Stroke(w))
}

/** Sacudida corta en horizontal cada vez que [trigger] cambia: "eso ya está elegido". */
fun Modifier.shake(trigger: Int): Modifier = composed {
    val x = remember { Animatable(0f) }
    LaunchedEffect(trigger) {
        if (trigger > 0) for (dx in listOf(-8f, 7f, -5f, 3f, 0f)) x.animateTo(dx, tween(42))
    }
    graphicsLayer { translationX = x.value * density }
}

/** Mando en dibujo de línea (para el estilo de botones). */
@Composable
fun PadGlyph(color: Color, modifier: Modifier = Modifier) = Canvas(modifier) {
    val w = size.minDimension * 0.07f
    val line = Stroke(w, cap = StrokeCap.Round)
    drawRoundRect(color, Offset(w, size.height * 0.26f), Size(size.width - 2 * w, size.height * 0.48f), CornerRadius(size.height * 0.24f), line)
    val y = size.height * 0.5f; val d = size.height * 0.085f
    drawLine(color, Offset(size.width * 0.27f - d, y), Offset(size.width * 0.27f + d, y), w, StrokeCap.Round)
    drawLine(color, Offset(size.width * 0.27f, y - d), Offset(size.width * 0.27f, y + d), w, StrokeCap.Round)
    drawCircle(color, w * 0.85f, Offset(size.width * 0.68f, y + d * 0.7f))
    drawCircle(color, w * 0.85f, Offset(size.width * 0.78f, y - d * 0.7f))
}

/** Gatillo en dibujo de línea: la curva del botón y, debajo, su recorrido. */
@Composable
fun TriggerGlyph(color: Color, modifier: Modifier = Modifier) = Canvas(modifier) {
    val w = size.minDimension * 0.07f
    val path = Path().apply {
        moveTo(size.width * 0.2f, size.height * 0.62f)
        cubicTo(size.width * 0.2f, size.height * 0.2f, size.width * 0.5f, size.height * 0.12f, size.width * 0.8f, size.height * 0.2f)
        lineTo(size.width * 0.8f, size.height * 0.62f)
        close()
    }
    drawPath(path, color, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawLine(color, Offset(size.width * 0.2f, size.height * 0.82f), Offset(size.width * 0.8f, size.height * 0.82f), w, StrokeCap.Round)
}

/** Luz en dibujo de línea: un punto con rayos. */
@Composable
fun LightGlyph(color: Color, modifier: Modifier = Modifier) = Canvas(modifier) {
    val c = Offset(size.width / 2, size.height / 2)
    val r = size.minDimension / 2
    val w = r * 0.14f
    drawCircle(color, r * 0.34f, c, style = Stroke(w))
    for (i in 0 until 8) {
        val a = i * 45.0 * kotlin.math.PI / 180
        val dir = Offset(kotlin.math.cos(a).toFloat(), kotlin.math.sin(a).toFloat())
        drawLine(color, c + dir * (r * 0.62f), c + dir * (r * 0.9f), w, StrokeCap.Round)
    }
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
        modifier.fillMaxWidth().height(height * LocalTextScale.current.coerceAtLeast(1f))
            .background(if (selected) P.Line else Color.Transparent, chamfer(12.dp))
            .brackets(selected, accent, gap = 2.dp, stroke = 2.5.dp, radius = 16.8.dp)
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
