package dev.palanca.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.palanca.launcher.Env
import dev.palanca.launcher.data.Config
import dev.palanca.launcher.data.Look
import dev.palanca.launcher.system.Metric
import dev.palanca.launcher.system.Reading
import dev.palanca.launcher.system.Telemetry

val STAT_SIZES = listOf("auto" to "Automático", "grande" to "Grande", "mediano" to "Mediano", "mini" to "Mini")
val STAT_STYLES = listOf("segmentos" to "Segmentos", "anillo" to "Anillo", "grafica" to "Gráfica", "numero" to "Solo número")

fun statList(cfg: Config): List<Metric> = cfg.look.stats.mapNotNull(Metric::of).distinct()

/** Tamaño efectivo: en automático se encoge según cuántas haya, para que quepan todas. */
fun statSize(look: Look, count: Int): String =
    if (look.statSize != "auto") look.statSize else if (count <= 4) "grande" else if (count <= 6) "mediano" else "mini"

fun statCols(size: String) = when (size) { "grande" -> 2; "mediano" -> 3; else -> 4 }

/** Rejilla de estadísticas de Inicio. [selected] es el índice con foco, o -1. */
@Composable
fun StatsGrid(metrics: List<Metric>, look: Look, selected: Int, firstNumber: Int, onTap: (Int) -> Unit) {
    if (metrics.isEmpty()) return
    LaunchedEffect(metrics) { Telemetry.run(metrics) }
    val readings by Telemetry.readings.collectAsState()
    val history by Telemetry.history.collectAsState()
    val size = statSize(look, metrics.size)
    val cols = statCols(size)
    val gap = if (size == "grande") 14.dp else if (size == "mediano") 10.dp else 8.dp
    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        metrics.chunked(cols).forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                row.forEachIndexed { c, metric ->
                    val i = r * cols + c
                    StatTile(
                        metric, readings[metric], history[metric].orEmpty(), size, look.statStyle, i == selected,
                        firstNumber + i, Modifier.weight(1f).tap { onTap(i) },
                    )
                }
                repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun StatTile(
    metric: Metric, reading: Reading?, history: List<Float>, size: String, style: String,
    selected: Boolean, number: Int, modifier: Modifier,
) {
    val accent = LocalAccent.current
    val r = reading ?: Reading("…")
    val color = if (r.available) accent else P.Line
    val big = size == "grande"; val mini = size == "mini"
    val valueSize = if (big) 28 else if (mini) 16 else 21
    val ring = if (big) 56.dp else if (mini) 24.dp else 34.dp
    Panel(modifier.brackets(selected, accent, gap = 3.dp, radius = if (big) 22.4.dp else 14.dp), cut = if (big) 16.dp else 10.dp) {
        Row(
            Modifier.fillMaxWidth().padding(
                horizontal = if (big) 18.dp else if (mini) 9.dp else 12.dp,
                vertical = if (big) 11.dp else if (mini) 6.dp else 9.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                val ringed = style == "anillo"
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label(metric.title, size = if (big) 12 else if (mini) 9 else 10)
                    if (metric == Metric.BAT_TEMP) {
                        // Mini batería: deja claro que esta temperatura es la de la batería.
                        Canvas(Modifier.padding(start = 6.dp).size(if (big) 18.dp else 14.dp, if (big) 10.dp else 8.dp)) {
                            val w = this.size.width * 0.88f
                            drawRoundRect(P.Dim, size = Size(w, this.size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(this.size.height * 0.25f), style = Stroke(1.3.dp.toPx()))
                            drawRect(P.Dim, Offset(w, this.size.height * 0.3f), Size(this.size.width - w, this.size.height * 0.4f))
                            drawRect(P.Dim, Offset(this.size.height * 0.28f, this.size.height * 0.28f), Size(w * 0.5f, this.size.height * 0.44f))
                        }
                    }
                }
                Spacer(Modifier.height(if (big) 8.dp else if (mini) 2.dp else 4.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    T(r.value, valueSize, if (r.available) P.Ink else P.Dim, FontWeight.Medium)
                    if (r.unit.isNotEmpty()) T(" " + r.unit, if (big) 14 else if (mini) 10 else 11, P.Dim, modifier = Modifier.padding(bottom = if (big) 4.dp else 2.dp))
                    if (big && !ringed && r.detail.isNotEmpty()) {
                        Spacer(Modifier.weight(1f))
                        T(r.detail, 12, P.Dim, mono = true, modifier = Modifier.padding(bottom = 5.dp))
                    }
                }
                when (style) {
                    "segmentos" -> {
                        Spacer(Modifier.height(if (big) 10.dp else if (mini) 4.dp else 7.dp))
                        Segments(r.fraction, color, Modifier.fillMaxWidth(), count = if (big) 12 else if (mini) 6 else 9, height = if (big) 10.dp else if (mini) 4.dp else 6.dp)
                    }
                    "grafica" -> {
                        Spacer(Modifier.height(if (big) 6.dp else 3.dp))
                        Sparkline(history, color, Modifier.fillMaxWidth().height(if (big) 22.dp else if (mini) 9.dp else 14.dp))
                    }
                }
            }
            if (style == "anillo") {
                Spacer(Modifier.width(if (big) 12.dp else 6.dp))
                Ring(r.fraction, color, ring)
            }
        }
    }
}

/** Indicador de arco de 270°. */
@Composable
private fun Ring(fraction: Float, color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.minDimension * 0.15f
        val area = Size(this.size.width - w, this.size.height - w)
        val origin = Offset(w / 2, w / 2)
        drawArc(P.Line, 135f, 270f, false, origin, area, style = Stroke(w, cap = StrokeCap.Butt))
        drawArc(color, 135f, 270f * fraction.coerceIn(0f, 1f), false, origin, area, style = Stroke(w, cap = StrokeCap.Butt))
    }
}

/** Gráfica de las últimas lecturas (unos 80 segundos). */
@Composable
private fun Sparkline(values: List<Float>, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        drawLine(P.Line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
        if (values.size < 2) return@Canvas
        val step = size.width / (values.size - 1)
        val points = values.mapIndexed { i, v -> Offset(i * step, size.height * (1f - v.coerceIn(0f, 1f) * 0.92f)) }
        drawPoints(points, PointMode.Polygon, color, 2.dp.toPx(), StrokeCap.Round)
    }
}

// ---- Menús para cambiarlas con el mando ------------------------------------------------

private fun editStats(f: (MutableList<String>) -> Unit) =
    Env.cfg.update { c -> c.look { copy(stats = stats.toMutableList().also(f).distinct()) } }

private fun next(options: List<Pair<String, String>>, current: String) =
    options[(options.indexOfFirst { it.first == current }.coerceAtLeast(0) + 1) % options.size].first

/** Lista de las estadísticas que todavía no están en Inicio. */
fun metricPicker(title: String, onPick: (Metric) -> Unit) = MenuScreen("ESTADÍSTICAS", title) {
    val used = Env.cfg.value.look.stats.toSet()
    Metric.entries.filter { it.name !in used }.map { m -> MenuItem(m.title) { onPick(m) } }
        .ifEmpty { listOf(MenuItem("Ya están todas en Inicio") { }) }
}

fun statMenu(metric: Metric, onMoved: (Int) -> Unit = {}) = MenuScreen("ESTADÍSTICA", metric.title) {
    val look = Env.cfg.value.look
    fun move(delta: Int) {
        var to = -1
        editStats { l ->
            val i = l.indexOf(metric.name); val j = i + delta
            if (i >= 0 && j in l.indices) { l[i] = l[j].also { l[j] = l[i] }; to = j }
        }
        if (to >= 0) onMoved(to)
    }
    listOf(
        MenuItem("Cambiar por…") { Env.nav.push(metricPicker("Cambiar ${metric.title} por") { m -> editStats { l -> l.indexOf(metric.name).takeIf { it >= 0 }?.let { i -> l[i] = m.name } } }) },
        MenuItem("Mover antes", keep = true) { move(-1) },
        MenuItem("Mover después", keep = true) { move(+1) },
        MenuItem("Añadir otra…") { Env.nav.push(metricPicker("Añadir a Inicio") { m -> editStats { it += m.name } }) },
        MenuItem("Tamaño", STAT_SIZES.first { it.first == look.statSize }.second, keep = true) {
            Env.cfg.update { c -> c.look { copy(statSize = next(STAT_SIZES, statSize)) } }
        },
        MenuItem("Estilo", STAT_STYLES.first { it.first == look.statStyle }.second, keep = true) {
            Env.cfg.update { c -> c.look { copy(statStyle = next(STAT_STYLES, statStyle)) } }
        },
        MenuItem("Quitar") { editStats { it -= metric.name } },
    )
}
