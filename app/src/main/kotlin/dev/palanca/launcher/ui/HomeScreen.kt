package dev.palanca.launcher.ui

import android.appwidget.AppWidgetHostView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.palanca.launcher.Env
import dev.palanca.launcher.data.AppEntry
import dev.palanca.launcher.data.Cat
import dev.palanca.launcher.data.Config
import dev.palanca.launcher.data.WidgetSlot
import dev.palanca.launcher.data.categoryIn
import dev.palanca.launcher.data.labelIn
import dev.palanca.launcher.input.Btn
import dev.palanca.launcher.input.PadEvent
import dev.palanca.launcher.system.Actions

/** Toque (y toque largo opcional) sin efectos visuales: el foco ya lo marcan los corchetes. */
fun Modifier.tap(onLong: (() -> Unit)? = null, onTap: () -> Unit): Modifier = composed {
    val tapNow by rememberUpdatedState(onTap)
    val longNow by rememberUpdatedState(onLong)
    pointerInput(Unit) { detectTapGestures(onTap = { tapNow() }, onLongPress = { longNow?.invoke() }) }
}

/** Icono + etiqueta con corchetes de foco. La pieza que comparten el Inicio y el cajón. */
@Composable
fun AppTile(
    app: AppEntry, size: Dp, selected: Boolean, modifier: Modifier = Modifier,
    onTap: () -> Unit = {}, onLong: () -> Unit = {},
) {
    val cfg = LocalCfg.current
    val scale by animateFloatAsState(if (selected) 1.12f else 1f, tween(120), label = "tile")
    val tapNow by rememberUpdatedState(onTap)
    val longNow by rememberUpdatedState(onLong)
    Column(
        modifier.width(size + 44.dp).pointerInput(Unit) {
            detectTapGestures(onTap = { tapNow() }, onLongPress = { longNow() })
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.graphicsLayer { scaleX = scale; scaleY = scale }
                .brackets(selected, LocalAccent.current)
        ) { AppIcon(app, size) }
        if (cfg.look.labels) {
            Spacer(Modifier.height(16.dp))
            T(
                app.labelIn(cfg), 14, if (selected) P.Ink else P.Dim,
                if (selected) FontWeight.Medium else FontWeight.Normal,
                modifier = Modifier.fillMaxWidth(), align = TextAlign.Center,
            )
        }
    }
}

fun pinnedApps(cfg: Config, apps: List<AppEntry>): List<AppEntry> {
    val byKey = apps.associateBy { it.key }
    return cfg.pinned.mapNotNull { byKey[it] }
}

fun ago(ms: Long): String {
    val min = (System.currentTimeMillis() - ms) / 60_000
    return when {
        min < 1 -> "ahora mismo"
        min < 60 -> "hace $min min"
        min < 60 * 24 -> "hace ${min / 60} h"
        else -> "hace ${min / (60 * 24)} d"
    }
}

fun duration(ms: Long): String {
    val min = ms / 60_000
    return if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"
}

class HomeScreen : Screen {
    private var page by mutableIntStateOf(0)
    private var zone by mutableIntStateOf(0) // 0 = barra de apps, 1 = tarjeta "continuar"
    private var dock by mutableIntStateOf(0)
    private var widget by mutableIntStateOf(0)

    fun reset() { page = 0; zone = 0 }

    private fun pins() = pinnedApps(Env.cfg.value, Env.apps.apps.value)

    override fun onPad(e: PadEvent): Boolean {
        val cfg = Env.cfg.value
        when (e.btn) {
            Btn.L1, Btn.R1 -> if (!e.long) { page = 1 - page; zone = 0 }
            Btn.START -> if (e.long) return false else Env.nav.push(SettingsScreen())
            Btn.SELECT -> if (e.long) return false else Env.nav.push(QuickPanel())
            Btn.Y -> if (!e.long) Env.nav.push(KeyboardScreen.search())
            else -> return if (page == 0) homePad(e, cfg) else widgetPad(e, cfg)
        }
        return true
    }

    private fun homePad(e: PadEvent, cfg: Config): Boolean {
        val pins = pins()
        dock = dock.coerceIn(0, pins.size)
        val app = pins.getOrNull(dock)
        when (e.btn) {
            Btn.LEFT -> if (zone == 0) dock = (dock - 1).coerceAtLeast(0)
            Btn.RIGHT -> if (zone == 0) dock = (dock + 1).coerceAtMost(pins.size)
            Btn.UP -> if (zone == 0 && cfg.look.cardContinue && Actions.lastApp() != null) zone = 1
            Btn.DOWN -> if (zone == 1) zone = 0 else Env.nav.push(DrawerScreen())
            Btn.A -> when {
                e.long -> if (zone == 0 && app != null) Env.nav.push(appMenu(app))
                zone == 1 -> Actions.lastApp()?.let(Actions::launch)
                app != null -> Actions.launch(app)
                else -> Env.nav.push(DrawerScreen())
            }
            Btn.X -> if (zone == 0 && app != null && !e.long) Env.nav.push(appMenu(app))
            Btn.RS_LEFT, Btn.RS_RIGHT -> if (zone == 0 && app != null) {
                val to = (dock + if (e.btn == Btn.RS_LEFT) -1 else 1)
                if (to in pins.indices) {
                    Env.cfg.update { c ->
                        val list = c.pinned.toMutableList()
                        val i = list.indexOf(app.key); val j = list.indexOf(pins[to].key)
                        if (i >= 0 && j >= 0) { list[i] = list[j].also { list[j] = list[i] } }
                        c.copy(pinned = list)
                    }
                    dock = to
                }
            }
            Btn.B -> zone = 0
            else -> return false
        }
        return true
    }

    private fun widgetPad(e: PadEvent, cfg: Config): Boolean {
        val count = cfg.widgets.size
        widget = widget.coerceIn(0, count)
        when (e.btn) {
            Btn.LEFT -> widget = (widget - 1).coerceAtLeast(0)
            Btn.RIGHT -> widget = (widget + 1).coerceAtMost(count)
            Btn.A, Btn.X -> if (!e.long) {
                val slot = cfg.widgets.getOrNull(widget)
                if (slot == null) Env.nav.push(WidgetPicker()) else Env.nav.push(widgetMenu(slot))
            }
            Btn.B, Btn.DOWN -> page = 0
            else -> return false
        }
        return true
    }

    @Composable
    override fun Content() {
        val cfg = LocalCfg.current
        val look = cfg.look
        val accent = LocalAccent.current
        val apps by Env.apps.apps.collectAsState()
        val battery by rememberBattery()
        Box(Modifier.fillMaxSize()) {
            Wallpaper(look)
            Column(Modifier.fillMaxSize().padding(start = 56.dp, end = 56.dp, top = 30.dp, bottom = 24.dp)) {
                // Cabecera: páginas a la izquierda, estado a la derecha.
                Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf("Inicio", "Widgets").forEachIndexed { i, name ->
                        val on = i == page
                        Box(Modifier.tap { page = i; zone = 0 }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                T("0${i + 1}", 13, if (on) accent else P.Dim, FontWeight.Bold, mono = true)
                                Spacer(Modifier.width(8.dp))
                                Label(name, if (on) P.Ink else P.Dim, size = 13)
                            }
                        }
                        Spacer(Modifier.width(28.dp))
                    }
                    Glyph("L1"); Spacer(Modifier.width(6.dp)); Glyph("R1")
                    Spacer(Modifier.weight(1f))
                    if (battery.percent >= 0) {
                        Label(if (battery.charging) "Cargando" else "Batería", size = 13)
                        Spacer(Modifier.width(10.dp))
                        T("${battery.percent} %", 13, P.Ink, FontWeight.Bold, mono = true)
                    }
                }

                if (page == 0) {
                    val pins = remember(cfg.pinned, apps) { pinnedApps(cfg, apps) }
                    Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        val now by rememberNow(look.clockSeconds)
                        Column(Modifier.weight(1f)) {
                            ClockView(look, if (look.clockSeconds) 112.dp else 150.dp, now)
                            if (look.clockDate) {
                                Spacer(Modifier.height(22.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.width(26.dp).height(3.dp).background(accent))
                                    Spacer(Modifier.width(12.dp))
                                    Label(dateText(now), P.Ink, size = 17)
                                }
                            }
                        }
                        Cards(cfg, apps, battery, selected = zone == 1)
                    }
                    Dock(pins, look.dockSize.dp, if (zone == 0) dock else -1)
                    HintBar(
                        if (zone == 1) listOf("A" to "Continuar", "B" to "Volver")
                        else listOf("A" to "Abrir", "X" to "Opciones", "Y" to "Buscar", "RS" to "Mover", "START" to "Personalizar"),
                        left = "${apps.size} apps",
                    )
                } else {
                    WidgetPage(cfg, Modifier.weight(1f))
                    HintBar(listOf("A" to "Opciones", "B" to "Inicio"), left = "${cfg.widgets.size} widgets")
                }
            }
        }
    }

    @Composable
    private fun Cards(cfg: Config, apps: List<AppEntry>, battery: Battery, selected: Boolean) {
        val look = cfg.look
        val accent = LocalAccent.current
        val stats by Env.stats.flow.collectAsState()
        val last = remember(stats, apps) { apps.filter { (stats.usage[it.key]?.last ?: 0) > 0 }.maxByOrNull { stats.usage[it.key]!!.last } }
        Column(Modifier.width(400.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (look.cardContinue && last != null) {
                val usage = stats.usage[last.key]!!
                val game = last.categoryIn(cfg).let { it == Cat.JUEGOS || it == Cat.EMULADORES }
                Panel(Modifier.fillMaxWidth().brackets(selected, accent).tap { Actions.launch(last) }) {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                        Label(if (game) "01 — Seguir jugando" else "01 — Continuar")
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(last, 54.dp)
                            Spacer(Modifier.width(14.dp))
                            Column {
                                T(last.labelIn(cfg), 19, weight = FontWeight.Medium)
                                Spacer(Modifier.height(3.dp))
                                T(
                                    ago(usage.last) + if (usage.lastSessionMs > 0) " · sesión de ${duration(usage.lastSessionMs)}" else "",
                                    13, P.Dim, mono = true,
                                )
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (look.cardBattery && battery.percent >= 0) {
                    Panel(Modifier.weight(1f)) {
                        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                            Label("02 — Batería")
                            Spacer(Modifier.height(8.dp))
                            T("${battery.percent} %", 28, weight = FontWeight.Medium)
                            Spacer(Modifier.height(10.dp))
                            Segments(battery.percent / 100f, accent, Modifier.fillMaxWidth(), count = 12)
                        }
                    }
                }
                if (look.cardStorage) {
                    val (free, total) = remember { Actions.storage() }
                    if (total > 0) Panel(Modifier.weight(1f)) {
                        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                            Label("03 — Libre")
                            Spacer(Modifier.height(8.dp))
                            T("${free / 1_000_000_000} GB", 28, weight = FontWeight.Medium)
                            Spacer(Modifier.height(10.dp))
                            Segments(1f - free.toFloat() / total, P.Ink, Modifier.fillMaxWidth(), count = 12)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Dock(pins: List<AppEntry>, size: Dp, selected: Int) {
        val state = rememberLazyListState()
        LaunchedEffect(selected) { if (selected >= 0) state.animateScrollToItem((selected - 3).coerceAtLeast(0)) }
        LazyRow(
            Modifier.fillMaxWidth().height(size + 84.dp), state = state,
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp, Alignment.CenterHorizontally),
        ) {
            itemsIndexed(pins, key = { _, a -> a.key }) { i, app ->
                AppTile(
                    app, size, selected == i,
                    onTap = { dock = i; zone = 0; Actions.launch(app) },
                    onLong = { dock = i; zone = 0; Env.nav.push(appMenu(app)) },
                )
            }
            item(key = "todas") {
                val on = selected == pins.size
                Column(
                    Modifier.width(size + 44.dp).tap { Env.nav.push(DrawerScreen()) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.size(size).brackets(on, LocalAccent.current).border(1.5.dp, if (on) P.Ink else P.Line, chamfer(18.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            repeat(3) { Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) { repeat(3) { Dot(if (on) P.Ink else P.Dim, 5.dp) } } }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    T("Todas", 14, if (on) P.Ink else P.Dim, if (on) FontWeight.Medium else FontWeight.Normal)
                }
            }
        }
    }

    @Composable
    private fun WidgetPage(cfg: Config, modifier: Modifier) {
        val state = rememberLazyListState()
        LaunchedEffect(widget) { state.animateScrollToItem((widget - 1).coerceAtLeast(0)) }
        LazyRow(
            modifier.fillMaxWidth().padding(vertical = 22.dp), state = state,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(26.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            itemsIndexed(cfg.widgets, key = { _, w -> w.id }) { i, slot ->
                Box(Modifier.width((170 * slot.cols).dp).fillMaxHeight().brackets(widget == i, LocalAccent.current)) { WidgetView(slot) }
            }
            item(key = "add") {
                val on = widget == cfg.widgets.size
                Box(
                    Modifier.width(240.dp).fillMaxHeight().brackets(on, LocalAccent.current)
                        .border(1.5.dp, if (on) P.Ink else P.Line, chamfer(22.dp)).tap { Env.nav.push(WidgetPicker()) },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        T("+", 54, if (on) LocalAccent.current else P.Dim, FontWeight.Light)
                        Label("Añadir widget", if (on) P.Ink else P.Dim)
                    }
                }
            }
        }
    }
}

/** Widget real de Android dentro de la composición. Su contenido responde al toque (o al puntero). */
@Composable
fun WidgetView(slot: WidgetSlot) {
    val ctx = LocalContext.current
    val info = remember(slot.id) { Env.widgets.info(slot.id) }
    if (info == null) {
        Panel(Modifier.fillMaxSize()) { Label("Widget no disponible", modifier = Modifier.align(Alignment.Center)) }
        return
    }
    AndroidView(
        factory = { Env.widgets.host.createView(ctx, slot.id, info) },
        modifier = Modifier.fillMaxSize(),
        update = { view: AppWidgetHostView ->
            view.post {
                // El widget espera su tamaño en dp reales del sistema, no en unidades de diseño.
                val real = ctx.resources.displayMetrics.density
                val w = (view.width / real).toInt(); val h = (view.height / real).toInt()
                if (w > 0 && h > 0) runCatching { @Suppress("DEPRECATION") view.updateAppWidgetSize(null, w, h, w, h) }
            }
        },
    )
}
