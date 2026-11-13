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

    private val system = listOf("brillo", "volumen", "wifi", "bluetooth", "notificaciones", "ajustes")
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
            "brillo" -> if (Actions.canWriteSettings()) { brightness = (brightness + delta).coerceIn(1, 100); Actions.setBrightness(brightness) }
            "volumen" -> { Actions.volumeStep(delta); volume = Actions.volume() }
            "rendimiento" -> change("perf", next(DeviceModes.PERFORMANCE, performance), { performance = it }, DeviceModes::setPerformance, DeviceModes::performance)
            "ventilador" -> change("fan", next(DeviceModes.FAN, fan), { fan = it }, DeviceModes::setFan, DeviceModes::fan)
            "velocidad" -> if (fan == 6) change("speed", (fanSpeed + dir * 10).coerceIn(0, 100), { fanSpeed = it }, DeviceModes::setFanSpeed, DeviceModes::fanSpeed)
        }
    }

    private fun activate(row: String) {
        when (row) {
            "brillo" -> if (!Actions.canWriteSettings()) Actions.askWriteSettings()
            "rendimiento", "ventilador", "velocidad" -> adjust(+5)
            "wifi" -> if (!Actions.open(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)) Actions.open(Settings.ACTION_WIFI_SETTINGS)
            "bluetooth" -> Actions.open(Settings.ACTION_BLUETOOTH_SETTINGS)
            "notificaciones" -> { Env.nav.pop(); Actions.run(dev.palanca.launcher.system.ActionId.NOTIFICATIONS) }
            "clustertune" -> { Env.nav.pop(); clusterTune?.let { Actions.launch(it) } }
            "ajustes" -> { Env.nav.pop(); Actions.open(Settings.ACTION_SETTINGS) }
        }
    }

    private fun adjustable(row: String?) = row in setOf("brillo", "volumen", "rendimiento", "ventilador", "velocidad")
    private fun switchSide(to: Int) { if (to == 0 || console().isNotEmpty()) { side = to; sel = 0 } }

    override fun onPad(e: PadEvent): Boolean {
        if (e.long) return true
        val rows = rows()
        sel = sel.coerceIn(0, (rows.size - 1).coerceAtLeast(0))
        val row = rows.getOrNull(sel)
        when (e.btn) {
            Btn.UP -> sel = (sel + rows.size - 1) % rows.size
            Btn.DOWN -> sel = (sel + 1) % rows.size
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
                                "wifi" -> ListRow("Wi‑Fi e internet", "abrir", on, tap)
                                "bluetooth" -> ListRow("Bluetooth", "abrir", on, tap)
                                "notificaciones" -> ListRow("Notificaciones", "abrir", on, tap)
                                else -> ListRow("Ajustes de Android", "abrir", on, tap)
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
                                else -> ListRow("ClusterTune · límites de CPU y GPU", "abrir", on, tap)
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                HintBar(
                    listOf("A" to "Abrir", "◀ ▶" to "Ajustar") + (if (console.isEmpty()) emptyList() else listOf("L1 R1" to "Cambiar de ventana")) + listOf("B" to "Cerrar"),
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
            Spacer(Modifier.height(10.dp))
            Segments(if (value < 0) 0f else value / 100f, if (selected) accent else P.Ink, Modifier.fillMaxWidth(), count = 20)
        }
    }
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
