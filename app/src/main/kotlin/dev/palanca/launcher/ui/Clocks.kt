package dev.palanca.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.palanca.launcher.data.Look
import java.time.LocalDateTime
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Estilos de reloj adicionales. Todos se dibujan con formas simples o texto: coste mínimo. */
val EXTRA_CLOCK_STYLES = listOf(
    "duo" to "Dúo",
    "bloques" to "Bloques", "contorno" to "Contorno", "apilado" to "Apilado", "palabras" to "En palabras",
    "binario" to "Binario", "analogico" to "Analógico", "terminal" to "Terminal",
)

private val BLOCK_FONT = mapOf(
    '0' to "111 101 101 101 111", '1' to "010 110 010 010 111", '2' to "111 001 111 100 111",
    '3' to "111 001 111 001 111", '4' to "101 101 111 001 001", '5' to "111 100 111 001 111",
    '6' to "111 100 111 101 111", '7' to "111 001 001 001 001", '8' to "111 101 111 101 111",
    '9' to "111 101 111 001 111", ':' to "0 1 0 1 0",
).mapValues { it.value.split(' ') }

private val NUMBERS = listOf(
    "cero", "una", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve", "diez", "once", "doce", "trece",
    "catorce", "quince", "dieciséis", "diecisiete", "dieciocho", "diecinueve", "veinte", "veintiuno", "veintidós",
    "veintitrés", "veinticuatro", "veinticinco", "veintiséis", "veintisiete", "veintiocho", "veintinueve",
)

private fun minuteWords(m: Int): String = when {
    m == 0 -> "en punto"
    m == 15 -> "y cuarto"
    m == 30 -> "y media"
    m == 1 -> "y uno"
    m < 30 -> "y " + NUMBERS[m]
    else -> {
        val tens = listOf("treinta", "cuarenta", "cincuenta")[m / 10 - 3]
        if (m % 10 == 0) "y $tens" else "y $tens y " + (if (m % 10 == 1) "uno" else NUMBERS[m % 10])
    }
}

/** @return true si [look] pide uno de los estilos de este archivo y ya quedó dibujado. */
@Composable
fun ExtraClock(look: Look, height: Dp, time: LocalDateTime, modifier: Modifier): Boolean {
    val accent = LocalAccent.current
    val text = timeText(look, time)
    val h = height.value
    when (look.clockStyle) {
        // Horas en negrita y minutos finos en el color de acento, sin dos puntos.
        "duo" -> Row(modifier, verticalAlignment = Alignment.Bottom) {
            val parts = text.split(':')
            T(parts[0], h.toInt(), weight = FontWeight.Bold, spacing = -h * 0.03f)
            Box(Modifier.width(height * 0.07f))
            T(parts[1], h.toInt(), accent, FontWeight.Light, spacing = -h * 0.03f)
            if (parts.size > 2) T(parts[2], (h * 0.3f).toInt(), P.Dim, modifier = Modifier.padding(start = height * 0.08f, bottom = height * 0.16f))
        }
        "bloques" -> {
            val cell = height / 5
            val cols = text.sumOf { BLOCK_FONT[it]!![0].length + 1 } - 1
            Canvas(modifier.width(cell * cols).height(height)) {
                val c = cell.toPx(); val side = c * 0.88f
                var col = 0
                for (ch in text) {
                    val rows = BLOCK_FONT[ch]!!
                    for (r in 0 until 5) for (k in rows[r].indices) {
                        if (rows[r][k] == '1') drawRect(if (ch == ':') accent else P.Ink, Offset((col + k) * c, r * c), Size(side, side))
                    }
                    col += rows[0].length + 1
                }
            }
        }
        "contorno" -> T(
            text, (h * 1.02f).toInt(), weight = FontWeight.Bold, spacing = h * 0.01f, modifier = modifier,
            outline = with(LocalDensity.current) { (height * 0.022f).toPx() },
        )
        "apilado" -> Column(modifier) {
            val parts = text.split(':')
            T(parts[0], (h * 0.62f).toInt(), weight = FontWeight.Bold, spacing = -h * 0.02f)
            T(parts.drop(1).joinToString(":"), (h * 0.62f).toInt(), accent, FontWeight.Bold, spacing = -h * 0.02f)
        }
        "palabras" -> Column(modifier) {
            val hour = (time.hour % 12).let { if (it == 0) 12 else it }
            T(if (hour == 1) "la una" else "las " + NUMBERS[hour], (h * 0.36f).toInt(), weight = FontWeight.Medium)
            T(minuteWords(time.minute), (h * 0.36f).toInt(), accent, FontWeight.Medium)
        }
        "binario" -> {
            val digits = text.filter { it.isDigit() }
            val pitch = height / 4
            val groupGap = pitch * 0.6f
            Canvas(modifier.width(pitch * digits.length + groupGap * (digits.length / 2 - 1)).height(height)) {
                val p = pitch.toPx()
                digits.forEachIndexed { i, d ->
                    val x = i * p + (i / 2) * groupGap.toPx() + p / 2
                    for (bit in 0 until 4) {
                        val on = (d - '0') shr (3 - bit) and 1 == 1
                        drawCircle(if (on) (if (i / 2 == 1) accent else P.Ink) else P.Line, p * 0.36f, Offset(x, (bit + 0.5f) * p))
                    }
                }
            }
        }
        "analogico" -> Canvas(modifier.size(height)) {
            val r = size.minDimension / 2; val c = Offset(r, r)
            fun at(turn: Float, length: Float) = Offset(c.x + sin(turn * 2 * PI.toFloat()) * r * length, c.y - cos(turn * 2 * PI.toFloat()) * r * length)
            for (i in 0 until 12) {
                val big = i % 3 == 0
                drawLine(if (big) P.Ink else P.Dim, at(i / 12f, if (big) 0.84f else 0.9f), at(i / 12f, 1f), r * (if (big) 0.05f else 0.025f), StrokeCap.Butt)
            }
            val minute = (time.minute + time.second / 60f) / 60f
            drawLine(P.Ink, c, at((time.hour % 12 + time.minute / 60f) / 12f, 0.5f), r * 0.085f, StrokeCap.Butt)
            drawLine(P.Ink, c, at(minute, 0.78f), r * 0.05f, StrokeCap.Butt)
            if (look.clockSeconds) drawLine(accent, c, at(time.second / 60f, 0.86f), r * 0.02f, StrokeCap.Butt)
            drawCircle(accent, r * 0.07f, c)
        }
        "terminal" -> Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            T(">", (h * 0.6f).toInt(), accent, FontWeight.Bold, mono = true)
            T(" $text", (h * 0.6f).toInt(), mono = true)
            Box(Modifier.padding(start = height * 0.05f).width(height * 0.3f).height(height * 0.56f).background(accent))
        }
        else -> return false
    }
    return true
}
