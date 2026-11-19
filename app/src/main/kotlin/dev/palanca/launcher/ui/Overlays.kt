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
import dev.palanca.launcher.system.FrameGen
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
    private var refresh by mutableIntStateOf(DeviceModes.refresh())
    private var charge by mutableIntStateOf(DeviceModes.chargeLimit())
    private val clusterTune = Env.apps.apps.value.firstOrNull { it.pkg == "com.aure.clustertune" }
    private val jobs = HashMap<String, Job>()

    /** Las cuatro cajitas del final de CNTRL: estilo de mando, gatillos, luces y ajustes de Android. */
    private val boxes = listOf("mando", "gatillos", "luces", "ajustes")
    private var box by mutableIntStateOf(0)
    private val system = listOf("brillo", "volumen", "wifi", "bluetooth", "archivos", "cajas")
    private fun console(): List<String> = buildList {
        if (performance >= 0) add("rendimiento")
        if (fan >= 0) add("ventilador")
        // La velocidad está siempre en la lista (solo actúa en Personalizado): así la ventana no cambia de tamaño.
        if (fan >= 0) add("velocidad")
        if (refresh >= 0) add("pantalla")
        if (charge >= 0) add("carga")
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
            // El brillo va de uno en uno (con el stick derecho, de cinco en cinco): cambio continuo.
            "brillo" -> if (Actions.canWriteSettings()) { brightness = (brightness + (if (kotlin.math.abs(delta) > 5) dir * 5 else dir)).coerceIn(1, 100); Actions.setBrightness(brightness) }
            "volumen" -> { Actions.volumeStep(delta); volume = Actions.volume() }
            "rendimiento" -> change("perf", next(DeviceModes.PERFORMANCE, performance), { performance = it }, DeviceModes::setPerformance, DeviceModes::performance)
            "ventilador" -> change("fan", next(DeviceModes.FAN, fan), { fan = it }, DeviceModes::setFan, DeviceModes::fan)
            "velocidad" -> if (fan == 6) change("speed", (fanSpeed + dir * (if (kotlin.math.abs(delta) > 5) 5 else 1)).coerceIn(0, 100), { fanSpeed = it }, DeviceModes::setFanSpeed, DeviceModes::fanSpeed)
            "pantalla" -> change("hz", next(DeviceModes.REFRESH, refresh), { refresh = it }, DeviceModes::setRefresh, DeviceModes::refresh)
            "carga" -> change("charge", if (charge == 1) 0 else 1, { charge = it }, DeviceModes::setChargeLimit, DeviceModes::chargeLimit)
        }
    }

    private fun activate(row: String) {
        when (row) {
            "brillo" -> if (!Actions.canWriteSettings()) Actions.askWriteSettings()
            "rendimiento", "ventilador", "velocidad", "carga" -> adjust(+5)
            // A alterna entre 60 y 120 Hz (las flechas eligen uno u otro).
            "pantalla" -> change("hz", if (refresh == 120) 60 else 120, { refresh = it }, DeviceModes::setRefresh, DeviceModes::refresh)
            "cajas" -> openBox(boxes[box])
            "wifi" -> if (!Actions.open(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)) Actions.open(Settings.ACTION_WIFI_SETTINGS)
            "bluetooth" -> Actions.open(Settings.ACTION_BLUETOOTH_SETTINGS)
            "archivos" -> { Env.nav.pop(); Env.nav.push(FilesScreen(TransformOrigin(0.2f, 0.45f))) }
            "clustertune" -> { Env.nav.pop(); clusterTune?.let { Actions.launch(it) } }
            "ajustes" -> { Env.nav.pop(); Actions.open(Settings.ACTION_SETTINGS) }
        }
    }

    private fun openBox(name: String) {
        when (name) {
            "mando" -> Env.nav.push(LearnedScreen("mando", "Estilo de mando", "Controller style", listOf("Retro", "Xbox")))
            "gatillos" -> Env.nav.push(LearnedScreen("gatillos", "Modo de los gatillos", "Trigger input mode", listOf("Analógico", "Digital", "Ambos")))
            "luces" -> if (DeviceModes.lightsSupported()) Env.nav.push(LightsScreen()) else Actions.toast("Esta consola no tiene luces configurables")
            else -> { Env.nav.pop(); Actions.open(Settings.ACTION_SETTINGS) }
        }
    }

    private fun adjustable(row: String?) = row in setOf("brillo", "volumen", "rendimiento", "ventilador", "velocidad", "pantalla", "carga")
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
            Btn.LEFT -> if (row == "cajas") box = (box - 1).coerceAtLeast(0) else if (adjustable(row)) adjust(-5) else switchSide(0)
            Btn.RIGHT -> if (row == "cajas") { if (box < boxes.lastIndex) box++ else switchSide(1) } else if (adjustable(row)) adjust(+5) else switchSide(1)
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
                                "archivos" -> ListRow("Archivos y limpieza", "", on, tap)
                                // Cuatro cajitas de cristal con su icono; cada una abre su ventana.
                                else -> Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    boxes.forEachIndexed { k, name ->
                                        val picked = on && k == box
                                        Box(
                                            Modifier.weight(1f).height(84.dp).brackets(picked, LocalAccent.current, gap = 3.dp, radius = 24.dp)
                                                .glassPanel(RoundedCornerShape(24.dp)).tap { side = 0; sel = i; box = k; openBox(name) },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            val ink = if (picked) P.Ink else P.Dim
                                            when (name) {
                                                "mando" -> PadGlyph(ink, Modifier.size(46.dp))
                                                "gatillos" -> TriggerGlyph(ink, Modifier.size(40.dp))
                                                "luces" -> LightGlyph(ink, Modifier.size(40.dp))
                                                else -> GearGlyph(ink, Modifier.size(40.dp))
                                            }
                                        }
                                    }
                                }
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
                                "pantalla" -> ListRow("Pantalla", DeviceModes.name(DeviceModes.REFRESH, refresh), on, tap, arrows = true)
                                "carga" -> ListRow("Límite de carga al 80 %", "", on, tap) { DropSwitch(charge == 1) }
                                else -> ListRow("ClusterTune · límites de CPU y GPU", "", on, tap)
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }
            }
            // La leyenda va donde la de Inicio (abajo a la derecha de la pantalla): la vista no tiene que buscarla.
            HintBar(
                listOf("A" to "Elegir", "◀ ▶" to "Ajustar") + (if (console.isEmpty()) emptyList() else listOf("L1 R1" to "Cambiar de ventana")) + listOf("B" to "Cerrar"),
                Modifier.align(Alignment.BottomCenter).padding(start = 56.dp, end = 56.dp, bottom = 20.dp),
            )
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

/** Resumen corto de un perfil para el menú de la app: «sin perfil» o «High · Sport · 120 Hz». */
fun profileSummary(p: dev.palanca.launcher.data.Profile?): String {
    if (p == null || p.isEmpty()) return "sin perfil"
    return listOfNotNull(
        p.performance?.let { DeviceModes.name(DeviceModes.PERFORMANCE, it).replace(" performance", "") },
        p.fan?.let { DeviceModes.name(DeviceModes.FAN, it) },
        p.hz?.let { "$it Hz" },
        p.fg?.let { if (it == 1) "sin FG" else "FG ${FrameGen.name(it)}" },
    ).joinToString(" · ")
}

/**
 * Perfil de la consola para una app: modo, ventilador, frecuencia de pantalla y Heaven FG que se ponen al abrirla
 * (también desde Cocoon u otro frontend) y se deshacen al volver. «Sin cambiar» deja ese ajuste como esté.
 */
class ProfileScreen(private val app: AppEntry) : Screen, Closable {
    override val overlay get() = true
    override var closing by mutableStateOf(false)
    private var row by mutableIntStateOf(0)
    private var p by mutableStateOf(Env.cfg.value.profiles[app.pkg] ?: dev.palanca.launcher.data.Profile())

    // Cada fila: opciones con null = «Sin cambiar».
    private val perfOptions = listOf<Int?>(null) + DeviceModes.PERFORMANCE.map { it.first }
    private val fanOptions = listOf<Int?>(null) + DeviceModes.FAN.map { it.first }
    private val hzOptions = listOf<Int?>(null) + DeviceModes.REFRESH.map { it.first }
    private val fgOptions = listOf<Int?>(null) + FrameGen.MODES.map { it.first }
    private fun rows() = buildList { add("modo"); add("ventilador"); if (p.fan == 6) add("velocidad"); add("pantalla"); add("fg") }

    private fun <T> cycle(options: List<T>, current: T, dir: Int): T =
        options[(options.indexOf(current).coerceAtLeast(0) + dir + options.size) % options.size]

    private fun save() = Env.cfg.update { c ->
        c.copy(profiles = if (p.isEmpty()) c.profiles - app.pkg else c.profiles + (app.pkg to p))
    }

    private fun change(dir: Int) {
        p = when (rows().getOrNull(row)) {
            "modo" -> p.copy(performance = cycle(perfOptions, p.performance, dir))
            "ventilador" -> p.copy(fan = cycle(fanOptions, p.fan, dir)).let { if (it.fan == 6 && it.fanSpeed == null) it.copy(fanSpeed = 50) else it }
            "velocidad" -> p.copy(fanSpeed = ((p.fanSpeed ?: 50) + dir * 5).coerceIn(0, 100))
            "pantalla" -> p.copy(hz = cycle(hzOptions, p.hz, dir))
            "fg" -> p.copy(fg = cycle(fgOptions, p.fg, dir))
            else -> p
        }
        save()
    }

    override fun onPad(e: PadEvent): Boolean {
        if (e.long) return true
        val n = rows().size
        when (e.btn) {
            Btn.UP -> row = (row + n - 1) % n
            Btn.DOWN -> row = (row + 1) % n
            Btn.LEFT -> change(-1)
            Btn.RIGHT, Btn.A -> change(+1)
            // X borra el perfil entero.
            Btn.X -> { p = dev.palanca.launcher.data.Profile(); save() }
            Btn.B -> Env.nav.pop()
            else -> Unit
        }
        return true
    }

    @Composable
    override fun Content() {
        val accent = LocalAccent.current
        Popup(TransformOrigin(0.5f, 0.6f), closing, { Env.nav.pop() }) {
            Window(Modifier.align(Alignment.Center).width(760.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp, bottom = 6.dp)) {
                    AppIcon(app, 44.dp)
                    Spacer(Modifier.width(14.dp))
                    T("Perfil · ${app.label}", 24, weight = FontWeight.Medium)
                }
                T("Se aplica al abrir la app (también desde Cocoon) y se deshace al volver a Heaven. La generación de fotogramas solo actúa si el juego usa el driver Heaven FG.", 14, P.Dim, maxLines = 3, modifier = Modifier.padding(start = 8.dp, bottom = 10.dp))
                rows().forEachIndexed { i, r ->
                    val on = i == row
                    val tap = Modifier.tap { row = i; change(+1) }
                    when (r) {
                        "modo" -> ListRow("Modo", p.performance?.let { DeviceModes.name(DeviceModes.PERFORMANCE, it) } ?: "Sin cambiar", on, tap, arrows = true)
                        "ventilador" -> ListRow("Ventilador", p.fan?.let { DeviceModes.name(DeviceModes.FAN, it) } ?: "Sin cambiar", on, tap, arrows = true)
                        "velocidad" -> ListRow("Velocidad del ventilador", "", on, tap) {
                            T("${p.fanSpeed ?: 50} %", 14, if (on) accent else P.Dim, mono = true)
                            Spacer(Modifier.width(14.dp))
                            DropSlider((p.fanSpeed ?: 50) / 100f, if (on) accent else P.Dim, Modifier.width(200.dp))
                        }
                        "fg" -> ListRow("Generación de fotogramas (Heaven FG)", p.fg?.let { FrameGen.name(it) } ?: "Por defecto (x2)", on, tap, arrows = true)
                        else -> ListRow("Pantalla", p.hz?.let { "$it Hz" } ?: "Sin cambiar", on, tap, arrows = true)
                    }
                    Spacer(Modifier.height(4.dp))
                }
                Spacer(Modifier.height(10.dp))
                HintBar(listOf("◀ ▶" to "Cambiar", "X" to "Borrar perfil", "B" to "Cerrar"))
            }
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

    private var sentOn: Boolean? = null
    private var sentHue = -1
    private var sentBrightness = -1

    /**
     * Envía a la consola lo que haya cambiado, mientras se mueve y no al soltar: como mucho un
     * comando cada 90 ms, siempre con el valor más reciente.
     */
    private fun apply() {
        if (job?.isActive == true) return
        job = Env.scope.launch {
            while (sentOn != on || sentHue != hue || sentBrightness != brightness) {
                val o = on.takeIf { it != sentOn }; val h = hue.takeIf { it != sentHue }; val b = brightness.takeIf { it != sentBrightness }
                sentOn = on; sentHue = hue; sentBrightness = brightness
                val ok = withContext(Dispatchers.IO) { DeviceModes.sendLights(o, h?.let { color() }, b) }
                if (!ok) { Actions.toast("Esta consola no permite cambiar las luces"); return@launch }
                delay(90)
            }
        }
    }

    override fun onStick(x: Float, y: Float) {
        if (kotlin.math.hypot(x, y) < 0.65f) return
        val angle = ((Math.toDegrees(kotlin.math.atan2(y, x).toDouble()) + 360) % 360).toInt() / 2 * 2
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
                    1 -> hue = (hue + dir * 3 + 360) % 360
                    else -> brightness = (brightness + dir * 2).coerceIn(2, 100)
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

/**
 * Un control de la consola con varios estados (estilo de mando, modo de gatillos). La primera vez
 * hay que "enseñárselo" a Heaven: se pone el mosaico de Android en cada estado y se toma una foto de
 * los ajustes del sistema; el ajuste que distingue todos los estados es el que se guarda.
 * Después, la ventana muestra una casilla por estado: A elige la que tiene el foco.
 */
class LearnedScreen(
    private val id: String, private val title: String, private val tile: String, private val names: List<String>,
) : Screen, Closable {
    override val overlay get() = true
    override var closing by mutableStateOf(false)
    private var cursor by mutableIntStateOf(0)
    private var shakes by mutableIntStateOf(0)
    private var busy by mutableStateOf(false)
    private var note by mutableStateOf("")
    private val photos = ArrayList<Map<String, String>>()
    private var captured by mutableIntStateOf(0)

    /** En consolas Retroid el control ya se conoce: no hace falta enseñárselo a Heaven. */
    private val known = DeviceModes.builtin(id)
    private fun learned() = known ?: Env.cfg.value.learned[id]
    private var value by mutableStateOf(learned()?.let(DeviceModes::learnedValue))

    init {
        // El foco empieza en el estado que está activo ahora.
        learned()?.states()?.indexOfFirst { it.first == value }?.takeIf { it >= 0 }?.let { cursor = it }
    }

    private fun choose() {
        val l = learned() ?: return
        val pick = l.states().getOrNull(cursor) ?: return
        // Ya estaba elegida: una sacudida corta en vez de hacer nada en silencio.
        if (pick.first == value) { shakes++; return }
        value = pick.first
        Env.scope.launch {
            if (!withContext(Dispatchers.IO) { DeviceModes.setLearned(l, pick.first) }) Actions.toast("La consola no aceptó el cambio")
        }
    }

    private fun capture() {
        if (busy) return
        busy = true; note = "Leyendo los ajustes de la consola…"
        Env.scope.launch {
            val photo = withContext(Dispatchers.IO) { DeviceModes.snapshot() }
            busy = false
            if (photo == null) { note = "No se pudieron leer los ajustes de esta consola."; return@launch }
            photos += photo; captured = photos.size
            if (photos.size < names.size) { note = ""; return@launch }
            // Sirve el ajuste que tiene un valor distinto en cada uno de los estados.
            val keys = photos[0].keys.filter { k -> photos.all { it[k] != null } && photos.map { it[k] }.toSet().size == names.size }
            val candidates = DeviceModes.diff(photos[0], photos[1]).map { it.first }.filter { it in keys }
            val hit = candidates.minByOrNull { key -> if (Regex("abxy|controller|gamepad|trigger|handle|style|input", RegexOption.IGNORE_CASE).containsMatchIn(key)) 0 else 1 }
            if (hit == null) {
                val partial = DeviceModes.diff(photos[0], photos[1]).joinToString { it.first.substringAfter(':') }.ifEmpty { "ninguno" }
                photos.clear(); captured = 0
                note = "No hay un ajuste que distinga los ${names.size} modos (cambiaron: $partial). Habrá que averiguarlo con la consola conectada al PC."
                return@launch
            }
            val l = dev.palanca.launcher.data.Learned(
                kind = if (hit.startsWith("prop:")) "propiedad" else "ajuste", table = hit.substringBefore(':'), key = hit.substringAfter(':'),
                values = photos.map { it[hit]!! }, names = names,
            )
            Env.cfg.update { it.copy(learned = it.learned + (id to l)) }
            value = l.values.last(); cursor = names.lastIndex
            photos.clear(); captured = 0
            note = "Aprendido: ${l.key} (${l.values.joinToString(" / ")})"
        }
    }

    override fun onPad(e: PadEvent): Boolean {
        if (e.long) return true
        val l = learned()
        when (e.btn) {
            Btn.B -> Env.nav.pop()
            Btn.LEFT -> if (l != null) cursor = (cursor - 1).coerceAtLeast(0)
            Btn.RIGHT -> if (l != null) cursor = (cursor + 1).coerceAtMost(l.states().lastIndex)
            Btn.A -> if (l != null) choose() else capture()
            // X olvida lo aprendido para poder repetir la detección.
            Btn.X -> if (l != null && known == null) { Env.cfg.update { it.copy(learned = it.learned - id) }; photos.clear(); captured = 0; note = "" }
            else -> Unit
        }
        return true
    }

    @Composable
    override fun Content() {
        val accent = LocalAccent.current
        val l = known ?: LocalCfg.current.learned[id]
        Popup(TransformOrigin(0.3f, 0.7f), closing, { Env.nav.pop() }) {
            Window(Modifier.align(Alignment.Center).width(760.dp)) {
                T(title, 24, weight = FontWeight.Medium, modifier = Modifier.padding(start = 8.dp, bottom = 12.dp))
                if (l != null) {
                    val states = l.states()
                    Row(Modifier.fillMaxWidth().padding(6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        states.forEachIndexed { i, (v, name) ->
                            val active = value == v
                            val focused = i == cursor
                            Box(
                                Modifier.weight(1f).height(96.dp)
                                    .then(if (focused) Modifier.shake(shakes) else Modifier)
                                    .brackets(focused, accent, gap = 3.dp, radius = 26.dp)
                                    .then(if (active) Modifier.background(accent.copy(alpha = 0.24f), RoundedCornerShape(26.dp)) else Modifier)
                                    .glassPanel(RoundedCornerShape(26.dp))
                                    .tap { cursor = i; choose() },
                                contentAlignment = Alignment.Center,
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (active) { Dot(accent, 10.dp); Spacer(Modifier.width(10.dp)) }
                                    T(name, 21, if (active) P.Ink else P.Dim, if (active) FontWeight.Medium else FontWeight.Normal)
                                }
                            }
                        }
                    }
                    if (states.size < names.size) T(
                        "Falta «${names.last()}». Pulsa X para volver a detectar y añadirlo.", 14, P.Dim, maxLines = 2,
                        modifier = Modifier.padding(start = 8.dp, top = 6.dp),
                    )
                    if (note.isNotEmpty()) T(note, 13, P.Dim, maxLines = 2, modifier = Modifier.padding(start = 8.dp, top = 6.dp, end = 8.dp))
                    Spacer(Modifier.height(8.dp))
                    HintBar(listOf("A" to "Elegir", "◀ ▶" to "Mover") + (if (known == null) listOf("X" to "Volver a detectar") else emptyList()) + listOf("B" to "Cerrar"))
                } else {
                    T(
                        if (busy) note
                        else "Paso ${captured + 1} de ${names.size}: baja el panel de Android, deja el mosaico «$tile» en «${names[captured.coerceAtMost(names.lastIndex)]}», vuelve aquí y pulsa A.",
                        17, P.Ink, maxLines = 4, modifier = Modifier.padding(horizontal = 8.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    T(
                        note.takeIf { !busy && it.isNotEmpty() } ?: "Heaven compara los ajustes de la consola en cada modo para aprender cuál es el que cambia.",
                        14, P.Dim, maxLines = 4, modifier = Modifier.padding(horizontal = 8.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    HintBar(listOf("A" to "Ya está en ese modo", "B" to "Cerrar"))
                }
            }
        }
    }
}

/** Mueve las piezas de Inicio (reloj, botones y tarjetas) con el stick; se dibuja encima de Inicio sin taparlo. */
class HomeEditScreen : Screen {
    override val overlay get() = true
    // Inicio tiene que verse nítido mientras se colocan sus piezas.
    override val blursBelow get() = false
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
            Btn.LEFT -> nudge(-2, 0)
            Btn.RIGHT -> nudge(+2, 0)
            Btn.UP -> nudge(0, -2)
            Btn.DOWN -> nudge(0, +2)
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

/**
 * Tiempo de juego por juego o emulador (también lo abierto desde Cocoon). L1/R1 cambian entre los
 * últimos 7 y 28 días. Se mide con el servicio del puntero, así que necesita que esté activo.
 */
class PlaytimeScreen : Screen, Closable {
    override val overlay get() = true
    override var closing by mutableStateOf(false)
    private var range by mutableIntStateOf(7)

    override fun onPad(e: PadEvent): Boolean {
        if (e.long) return true
        when (e.btn) {
            Btn.L1, Btn.R1, Btn.LEFT, Btn.RIGHT -> range = if (range == 7) 28 else 7
            Btn.B -> Env.nav.pop()
            else -> Unit
        }
        return true
    }

    @Composable
    override fun Content() {
        val accent = LocalAccent.current
        val list = remember(range) { dev.palanca.launcher.system.Playtime.summary(range) }
        val apps = Env.apps.apps.value
        val top = list.firstOrNull()?.second ?: 1L
        Popup(TransformOrigin(0.5f, 0.5f), closing, { Env.nav.pop() }) {
            Window(Modifier.align(Alignment.Center).width(860.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)) {
                    T("Tiempo de juego", 24, weight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    T(if (range == 7) "últimos 7 días" else "últimos 28 días", 15, accent)
                }
                T("Total: ${dev.palanca.launcher.system.Playtime.human(list.sumOf { it.second })}" +
                    if (!Actions.pointerEnabled()) " · activa el puntero en Custom › Mandos para medirlo" else "",
                    14, P.Dim, maxLines = 2, modifier = Modifier.padding(start = 8.dp, bottom = 12.dp))
                if (list.isEmpty()) T("Todavía no hay partidas en este periodo.", 16, P.Dim, modifier = Modifier.padding(8.dp))
                list.take(7).forEach { (pkg, ms) ->
                    val app = apps.firstOrNull { it.pkg == pkg }
                    Row(Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (app != null) AppIcon(app, 40.dp) else Spacer(Modifier.width(40.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            T(app?.label ?: pkg, 16, P.Ink, FontWeight.Medium)
                            Spacer(Modifier.height(5.dp))
                            Box(Modifier.fillMaxWidth(ms.toFloat() / top).height(5.dp).background(accent, RoundedCornerShape(3.dp)))
                        }
                        Spacer(Modifier.width(16.dp))
                        T(dev.palanca.launcher.system.Playtime.human(ms), 15, P.Ink, mono = true)
                    }
                }
                Spacer(Modifier.height(10.dp))
                HintBar(listOf("L1 R1" to "7 / 28 días", "B" to "Cerrar"))
            }
        }
    }
}
