package dev.palanca.launcher.ui

import android.appwidget.AppWidgetHostView
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
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

/** Toque (y toque largo opcional) sin efectos visuales: el foco ya lo marca el anillo. */
fun Modifier.tap(onLong: (() -> Unit)? = null, onTap: () -> Unit): Modifier = composed {
    val tapNow by rememberUpdatedState(onTap)
    val longNow by rememberUpdatedState(onLong)
    pointerInput(Unit) { detectTapGestures(onTap = { tapNow() }, onLongPress = { longNow?.invoke() }) }
}

/** Icono + etiqueta con anillo de foco. La pieza del cajón y de los resultados de búsqueda. */
@Composable
fun AppTile(
    app: AppEntry, size: Dp, selected: Boolean, modifier: Modifier = Modifier,
    onTap: () -> Unit = {}, onLong: () -> Unit = {},
) {
    val cfg = LocalCfg.current
    Column(modifier.width(size + 44.dp).tap(onLong, onTap), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.focusScale(selected).iconFocus(selected, iconShape(cfg, app), LocalAccent.current)) { AppIcon(app, size) }
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

/** Elige la app que abre el botón Jugar: primero frontends, emuladores y juegos; después el resto. */
fun playAppPicker() = MenuScreen("JUGAR", "¿Qué abre el botón Jugar?") {
    val cfg = Env.cfg.value
    val apps = Env.apps.apps.value.filter { it.key !in cfg.hidden }
    val (games, rest) = apps.partition { it.categoryIn(cfg).let { c -> c == Cat.EMULADORES || c == Cat.JUEGOS } }
    (games + rest).map { app ->
        MenuItem(app.labelIn(cfg), if (app.key == cfg.playApp) "actual" else "") { Env.cfg.update { it.copy(playApp = app.key) } }
    }
}

class HomeScreen : Screen {
    private var page by mutableIntStateOf(0)
    private var zone by mutableIntStateOf(0) // 0 = carrusel · 1 = tarjetas · 2 = botón Jugar
    private var dock by mutableIntStateOf(0)
    private var card by mutableIntStateOf(0)
    private var widget by mutableIntStateOf(0)

    fun reset() { page = 0; zone = 0 }

    private fun pins() = pinnedApps(Env.cfg.value, Env.apps.apps.value)
    private fun playApp(cfg: Config) = Env.apps.apps.value.firstOrNull { it.key == cfg.playApp }

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

    /** La estadística con foco se ha movido: la selección la sigue. */
    private fun followStat(index: Int) {
        val cfg = Env.cfg.value
        card = index + if (cfg.look.cardContinue && Actions.lastApp() != null) 1 else 0
    }

    private fun homePad(e: PadEvent, cfg: Config): Boolean {
        val pins = pins()
        dock = dock.coerceIn(0, pins.size)
        val app = pins.getOrNull(dock)
        // Tarjetas: [continuar] + estadísticas en rejilla.
        val stats = statList(cfg)
        val offset = if (cfg.look.cardContinue && Actions.lastApp() != null) 1 else 0
        val total = offset + stats.size
        if (zone == 1 && total == 0) zone = 2
        card = card.coerceIn(0, (total - 1).coerceAtLeast(0))
        val cols = statCols(statSize(cfg.look, stats.size))
        val stat = card - offset // -1 = tarjeta "continuar"

        when (zone) {
            2 -> when (e.btn) {
                Btn.RIGHT -> if (total > 0) zone = 1
                Btn.DOWN, Btn.B -> zone = 0
                Btn.A -> playApp(cfg)?.let(Actions::launch) ?: Env.nav.push(playAppPicker())
                Btn.X -> Env.nav.push(playAppPicker())
                Btn.UP, Btn.LEFT -> Unit
                else -> return false
            }
            1 -> when (e.btn) {
                Btn.UP -> if (stat >= cols) card -= cols else if (stat >= 0 && offset == 1) card = 0
                Btn.DOWN -> when {
                    stat < 0 -> if (stats.isNotEmpty()) card = 1 else zone = 0
                    stat + cols < stats.size -> card += cols
                    stat / cols < (stats.size - 1) / cols -> card = total - 1
                    else -> zone = 0
                }
                // Desde la columna izquierda de las tarjetas se pasa al botón Jugar.
                Btn.LEFT -> if (stat < 0 || stat % cols == 0) zone = 2 else card--
                Btn.RIGHT -> if (stat >= 0) card = offset + gridMove(stat, stats.size, cols, Btn.RIGHT)
                Btn.A, Btn.X -> if (stat < 0) Actions.lastApp()?.let(Actions::launch) else Env.nav.push(statMenu(stats[stat], ::followStat))
                Btn.B -> zone = 0
                else -> return false
            }
            else -> when (e.btn) {
                Btn.LEFT -> dock = (dock - 1).coerceAtLeast(0)
                Btn.RIGHT -> dock = (dock + 1).coerceAtMost(pins.size)
                Btn.UP -> zone = 2
                Btn.DOWN -> Env.nav.push(DrawerScreen())
                Btn.A -> if (app != null) Actions.launch(app, 0.5f to 0.78f) else Env.nav.push(DrawerScreen())
                Btn.X -> if (app != null) Env.nav.push(appMenu(app))
                Btn.RS_LEFT, Btn.RS_RIGHT -> if (app != null) {
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
                else -> return false
            }
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
            Column(Modifier.fillMaxSize().padding(start = 56.dp, end = 56.dp, top = 26.dp, bottom = 20.dp)) {
                // Cabecera: páginas a la izquierda, conectividad y batería a la derecha.
                Row(Modifier.fillMaxWidth().height(38.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf("Inicio", "Widgets").forEachIndexed { i, name ->
                        val on = i == page
                        Box(
                            Modifier.height(36.dp).background(if (on) P.Panel else Color.Transparent, CircleShape)
                                .tap { page = i; zone = 0 }.padding(horizontal = 18.dp),
                            contentAlignment = Alignment.Center,
                        ) { T(name, 15, if (on) P.Ink else P.Dim, if (on) FontWeight.Medium else FontWeight.Normal) }
                        Spacer(Modifier.width(6.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Glyph("L1"); Spacer(Modifier.width(6.dp)); Glyph("R1")
                    Spacer(Modifier.weight(1f))
                    val foreground by Env.foreground.collectAsState()
                    val (wifi, bluetooth) = remember(foreground) {
                        fun on(key: String) = runCatching { Settings.Global.getInt(Env.ctx.contentResolver, key) != 0 }.getOrDefault(false)
                        on("wifi_on") to on("bluetooth_on")
                    }
                    Row(Modifier.tap { Env.nav.push(QuickPanel()) }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusPill("Wi‑Fi", wifi)
                        StatusPill("Bluetooth", bluetooth)
                        if (battery.percent >= 0) StatusPill("${battery.percent} %" + if (battery.charging) " · cargando" else "", true)
                    }
                }

                if (page == 0) {
                    val pins = remember(cfg.pinned, apps) { pinnedApps(cfg, apps) }
                    Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        val now by rememberNow(look.clockSeconds)
                        Column(Modifier.weight(1f)) {
                            ClockView(look, if (look.clockSeconds) 104.dp else 138.dp, now)
                            if (look.clockDate) {
                                Spacer(Modifier.height(10.dp))
                                T(dateText(now).replaceFirstChar { it.uppercase() }, 21, P.Dim)
                            }
                            Spacer(Modifier.height(26.dp))
                            PlayButton(cfg, apps.firstOrNull { it.key == cfg.playApp }, zone == 2)
                        }
                        Cards(cfg, apps, if (zone == 1) card else -1)
                    }
                    Carousel(pins, look.dockSize.dp, dock.coerceIn(0, pins.size), zone == 0)
                    HintBar(
                        when (zone) {
                            1 -> listOf("A" to "Abrir / cambiar", "B" to "Volver")
                            2 -> listOf("A" to "Jugar", "X" to "Elegir app", "B" to "Volver")
                            else -> listOf("A" to "Abrir", "X" to "Opciones", "Y" to "Buscar", "SELECT" to "Panel", "START" to "Personalizar")
                        },
                    )
                } else {
                    WidgetPage(cfg, Modifier.weight(1f))
                    HintBar(listOf("A" to "Opciones", "B" to "Inicio"), left = "${cfg.widgets.size} widgets")
                }
            }
        }
    }

    @Composable
    private fun StatusPill(text: String, on: Boolean) {
        Row(
            Modifier.height(36.dp).background(P.Panel, CircleShape).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Dot(if (on) LocalAccent.current else P.Line, 8.dp)
            Spacer(Modifier.width(9.dp))
            T(text, 14, if (on) P.Ink else P.Dim)
        }
    }

    @Composable
    private fun PlayButton(cfg: Config, app: AppEntry?, selected: Boolean) {
        val accent = LocalAccent.current
        Row(
            Modifier.height(60.dp).brackets(selected, P.Ink, gap = 4.dp, radius = 30.dp).background(accent, CircleShape)
                .tap(onLong = { Env.nav.push(playAppPicker()) }) {
                    zone = 2
                    app?.let(Actions::launch) ?: Env.nav.push(playAppPicker())
                }.padding(start = 10.dp, end = 26.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (app != null) AppIcon(app, 40.dp) else Spacer(Modifier.width(14.dp))
            Spacer(Modifier.width(12.dp))
            T("Jugar", 19, P.OnAccent, FontWeight.Bold)
            Spacer(Modifier.width(10.dp))
            T(app?.labelIn(cfg) ?: "elige tu frontend", 15, P.OnAccent.copy(alpha = 0.78f))
        }
    }

    @Composable
    private fun Cards(cfg: Config, apps: List<AppEntry>, selected: Int) {
        val look = cfg.look
        val accent = LocalAccent.current
        val stats by Env.stats.flow.collectAsState()
        val last = remember(stats, apps) { apps.filter { (stats.usage[it.key]?.last ?: 0) > 0 }.maxByOrNull { stats.usage[it.key]!!.last } }
        val showContinue = look.cardContinue && last != null
        val offset = if (showContinue) 1 else 0
        Column(Modifier.width(430.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (showContinue && last != null) {
                val usage = stats.usage[last.key]!!
                val game = last.categoryIn(cfg).let { it == Cat.JUEGOS || it == Cat.EMULADORES }
                Panel(Modifier.fillMaxWidth().brackets(selected == 0, accent, radius = 22.4.dp).tap { Actions.launch(last) }) {
                    Row(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(last, 56.dp)
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Label(if (game) "Seguir jugando" else "Continuar")
                            Spacer(Modifier.height(4.dp))
                            T(last.labelIn(cfg), 20, weight = FontWeight.Medium)
                            Spacer(Modifier.height(2.dp))
                            T(ago(usage.last) + if (usage.lastSessionMs > 0) " · sesión de ${duration(usage.lastSessionMs)}" else "", 13, P.Dim)
                        }
                    }
                }
            }
            val metrics = remember(look.stats) { statList(cfg) }
            StatsGrid(metrics, look, if (selected >= offset) selected - offset else -1, firstNumber = offset + 1) { i ->
                zone = 1; card = offset + i; Env.nav.push(statMenu(metrics[i], ::followStat))
            }
        }
    }

    /**
     * Carrusel de apps fijadas: una sola fila en la que la app enfocada queda siempre en el centro,
     * más grande y con su nombre; las demás se deslizan a los lados, atenuadas.
     */
    @Composable
    private fun Carousel(pins: List<AppEntry>, size: Dp, selected: Int, active: Boolean) {
        val cfg = LocalCfg.current
        val slot = size + 34.dp
        BoxWithConstraints(Modifier.fillMaxWidth().height(size * 1.3f + 62.dp)) {
            val state = rememberLazyListState()
            LaunchedEffect(selected) { state.animateScrollToItem(selected) }
            LazyRow(
                Modifier.fillMaxSize(), state = state,
                // El relleno lateral hace que el primer y el último elemento también puedan centrarse.
                contentPadding = PaddingValues(horizontal = (maxWidth - slot) / 2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                itemsIndexed(pins, key = { _, a -> a.key }) { i, app ->
                    val on = i == selected
                    Column(
                        Modifier.width(slot).tap(onLong = { dock = i; zone = 0; Env.nav.push(appMenu(app)) }) {
                            if (dock == i && zone == 0) Actions.launch(app) else { dock = i; zone = 0 }
                        },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier.focusScale(on, 1.3f).iconFocus(on && active, iconShape(cfg, app), LocalAccent.current)
                                .alpha(if (on) 1f else 0.62f)
                        ) { AppIcon(app, size) }
                        Spacer(Modifier.height(size * 0.15f + 14.dp))
                        T(app.labelIn(cfg), 16, if (on) P.Ink else Color.Transparent, FontWeight.Medium, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    }
                }
                item(key = "todas") {
                    val on = selected == pins.size
                    Column(Modifier.width(slot).tap { Env.nav.push(DrawerScreen()) }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(size).focusScale(on, 1.3f).brackets(on && active, LocalAccent.current, radius = size * 0.28f)
                                .alpha(if (on) 1f else 0.62f).border(1.5.dp, if (on) P.Ink else P.Dim, chamfer(size * 0.2f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                repeat(3) { Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) { repeat(3) { Dot(if (on) P.Ink else P.Dim, 5.dp) } } }
                            }
                        }
                        Spacer(Modifier.height(size * 0.15f + 14.dp))
                        T("Todas las apps", 16, if (on) P.Ink else Color.Transparent, FontWeight.Medium, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    }
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
