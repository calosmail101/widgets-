package dev.palanca.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.TransformOrigin
import dev.palanca.launcher.Env
import dev.palanca.launcher.data.AppEntry
import dev.palanca.launcher.data.Cat
import dev.palanca.launcher.data.WidgetSlot
import dev.palanca.launcher.data.categoryIn
import dev.palanca.launcher.input.Btn
import dev.palanca.launcher.input.PadEvent
import dev.palanca.launcher.system.Actions
import dev.palanca.launcher.system.DeviceModes

/** Fondo de las ventanas flotantes: un punto más claro que los paneles. */
val WINDOW = Color(0xFF191A1F)

class MenuItem(val label: String, val value: String = "", val keep: Boolean = false, val run: () -> Unit)

/** Ventana flotante con una lista de acciones. Se usa para los menús de app, widget y categoría. */
class MenuScreen(
    private val index: String, private val title: String, private val items: () -> List<MenuItem>,
) : Screen, Closable {
    override val overlay get() = true
    override var closing by mutableStateOf(false)
    private var sel by mutableIntStateOf(0)

    override fun onPad(e: PadEvent): Boolean {
        val list = items()
        sel = sel.coerceIn(0, (list.size - 1).coerceAtLeast(0))
        when (e.btn) {
            Btn.UP, Btn.RS_UP -> sel = if (sel > 0) sel - 1 else list.lastIndex
            Btn.DOWN, Btn.RS_DOWN -> sel = if (sel < list.lastIndex) sel + 1 else 0
            Btn.A -> if (!e.long) list.getOrNull(sel)?.let(::run)
            Btn.B, Btn.X -> if (!e.long) Env.nav.pop()
            else -> Unit
        }
        return true // un menú abierto se queda con todo el mando
    }

    private fun run(item: MenuItem) {
        if (!item.keep) Env.nav.stack.remove(this)
        item.run()
    }

    @Composable
    override fun Content() {
        LocalCfg.current // se relee al cambiar la configuración
        val list = items()
        Popup(TransformOrigin(0.5f, 0.55f), closing, { Env.nav.pop() }) {
            Window(Modifier.align(Alignment.Center).width(540.dp).heightIn(max = 610.dp)) {
                T(index, 14, LocalAccent.current, FontWeight.Bold, mono = true)
                Spacer(Modifier.height(6.dp))
                T(title, 26, weight = FontWeight.Medium)
                Box(Modifier.padding(top = 16.dp, bottom = 14.dp).fillMaxWidth().height(1.dp).background(P.Line))
                val current = sel.coerceIn(0, (list.size - 1).coerceAtLeast(0))
                val state = rememberLazyListState()
                LaunchedEffect(current) { if (list.isNotEmpty()) state.animateScrollToItem((current - 4).coerceAtLeast(0)) }
                LazyColumn(Modifier.weight(1f, fill = false), state = state, contentPadding = PaddingValues(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(list) { i, item ->
                        ListRow(item.label, item.value, i == current, Modifier.tap { sel = i; run(item) }, height = 50.dp)
                    }
                }
                HintBar(listOf("A" to "Elegir", "B" to "Cerrar"))
            }
        }
    }
}

fun appMenu(app: AppEntry) = MenuScreen("APP", app.label) {
    val cfg = Env.cfg.value
    val pinned = app.key in cfg.pinned
    val hidden = app.key in cfg.hidden
    // Reordenar el carrusel de Inicio (el stick derecho ahora solo lo recorre).
    fun move(dir: Int) = Env.cfg.update { c ->
        val list = c.pinned.toMutableList()
        val i = list.indexOf(app.key); val j = i + dir
        if (i >= 0 && j in list.indices) list.add(j, list.removeAt(i))
        c.copy(pinned = list)
    }
    val profile = cfg.profiles[app.pkg]
    listOfNotNull(
        MenuItem("Abrir") { Actions.launch(app) },
        MenuItem(if (pinned) "Quitar de Inicio" else "Fijar en Inicio") { togglePin(app) },
        if (pinned) MenuItem("Mover a la izquierda", keep = true) { move(-1) } else null,
        if (pinned) MenuItem("Mover a la derecha", keep = true) { move(+1) } else null,
        if (DeviceModes.performance() >= 0) MenuItem("Perfil de la consola", profileSummary(profile)) { Env.nav.push(ProfileScreen(app)) } else null,
        MenuItem("Editar icono") { Env.nav.push(IconEditorScreen(app)) },
        MenuItem("Categoría", app.categoryIn(cfg).title) { Env.nav.push(categoryMenu(app)) },
        MenuItem(if (hidden) "Mostrar en el cajón" else "Ocultar del cajón") {
            Env.cfg.update { c -> c.copy(hidden = if (hidden) c.hidden - app.key else c.hidden + app.key, pinned = if (hidden) c.pinned else c.pinned - app.key) }
        },
        MenuItem("Información de la app") { Env.apps.details(app) },
        MenuItem("Desinstalar") { Actions.uninstall(app) },
    )
}

fun categoryMenu(app: AppEntry) = MenuScreen("CATEGORÍA", app.label) {
    val current = app.categoryIn(Env.cfg.value)
    Cat.entries.map { cat ->
        MenuItem(cat.title, if (cat == current) "actual" else "") {
            Env.cfg.update { c -> c.copy(categories = if (cat == app.category) c.categories - app.key else c.categories + (app.key to cat.name)) }
        }
    }
}

fun widgetMenu(slot: WidgetSlot) = MenuScreen("WIDGET", Env.widgets.info(slot.id)?.loadLabel(Env.ctx.packageManager)?.toString() ?: "Widget") {
    fun edit(f: (MutableList<WidgetSlot>, Int) -> Unit) = Env.cfg.update { c ->
        val list = c.widgets.toMutableList()
        val i = list.indexOfFirst { it.id == slot.id }
        if (i >= 0) f(list, i)
        c.copy(widgets = list)
    }
    listOf(
        MenuItem("Más ancho", keep = true) { edit { l, i -> l[i] = l[i].copy(cols = (l[i].cols + 1).coerceAtMost(6)) } },
        MenuItem("Más estrecho", keep = true) { edit { l, i -> l[i] = l[i].copy(cols = (l[i].cols - 1).coerceAtLeast(1)) } },
        MenuItem("Mover a la izquierda", keep = true) { edit { l, i -> if (i > 0) l.add(i - 1, l.removeAt(i)) } },
        MenuItem("Mover a la derecha", keep = true) { edit { l, i -> if (i < l.lastIndex) l.add(i + 1, l.removeAt(i)) } },
        if (Env.cfg.value.homeWidget == slot.id) MenuItem("Quitar de Inicio") { Env.cfg.update { it.copy(homeWidget = -1) } }
        else MenuItem("Mostrar en Inicio") { Env.cfg.update { it.copy(homeWidget = slot.id) } },
        MenuItem("Quitar") {
            Env.cfg.update { if (it.homeWidget == slot.id) it.copy(homeWidget = -1) else it }
            edit { l, i -> l.removeAt(i) }
            runCatching { Env.widgets.host.deleteAppWidgetId(slot.id) }
        },
    )
}

/** Lista de widgets instalados en el sistema. */
class WidgetPicker : Screen, Closable {
    override val overlay get() = true
    override var closing by mutableStateOf(false)
    private var sel by mutableIntStateOf(0)
    private val providers = Env.widgets.providers().sortedBy { it.loadLabel(Env.ctx.packageManager).lowercase() }

    override fun onPad(e: PadEvent): Boolean {
        when (e.btn) {
            Btn.UP -> sel = (sel - 1).coerceAtLeast(0)
            Btn.DOWN -> sel = (sel + 1).coerceAtMost((providers.size - 1).coerceAtLeast(0))
            Btn.RS_UP -> sel = (sel - 6).coerceAtLeast(0)
            Btn.RS_DOWN -> sel = (sel + 6).coerceAtMost((providers.size - 1).coerceAtLeast(0))
            Btn.A -> if (!e.long) providers.getOrNull(sel)?.let { Env.nav.pop(); Env.host?.addWidget(it) }
            Btn.B -> Env.nav.pop()
            else -> Unit
        }
        return true
    }

    @Composable
    override fun Content() {
        val pm = Env.ctx.packageManager
        val state = rememberLazyListState()
        LaunchedEffect(sel) { if (providers.isNotEmpty()) state.animateScrollToItem((sel - 3).coerceAtLeast(0)) }
        Popup(TransformOrigin(0.5f, 0.5f), closing, { Env.nav.pop() }) {
          Window(Modifier.align(Alignment.Center).width(760.dp).heightIn(max = 600.dp)) {
            T("Añadir widget", 24, weight = FontWeight.Medium, modifier = Modifier.padding(start = 8.dp, bottom = 10.dp))
            if (providers.isEmpty()) Label("No hay widgets instalados", modifier = Modifier.padding(8.dp))
            LazyColumn(Modifier.weight(1f, fill = false), state = state, contentPadding = PaddingValues(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(providers) { i, info ->
                    val app = remember(info) { runCatching { pm.getApplicationLabel(pm.getApplicationInfo(info.provider.packageName, 0)).toString() }.getOrDefault("") }
                    ListRow(info.loadLabel(pm), app, i == sel, Modifier.tap { sel = i; Env.nav.pop(); Env.host?.addWidget(info) })
                }
            }
            Spacer(Modifier.height(8.dp))
            HintBar(listOf("A" to "Añadir", "B" to "Volver"), left = "${providers.size} disponibles")
          }
        }
    }
}
