package dev.palanca.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.palanca.launcher.Env
import dev.palanca.launcher.input.Btn
import dev.palanca.launcher.input.PadEvent
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

private fun hsv(h: Float, s: Float, v: Float) = Color(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)))

/** Saturación y brillo de los siete tonos rápidos: de pastel claro a oscuro profundo. */
private val TONES = listOf(0.30f to 1f, 0.50f to 1f, 0.72f to 1f, 0.88f to 0.95f, 0.90f to 0.76f, 0.88f to 0.58f, 0.85f to 0.42f)

/**
 * Rueda de color del acento. El color se aplica en vivo a toda la interfaz mientras se elige;
 * B lo deja como estaba.
 */
class ColorPickerScreen : Screen {
    private val original = Env.cfg.value.look.accent
    private var hue by mutableFloatStateOf(0f)
    private var sat by mutableFloatStateOf(1f)
    private var value by mutableFloatStateOf(1f)
    private var row by mutableIntStateOf(0) // 0 tono · 1 saturación · 2 brillo · 3 tonos rápidos
    private var tone by mutableIntStateOf(3)

    init {
        val out = FloatArray(3)
        android.graphics.Color.colorToHSV(original.toInt(), out)
        hue = out[0]; sat = out[1]; value = out[2]
    }

    private fun apply() {
        val argb = android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value)).toLong() and 0xFFFFFFFFL
        Env.cfg.update { it.look { copy(accent = argb) } }
    }

    private fun step(dir: Int) {
        when (row) {
            0 -> hue = (hue + dir * 5f + 360f) % 360f
            1 -> sat = (sat + dir * 0.05f).coerceIn(0f, 1f)
            // Por debajo de 0.3 el acento no se distingue del fondo negro.
            2 -> value = (value + dir * 0.05f).coerceIn(0.3f, 1f)
            else -> { tone = (tone + dir).coerceIn(0, TONES.lastIndex); sat = TONES[tone].first; value = TONES[tone].second }
        }
        apply()
    }

    override fun onStick(x: Float, y: Float) {
        if (hypot(x, y) < 0.65f) return
        val angle = ((Math.toDegrees(atan2(y, x).toDouble()).toFloat() + 360f) % 360f)
        // De cinco en cinco grados: el pulso del stick no hace bailar el color.
        val snapped = ((angle / 5f).roundToInt() * 5f) % 360f
        if (snapped != hue) { hue = snapped; apply() }
    }

    override fun onPad(e: PadEvent): Boolean {
        if (e.long) return true
        when (e.btn) {
            Btn.UP -> row = (row + 3) % 4
            Btn.DOWN -> row = (row + 1) % 4
            Btn.LEFT -> step(-1)
            Btn.RIGHT -> step(+1)
            Btn.L1 -> { hue = (hue + 330f) % 360f; apply() }
            Btn.R1 -> { hue = (hue + 30f) % 360f; apply() }
            Btn.X -> { hue = 18f; sat = 0.83f; value = 1f; apply() } // el naranja de fábrica
            Btn.A, Btn.START -> Env.nav.pop()
            Btn.B -> { Env.cfg.update { it.look { copy(accent = original) } }; Env.nav.pop() }
            else -> Unit
        }
        return true
    }

    @Composable
    override fun Content() {
        val color = hsv(hue, sat, value)
        val hex = "#%06X".format(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value)) and 0xFFFFFF)
        Frame(
            "TEMA", "Color de acento", "Gira el stick derecho para elegir el tono",
            listOf("A" to "Aceptar", "◀ ▶" to "Ajustar", "L1 R1" to "Saltar tono", "X" to "Naranja original", "B" to "Cancelar"),
        ) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(430.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        val ring = 46.dp.toPx()
                        val radius = size.minDimension / 2 - ring / 2 - 10.dp.toPx()
                        // El anillo muestra todos los tonos con la saturación y el brillo actuales.
                        drawCircle(Brush.sweepGradient(List(13) { i -> hsv(i * 30f % 360f, sat, value) }), radius, style = Stroke(ring))
                        val a = Math.toRadians(hue.toDouble())
                        val marker = Offset(center.x + cos(a).toFloat() * radius, center.y + sin(a).toFloat() * radius)
                        drawCircle(P.Bg, ring * 0.6f, marker)
                        drawCircle(color, ring * 0.42f, marker)
                        drawCircle(P.Ink, ring * 0.6f, marker, style = Stroke(3.dp.toPx()))
                        drawCircle(color, radius - ring / 2 - 26.dp.toPx())
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        T(hex, 30, P.OnAccent, FontWeight.Bold, mono = true)
                        T("${hue.roundToInt()}°", 15, P.OnAccent, mono = true)
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                    Bar("Tono", "${hue.roundToInt()}°", hue / 360f, row == 0, color)
                    Bar("Saturación", "${(sat * 100).roundToInt()} %", sat, row == 1, color)
                    Bar("Brillo", "${(value * 100).roundToInt()} %", value, row == 2, color)
                    Column(
                        Modifier.fillMaxWidth().background(if (row == 3) P.Panel else Color.Transparent, chamfer(12.dp))
                            .brackets(row == 3, color, gap = 2.dp, len = 10.dp, stroke = 2.5.dp).padding(horizontal = 18.dp, vertical = 14.dp),
                    ) {
                        T("Claros y oscuros de este tono", 16, if (row == 3) P.Ink else P.Dim, if (row == 3) FontWeight.Medium else FontWeight.Normal)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            TONES.forEachIndexed { i, (s, v) ->
                                val picked = row == 3 && i == tone
                                Box(
                                    Modifier.weight(1f).height(if (picked) 46.dp else 36.dp)
                                        .then(if (picked) Modifier.border(2.5.dp, P.Ink, chamfer(10.dp)).padding(5.dp) else Modifier)
                                        .background(hsv(hue, s, v), chamfer(8.dp))
                                        .tap { row = 3; tone = i; sat = s; value = v; apply() }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(22.dp))
                    // Muestra de cómo queda en la interfaz real.
                    Row(Modifier.padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Label("Así se ve")
                        Glyph("A", primary = true)
                        Box(Modifier.height(34.dp).background(color, chamfer(10.dp)).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                            T("EMULADORES", 12, P.OnAccent, FontWeight.Bold, mono = true, spacing = 1f)
                        }
                        Segments(0.7f, color, Modifier.width(150.dp), count = 10)
                        T("21:47", 22, color, FontWeight.Bold)
                    }
                }
            }
        }
    }

    @Composable
    private fun Bar(title: String, text: String, fraction: Float, selected: Boolean, color: Color) {
        Column(
            Modifier.fillMaxWidth().background(if (selected) P.Panel else Color.Transparent, chamfer(12.dp))
                .brackets(selected, color, gap = 2.dp, len = 10.dp, stroke = 2.5.dp).padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(title, 16, if (selected) P.Ink else P.Dim, if (selected) FontWeight.Medium else FontWeight.Normal, modifier = Modifier.weight(1f))
                if (selected) T("‹ ", 16, color, FontWeight.Bold, mono = true)
                T(text, 14, if (selected) color else P.Dim, mono = true)
                if (selected) T(" ›", 16, color, FontWeight.Bold, mono = true)
            }
            Spacer(Modifier.height(10.dp))
            Segments(fraction, if (selected) color else P.Ink, Modifier.fillMaxWidth(), count = 24)
        }
        Spacer(Modifier.height(6.dp))
    }
}
