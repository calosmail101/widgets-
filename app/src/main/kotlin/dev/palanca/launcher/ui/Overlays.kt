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
 * Panel rápido: brillo, volumen, modos de la consola y accesos del sistema en una ventana.
 * Los modos de rendimiento y ventilador solo aparecen en consolas que los tienen.
 */
class QuickPanel : Screen {
    override val overlay get() = true
    private var sel by mutableIntStateOf(0)
    private var brightness by mutableIntStateOf(Actions.brightness())
    private var volume by mutableIntStateOf(Actions.volume())
    private var performance by mutableIntStateOf(DeviceModes.performance())
    private var fan by mutableIntStateOf(DeviceModes.fan())
    private val clusterTune = Env.apps.apps.value.firstOrNull { it.pkg == "com.aure.clustertune" }

    private fun rows(): List<String> = buildList {
        add("brillo"); add("volumen")
        if (performance >= 0) add("rendimiento")
        if (fan >= 0) add("ventilador")
        add("wifi"); add("bluetooth"); add("notificaciones")
        if (clusterTune != null) add("clustertune")
        add("ajustes")
    }

    /** Aplica un modo y comprueba medio segundo después que la consola lo aceptó. */
    private fun setMode(apply: () -> Boolean, read: () -> Int, wanted: Int, store: (Int) -> Unit) {
        if (!Privileged.available()) { Actions.toast("Esta consola no expone el servicio para cambiar modos"); return }
        store(wanted)
        apply()
        Env.scope.launch {
            delay(500)
            val now = read()
            store(now)
            if (now != wanted) Actions.toast("La consola no aceptó el cambio")
        }
    }

    private fun adjust(delta: Int) {
        val dir = if (delta > 0) 1 else -1
        when (rows().getOrNull(sel)) {
            "brillo" -> if (Actions.canWriteSettings()) { brightness = (brightness + delta).coerceIn(1, 100); Actions.setBrightness(brightness) }
            "volumen" -> { Actions.volumeStep(delta); volume = Actions.volume() }
            "rendimiento" -> setMode({ DeviceModes.setPerformance((performance)) }, DeviceModes::performance, (performance + dir).coerceIn(0, 2)) { performance = it }
            "ventilador" -> setMode({ DeviceModes.setFan(fan) }, DeviceModes::fan, (fan + dir).coerceIn(0, 6)) { fan = it }
        }
    }

    private fun activate(row: String) {
        when (row) {
            "brillo" -> if (!Actions.canWriteSettings()) Actions.askWriteSettings()
            "rendimiento", "ventilador" -> adjust(+5)
            "wifi" -> if (!Actions.open(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)) Actions.open(Settings.ACTION_WIFI_SETTINGS)
            "bluetooth" -> Actions.open(Settings.ACTION_BLUETOOTH_SETTINGS)
            "notificaciones" -> { Env.nav.pop(); Actions.run(dev.palanca.launcher.system.ActionId.NOTIFICATIONS) }
            "clustertune" -> { Env.nav.pop(); clusterTune?.let(Actions::launch) }
            "ajustes" -> { Env.nav.pop(); Actions.open(Settings.ACTION_SETTINGS) }
        }
    }

    override fun onPad(e: PadEvent): Boolean {
        if (e.long) return true
        val rows = rows()
        sel = sel.coerceIn(0, rows.lastIndex)
        when (e.btn) {
            Btn.UP -> sel = (sel + rows.size - 1) % rows.size
            Btn.DOWN -> sel = (sel + 1) % rows.size
            Btn.LEFT -> adjust(-5)
            Btn.RIGHT -> adjust(+5)
            Btn.RS_LEFT -> adjust(-15)
            Btn.RS_RIGHT -> adjust(+15)
            Btn.A -> activate(rows[sel])
            Btn.B, Btn.SELECT, Btn.L2 -> Env.nav.pop()
            else -> Unit
        }
        return true
    }

    @Composable
    override fun Content() {
        val accent = LocalAccent.current
        val canWrite = remember(sel) { Actions.canWriteSettings() }
        val rows = rows()
        val current = sel.coerceIn(0, rows.lastIndex)
        val state = rememberLazyListState()
        LaunchedEffect(current) { state.animateScrollToItem((current - 3).coerceAtLeast(0)) }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.62f)).tap { Env.nav.pop() }) {
            Column(
                Modifier.align(Alignment.Center).width(600.dp).heightIn(max = 630.dp).enter(18.dp, 0.94f, 200)
                    .background(WINDOW, RoundedCornerShape(34.dp))
                    .tap { }.padding(start = 24.dp, end = 24.dp, top = 26.dp, bottom = 20.dp),
            ) {
                T("Panel rápido", 26, weight = FontWeight.Medium, modifier = Modifier.padding(start = 6.dp, bottom = 12.dp))
                LazyColumn(Modifier.weight(1f, fill = false), state = state, contentPadding = PaddingValues(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(rows) { i, row ->
                        val on = i == current
                        val tap = Modifier.tap { sel = i; activate(row) }
                        when (row) {
                            "brillo" -> Slider("Brillo", if (canWrite) brightness else -1, on, tap)
                            "volumen" -> Slider("Volumen", volume, on, tap)
                            "rendimiento" -> ListRow("Rendimiento", DeviceModes.PERFORMANCE.firstOrNull { it.first == performance }?.second ?: "modo $performance", on, tap, arrows = true)
                            "ventilador" -> ListRow("Ventilador", DeviceModes.fanName(fan), on, tap, arrows = true)
                            "wifi" -> ListRow("Wi‑Fi e internet", "abrir", on, tap)
                            "bluetooth" -> ListRow("Bluetooth", "abrir", on, tap)
                            "notificaciones" -> ListRow("Notificaciones", "abrir", on, tap)
                            "clustertune" -> ListRow("ClusterTune · límites de CPU y GPU", "abrir", on, tap)
                            else -> ListRow("Ajustes de Android", "abrir", on, tap)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                HintBar(listOf("A" to "Abrir", "◀ ▶" to "Ajustar", "B" to "Cerrar"))
            }
        }
    }

    @Composable
    private fun Slider(title: String, value: Int, selected: Boolean, modifier: Modifier) {
        val accent = LocalAccent.current
        Column(
            modifier.fillMaxWidth().background(if (selected) P.Line else Color.Transparent, chamfer(12.dp))
                .brackets(selected, accent, gap = 2.dp, len = 10.dp, stroke = 2.5.dp).padding(horizontal = 18.dp, vertical = 14.dp),
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
