package dev.palanca.launcher.ui

import android.provider.Settings
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
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import dev.palanca.launcher.system.DeviceModes
import dev.palanca.launcher.system.Privileged
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.alpha
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import dev.palanca.launcher.Env
import dev.palanca.launcher.data.AppEntry
import dev.palanca.launcher.data.fold
import dev.palanca.launcher.data.labelIn
import dev.palanca.launcher.input.Btn
import dev.palanca.launcher.input.PadEvent
import dev.palanca.launcher.system.Actions

private val KEY_ROWS = listOf(
    "1234567890".map { it.toString() },
    "QWERTYUIOP".map { it.toString() },
    "ASDFGHJKLÑ".map { it.toString() },
    "ZXCVBNM".map { it.toString() } + listOf("ESPACIO", "BORRAR", "OK"),
)

/** Teclado en pantalla para mando. Sirve para buscar apps y para escribir etiquetas. */
class KeyboardScreen private constructor(
    private val title: String, initial: String, private val search: Boolean, private val onDone: (String) -> Unit,
) : Screen {
    companion object {
        fun search() = KeyboardScreen("Buscar", "", true) {}
        fun text(title: String, initial: String, onDone: (String) -> Unit) = KeyboardScreen(title, initial, false, onDone)
    }

    private var text by mutableStateOf(initial)
    private var row by mutableIntStateOf(1)
    private var col by mutableIntStateOf(0)
    private var zone by mutableIntStateOf(0) // 0 = teclas, 1 = resultados
    private var result by mutableIntStateOf(0)

    private fun results(): List<AppEntry> {
        if (!search || text.isBlank()) return emptyList()
        val cfg = Env.cfg.value
        val q = fold(text.trim())
        val all = Env.apps.apps.value.filter { it.key !in cfg.hidden }
        // Primero las que empiezan por el texto, luego las que lo contienen.
        return (all.filter { fold(it.labelIn(cfg)).startsWith(q) } + all.filter { fold(it.labelIn(cfg)).contains(q) }).distinct().take(8)
    }

    private fun type(key: String) {
        when (key) {
            "ESPACIO" -> if (text.isNotEmpty() && !text.endsWith(" ")) text += " "
            "BORRAR" -> text = text.dropLast(1)
            "OK" -> done()
            else -> if (text.length < 32) text += if (text.isEmpty() || text.endsWith(" ")) key else key.lowercase()
        }
        result = 0
    }

    private fun done() {
        if (search) results().firstOrNull()?.let(::open)
        else { Env.nav.pop(); onDone(text.trim()) }
    }

    private fun open(app: AppEntry) { Env.nav.pop(); Actions.launch(app) }

    override fun onPad(e: PadEvent): Boolean {
        if (e.long) return true
        val found = results()
        if (zone == 1 && found.isEmpty()) zone = 0
        when (e.btn) {
            Btn.B -> Env.nav.pop()
            Btn.X -> type("BORRAR")
            Btn.Y -> type("ESPACIO")
            Btn.START -> done()
            Btn.A -> if (zone == 1) found.getOrNull(result)?.let(::open) else type(KEY_ROWS[row][col])
            Btn.LEFT -> if (zone == 1) result = (result - 1).coerceAtLeast(0) else col = (col + 9) % 10
            Btn.RIGHT -> if (zone == 1) result = (result + 1).coerceAtMost(found.lastIndex) else col = (col + 1) % 10
            Btn.UP -> if (zone == 0) { if (row > 0) row-- else if (found.isNotEmpty()) { zone = 1; result = result.coerceIn(0, found.lastIndex) } }
            Btn.DOWN -> if (zone == 1) zone = 0 else row = (row + 1).coerceAtMost(KEY_ROWS.lastIndex)
            else -> Unit
        }
        return true
    }

    @Composable
    override fun Content() {
        val accent = LocalAccent.current
        Env.apps.apps.collectAsState().value
        val found = results()
        Frame(
            if (search) "Y" else "ABC", title, if (search) "Escribe para filtrar tus apps" else "Escribe el nuevo nombre",
            listOf("A" to "Escribir", "X" to "Borrar", "Y" to "Espacio", "START" to if (search) "Abrir la primera" else "Aceptar", "B" to "Cancelar"),
            if (search) "${found.size} resultados" else null,
        ) {
            Row(Modifier.fillMaxWidth().height(58.dp).border(1.dp, P.Line, chamfer(14.dp)).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                T(text, 26, weight = FontWeight.Medium)
                Box(Modifier.padding(start = 3.dp).width(12.dp).height(28.dp).background(accent))
                if (text.isEmpty()) { Spacer(Modifier.width(12.dp)); T(if (search) "Nombre de la app" else "Etiqueta", 20, P.Dim) }
            }
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                found.forEachIndexed { i, app -> AppTile(app, 72.dp, zone == 1 && i == result, onTap = { open(app) }) }
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                KEY_ROWS.forEachIndexed { r, keys ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        keys.forEachIndexed { c, key ->
                            val on = zone == 0 && r == row && c == col
                            Box(
                                Modifier.width(104.dp).height(58.dp)
                                    .background(if (on) accent else P.Panel, chamfer(10.dp))
                                    .tap { zone = 0; row = r; col = c; type(key) },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (key.length == 1) T(key, 22, if (on) P.OnAccent else P.Ink, FontWeight.Medium)
                                else T(key, 12, if (on) P.OnAccent else P.Dim, FontWeight.Bold, mono = true, spacing = 1f)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Panel rápido en dos ventanas: a la izquierda, sistema (brillo, volumen, accesos); a la derecha,
 * rendimiento de la consola (modo, ventilador, límite de CPU o ClusterTune).
 * La ventana de rendimiento solo aparece en consolas que exponen esos ajustes.
 */
class QuickPanel : Screen, Closable {
    override val overlay get() = true
    override var closing by mutableStateOf(false)
    private var side by mutableIntStateOf(0)
    private var sel by mutableIntStateOf(0)
    private var brightness by mutableIntStateOf(Actions.brightness())
    private var volume by mutableIntStateOf(Actions.volume())
    private var performance by mutableIntStateOf(DeviceModes.performance())
    private var fan by mutableIntStateOf(DeviceModes.fan())
    private var fanSpeed by mutableIntStateOf(DeviceModes.fanSpeed())
    private val clusterTune = Env.apps.apps.value.firstOrNull { it.pkg == "com.aure.clustertune" }
    private val jobs = HashMap<String, Job>()

    private var controller by mutableIntStateOf(DeviceModes.controller())
    private val system: List<String> = buildList {
        add("brillo"); add("volumen"); add("wifi"); add("bluetooth")
        if (controller >= 0) add("mando")
        if (DeviceModes.lightsSupported()) add("luces")
        add("ajustes")
    }
    private fun console(): List<String> = buildList {
        if (performance >= 0) add("rendimiento")
        if (fan >= 0) add("ventilador")
        // La velocidad está siempre en la lista (solo actúa en Personalizado): así la ventana no cambia de tamaño.
        if (fan >= 0) add("velocidad")
        if (clusterTune != null) add("clustertune")
    }
    private fun rows() = if (side == 0) system else console()

    /**
     * Cambia un ajuste de la consola. Espera a que se deje de pulsar (solo se envía el último valor)
     * y luego comprueba hasta 4 segundos que la consola lo aplicó: el comando tarda en arrancar.
     */
    private fun change(key: String, wanted: Int, store: (Int) -> Unit, apply: (Int) -> Boolean, read: () -> Int) {
        store(wanted)
        jobs[key]?.cancel()
        jobs[key] = Env.scope.launch {
            delay(300)
            if (!withContext(Dispatchers.IO) { apply(wanted) }) {
                store(read()); Actions.toast("Esta consola no permite cambiar ese ajuste"); return@launch
            }
            repeat(16) { delay(250); if (read() == wanted) return@launch }
            store(read()); Actions.toast("La consola no aceptó el cambio")
        }
    }

    private fun adjust(delta: Int) {
        val dir = if (delta > 0) 1 else -1
        fun <T> next(options: List<Pair<Int, T>>, current: Int): Int {
            val i = options.indexOfFirst { it.first == current }
            return options[(if (i < 0) 0 else (i + dir).coerceIn(0, options.lastIndex))].first
        }
        when (rows().getOrNull(sel)) {
            // El brillo va de dos en dos (el stick derecho, de diez en diez): cambio gradual.
            "brillo" -> if (Actions.canWriteSettings()) { brightness = (brightness + (if (kotlin.math.abs(delta) > 5) dir * 10 else dir * 2)).coerceIn(1, 100); Actions.setBrightness(brightness) }
            "mando" -> change("pad", if (controller == 1) 0 else 1, { controller = it }, DeviceModes::setController, DeviceModes::controller)
            "volumen" -> { Actions.volumeStep(delta); volume = Actions.volume() }
            "rendimiento" -> change("perf", next(DeviceModes.PERFORMANCE, performance), { performance = it }, DeviceModes::setPerformance, DeviceModes::performance)
            "ventilador" -> change("fan", next(DeviceModes.FAN, fan), { fan = it }, DeviceModes::setFan, DeviceModes::fan)
            "velocidad" -> if (fan == 6) change("speed", (fanSpeed + dir * 5).coerceIn(0, 100), { fanSpeed = it }, DeviceModes::setFanSpeed, DeviceModes::fanSpeed)
        }
    }

    private fun activate(row: String) {
        when (row) {
            "brillo" -> if (!Actions.canWriteSettings()) Actions.askWriteSettings()
            "rendimiento", "ventilador", "velocidad", "mando" -> adjust(+5)
            "luces" -> Env.nav.push(LightsScreen())
            "wifi" -> if (!Actions.open(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)) Actions.open(Settings.ACTION_WIFI_SETTINGS)
            "bluetooth" -> Actions.open(Settings.ACTION_BLUETOOTH_SETTINGS)
            "clustertune" -> { Env.nav.pop(); clusterTune?.let { Actions.launch(it) } }
            "ajustes" -> { Env.nav.pop(); Actions.open(Settings.ACTION_SETTINGS) }
        }
    }

    private fun adjustable(row: String?) = row in setOf("brillo", "volumen", "rendimiento", "ventilador", "velocidad", "mando")
    private fun switchSide(to: Int) { if (to == 0 || console().isNotEmpty()) { side = to; sel = 0 } }

    override fun onPad(e: PadEvent): Boolean {
        if (e.long) return true
        val rows = rows()
        sel = sel.coerceIn(0, (rows.size - 1).coerceAtLeast(0))
        val row = rows.getOrNull(sel)
        when (e.btn) {
            Btn.UP, Btn.RS_UP -> sel = (sel + rows.size - 1) % rows.size
            Btn.DOWN, Btn.RS_DOWN -> sel = (sel + 1) % rows.size
            // START guarda esta ventana y abre Custom al momento.
            Btn.START -> { Env.nav.pop(); Env.nav.push(SettingsScreen()) }
            Btn.LEFT -> if (adjustable(row)) adjust(-5) else switchSide(0)
            Btn.RIGHT -> if (adjustable(row)) adjust(+5) else switchSide(1)
            Btn.L1 -> switchSide(0)
            Btn.R1 -> switchSide(1)
            Btn.RS_LEFT -> adjust(-15)
            Btn.RS_RIGHT -> adjust(+15)
            Btn.A -> row?.let(::activate)
            Btn.B, Btn.SELECT, Btn.L2 -> Env.nav.pop()
            else -> Unit
        }
        return true
    }

    @Composable
    override fun Content() {
        val canWrite = remember(sel) { Actions.canWriteSettings() }
        val console = console()
        // Nace de la esquina superior izquierda, del lado del botón SELECT.
        Popup(TransformOrigin(0f, 0f), closing, { Env.nav.pop() }) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.Top) {
                    Window(Modifier.width(if (console.isEmpty()) 600.dp else 540.dp).alpha(if (side == 0) 1f else 0.6f)) {
                        T("CNTRL", 24, weight = FontWeight.Medium, modifier = Modifier.padding(start = 8.dp, bottom = 12.dp))
                        system.forEachIndexed { i, row ->
                            val on = side == 0 && i == sel
                            val tap = Modifier.tap { side = 0; sel = i; activate(row) }
                            when (row) {
                                "brillo" -> Slider("Brillo", if (canWrite) brightness else -1, on, tap)
                                "volumen" -> Slider("Volumen", volume, on, tap)
                                "wifi" -> ListRow("Wi‑Fi e internet", "", on, tap)
                                "bluetooth" -> ListRow("Bluetooth", "", on, tap)
                                "mando" -> ListRow("Estilo de mando", DeviceModes.name(DeviceModes.CONTROLLER, controller), on, tap, arrows = true)
                                "luces" -> ListRow("Luces de los sticks", "", on, tap)
                                // Ajustes de Android: solo un engranaje, centrado.
                                else -> Box(
                                    tap.fillMaxWidth().height(56.dp).background(if (on) P.Line else Color.Transparent, chamfer(12.dp))
                                        .brackets(on, LocalAccent.current, gap = 2.dp, stroke = 2.5.dp, radius = 16.8.dp),
                                    contentAlignment = Alignment.Center,
                                ) { GearGlyph(if (on) P.Ink else P.Dim, Modifier.size(30.dp)) }
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                    if (console.isNotEmpty()) Window(Modifier.width(540.dp).alpha(if (side == 1) 1f else 0.6f)) {
                        T("BOOST", 24, weight = FontWeight.Medium, modifier = Modifier.padding(start = 8.dp, bottom = 12.dp))
                        console.forEachIndexed { i, row ->
                            val on = side == 1 && i == sel
                            val tap = Modifier.tap { side = 1; sel = i; activate(row) }
                            when (row) {
                                "rendimiento" -> ListRow("Modo", DeviceModes.name(DeviceModes.PERFORMANCE, performance), on, tap, arrows = true)
                                "ventilador" -> ListRow("Ventilador", DeviceModes.name(DeviceModes.FAN, fan), on, tap, arrows = true)
                                "velocidad" -> Box(Modifier.alpha(if (fan == 6) 1f else 0.4f)) { Slider(if (fan == 6) "Velocidad del ventilador" else "Velocidad (solo en Personalizado)", fanSpeed, on, tap) }
                                else -> ListRow("ClusterTune · límites de CPU y GPU", "", on, tap)
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                HintBar(
                    listOf("A" to "Elegir", "◀ ▶" to "Ajustar") + (if (console.isEmpty()) emptyList() else listOf("L1 R1" to "Cambiar de ventana")) + listOf("B" to "Cerrar"),
                    Modifier.width(if (console.isEmpty()) 600.dp else 1102.dp).padding(horizontal = 14.dp),
                )
            }
        }
    }

    @Composable
    private fun Slider(title: String, value: Int, selected: Boolean, modifier: Modifier) {
        val accent = LocalAccent.current
        Column(
            modifier.fillMaxWidth().background(if (selected) P.Line else Color.Transparent, chamfer(12.dp))
                .brackets(selected, accent, gap = 2.dp, stroke = 2.5.dp, radius = 16.8.dp).padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(title, 16, if (selected) P.Ink else P.Dim, if (selected) FontWeight.Medium else FontWeight.Normal, modifier = Modifier.weight(1f))
                T(if (value < 0) "pulsa A para dar permiso" else "$value %", 14, if (selected) accent else P.Dim, mono = true)
            }
            Spacer(Modifier.height(4.dp))
            DropSlider(if (value < 0) 0f else value / 100f, if (selected) accent else P.Dim, Modifier.fillMaxWidth())
        }
    }
}

/** Luces de los sticks: encendido, color (rueda con el stick derecho) y brillo. */
class LightsScreen : Screen, Closable {
    override val overlay get() = true
    override var closing by mutableStateOf(false)
    private var on by mutableStateOf(DeviceModes.lightsOn())
    private var hue by mutableIntStateOf(0)
    private var brightness by mutableIntStateOf(DeviceModes.lightsBrightness())
    private var row by mutableIntStateOf(0)
    private var job: Job? = null

    init {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(DeviceModes.lightsColor(), hsv)
        hue = hsv[0].toInt()
    }

    private fun color() = android.graphics.Color.HSVToColor(floatArrayOf(hue.toFloat(), 1f, 1f))

    /** Envía el estado a la consola cuando se deja de mover (un solo comando, no uno por paso). */
    private fun apply() {
        job?.cancel()
        job = Env.scope.launch {
            delay(280)
            if (!withContext(Dispatchers.IO) { DeviceModes.setLights(on, color(), brightness) }) Actions.toast("Esta consola no permite cambiar las luces")
        }
    }

    override fun onStick(x: Float, y: Float) {
        if (kotlin.math.hypot(x, y) < 0.65f) return
        val angle = ((Math.toDegrees(kotlin.math.atan2(y, x).toDouble()) + 360) % 360).toInt() / 6 * 6
        if (angle != hue) { hue = angle; on = true; apply() }
    }

    override fun onPad(e: PadEvent): Boolean {
        if (e.long) return true
        val dir = if (e.btn == Btn.LEFT) -1 else 1
        when (e.btn) {
            Btn.UP -> row = (row + 2) % 3
            Btn.DOWN -> row = (row + 1) % 3
            Btn.LEFT, Btn.RIGHT -> {
                when (row) {
                    0 -> on = !on
                    1 -> hue = (hue + dir * 6 + 360) % 360
                    else -> brightness = (brightness + dir * 5).coerceIn(5, 100)
                }
                apply()
            }
            Btn.A -> if (row == 0) { on = !on; apply() }
            Btn.X -> {
                // Mismo tono que el color de acento de la interfaz.
                val hsv = FloatArray(3); android.graphics.Color.colorToHSV(Env.cfg.value.look.accent.toInt(), hsv)
                hue = hsv[0].toInt(); on = true; apply()
            }
            Btn.B -> Env.nav.pop()
            else -> Unit
        }
        return true
    }

    @Composable
    override fun Content() {
        val accent = LocalAccent.current
        val tint = Color(color())
        Popup(TransformOrigin(0.3f, 0.6f), closing, { Env.nav.pop() }) {
            Window(Modifier.align(Alignment.Center).width(760.dp)) {
                T("Luces de los sticks", 24, weight = FontWeight.Medium, modifier = Modifier.padding(start = 8.dp, bottom = 12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.size(230.dp).padding(8.dp)) {
                        val ring = 30.dp.toPx(); val radius = size.minDimension / 2 - ring / 2
                        drawCircle(Brush.sweepGradient(List(13) { i -> Color(android.graphics.Color.HSVToColor(floatArrayOf(i * 30f % 360f, 1f, 1f))) }), radius, style = Stroke(ring))
                        val a = Math.toRadians(hue.toDouble())
                        val marker = Offset(center.x + kotlin.math.cos(a).toFloat() * radius, center.y + kotlin.math.sin(a).toFloat() * radius)
                        drawCircle(Color.Black, ring * 0.62f, marker); drawCircle(tint, ring * 0.44f, marker)
                        drawCircle(P.Ink, ring * 0.62f, marker, style = Stroke(3.dp.toPx()))
                        drawCircle(if (on) tint else P.Line, radius - ring / 2 - 16.dp.toPx())
                    }
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ListRow("Encendidas", "", row == 0, Modifier.tap { row = 0; on = !on; apply() }) { DropSwitch(on) }
                        ListRow("Color", "", row == 1, Modifier.tap { row = 1 }) { DropSlider(hue / 360f, tint, Modifier.width(230.dp)) }
                        ListRow("Brillo", "", row == 2, Modifier.tap { row = 2 }) {
                            T("$brightness %", 14, if (row == 2) accent else P.Dim, mono = true)
                            Spacer(Modifier.width(14.dp))
                            DropSlider(brightness / 100f, if (row == 2) accent else P.Dim, Modifier.width(170.dp))
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                HintBar(listOf("◀ ▶" to "Ajustar", "RS" to "Girar la rueda", "X" to "Como el acento", "B" to "Cerrar"))
            }
        }
    }
}

/** Mueve las piezas de Inicio (reloj, botones y tarjetas) con el stick; se dibuja encima de Inicio sin taparlo. */
class HomeEditScreen : Screen {
    override val overlay get() = true
    private val pieces = listOf("reloj" to "Reloj", "botones" to "Botones", "tarjetas" to "Tarjetas")
    private var piece by mutableIntStateOf(0)

    init { editing = pieces[0].first }

    private fun nudge(dx: Int, dy: Int) = Env.cfg.update { c ->
        val key = pieces[piece].first
        val (x, y) = pieceOffset(c.look.offsets, key)
        c.look { copy(offsets = offsets + (key to "${(x + dx).coerceIn(-500, 500)},${(y + dy).coerceIn(-300, 300)}")) }
    }

    private fun close() { editing = null; Env.nav.stack.remove(this) }

    override fun onPad(e: PadEvent): Boolean {
        if (e.long) return true
        when (e.btn) {
            Btn.LEFT -> nudge(-6, 0)
            Btn.RIGHT -> nudge(+6, 0)
            Btn.UP -> nudge(0, -6)
            Btn.DOWN -> nudge(0, +6)
            Btn.L1 -> { piece = (piece + pieces.size - 1) % pieces.size; editing = pieces[piece].first }
            Btn.R1 -> { piece = (piece + 1) % pieces.size; editing = pieces[piece].first }
            Btn.X -> Env.cfg.update { c -> c.look { copy(offsets = offsets - pieces[piece].first) } }
            Btn.A, Btn.B, Btn.START -> close()
            else -> Unit
        }
        return true
    }

    @Composable
    override fun Content() {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Window(Modifier.width(900.dp)) {
                    T("Moviendo: ${pieces[piece].second}", 20, weight = FontWeight.Medium, modifier = Modifier.padding(start = 8.dp, bottom = 6.dp))
                    HintBar(listOf("A" to "Listo", "L1 R1" to "Otra pieza", "X" to "A su sitio"), left = "Stick o cruceta: mover")
                }
            }
        }
    }

    companion object {
        /** Pieza que se está moviendo ahora (Inicio la marca con un anillo), o null. */
        var editing by mutableStateOf<String?>(null)
    }
}

/** Lee el desplazamiento "x,y" guardado para una pieza de Inicio. */
fun pieceOffset(offsets: Map<String, String>, key: String): Pair<Int, Int> {
    val parts = offsets[key]?.split(',')
    return (parts?.getOrNull(0)?.toIntOrNull() ?: 0) to (parts?.getOrNull(1)?.toIntOrNull() ?: 0)
}

/** Ventana que espera a que se pulse un botón cualquiera y devuelve su código de tecla. */
class KeyCaptureScreen(private val title: String, private val onKey: (Int) -> Unit) : Screen, Closable {
    override val overlay get() = true
    override var closing by mutableStateOf(false)

    init {
        Env.keyCapture = { code -> Env.keyCapture = null; Env.nav.stack.remove(this); onKey(code) }
    }

    override fun onPad(e: PadEvent) = true

    @Composable
    override fun Content() {
        Popup(TransformOrigin(0.5f, 0.5f), closing, { Env.keyCapture = null; Env.nav.stack.remove(this@KeyCaptureScreen) }) {
            Window(Modifier.align(Alignment.Center).width(620.dp)) {
                T(title, 24, weight = FontWeight.Medium, modifier = Modifier.padding(8.dp))
                T(
                    "Pulsa ahora el botón que quieras usar. Si es un botón trasero (M1/M2), asígnale antes una tecla en los ajustes de la consola que no sea Home ni Atrás. Toca fuera para cancelar.",
                    15, P.Dim, maxLines = 4, modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 10.dp),
                )
            }
        }
    }
}
