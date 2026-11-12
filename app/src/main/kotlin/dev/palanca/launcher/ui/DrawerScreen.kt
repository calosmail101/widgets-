package dev.palanca.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.palanca.launcher.Env
import dev.palanca.launcher.data.AppEntry
import dev.palanca.launcher.data.Cat
import dev.palanca.launcher.data.Config
import dev.palanca.launcher.data.categoryIn
import dev.palanca.launcher.input.Btn
import dev.palanca.launcher.input.PadEvent
import dev.palanca.launcher.system.Actions

/** Pestañas del cajón: 0 = todas, 1..5 = categorías, 6 = ocultas (solo si hay alguna). */
fun drawerTabs(cfg: Config): List<String> =
    listOf("Todas") + Cat.entries.map { it.title } + if (cfg.hidden.isEmpty()) emptyList() else listOf("Ocultas")

fun drawerItems(cfg: Config, apps: List<AppEntry>, tab: Int): List<AppEntry> = when (tab) {
    0 -> apps.filter { it.key !in cfg.hidden }
    in 1..Cat.entries.size -> apps.filter { it.key !in cfg.hidden && it.categoryIn(cfg) == Cat.entries[tab - 1] }
    else -> apps.filter { it.key in cfg.hidden }
}

/** Mueve la selección por una rejilla de [cols] columnas y [count] elementos. */
fun gridMove(sel: Int, count: Int, cols: Int, btn: Btn, rows: Int = 1): Int {
    if (count == 0) return 0
    return when (btn) {
        Btn.LEFT -> if (sel % cols > 0) sel - 1 else sel
        Btn.RIGHT -> if (sel % cols < cols - 1 && sel + 1 < count) sel + 1 else sel
        Btn.UP -> if (sel - cols * rows >= 0) sel - cols * rows else sel % cols
        Btn.DOWN -> {
            val target = sel + cols * rows
            val lastRow = (count - 1) / cols
            when {
                target < count -> target
                sel / cols < lastRow -> count - 1
                else -> sel
            }
        }
        else -> sel
    }.coerceIn(0, count - 1)
}

class DrawerScreen : Screen {
    private var tab by mutableIntStateOf(0)
    private var sel by mutableIntStateOf(0)

    override fun onPad(e: PadEvent): Boolean {
        val cfg = Env.cfg.value
        val tabs = drawerTabs(cfg)
        tab = tab.coerceIn(0, tabs.lastIndex)
        val items = drawerItems(cfg, Env.apps.apps.value, tab)
        sel = sel.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        val app = items.getOrNull(sel)
        val cols = cfg.look.drawerCols
        when (e.btn) {
            Btn.L1 -> if (!e.long) { tab = (tab - 1 + tabs.size) % tabs.size; sel = 0 }
            Btn.R1 -> if (!e.long) { tab = (tab + 1) % tabs.size; sel = 0 }
            Btn.UP, Btn.DOWN, Btn.LEFT, Btn.RIGHT -> sel = gridMove(sel, items.size, cols, e.btn)
            Btn.RS_UP -> sel = gridMove(sel, items.size, cols, Btn.UP, 3)
            Btn.RS_DOWN -> sel = gridMove(sel, items.size, cols, Btn.DOWN, 3)
            Btn.A -> if (app != null) { if (e.long) Env.nav.push(appMenu(app)) else Actions.launch(app) }
            Btn.X -> if (app != null && !e.long) Env.nav.push(appMenu(app))
            Btn.SELECT -> if (e.long) return false else if (app != null) togglePin(app)
            Btn.Y -> if (!e.long) Env.nav.push(KeyboardScreen.search())
            Btn.START -> if (e.long) return false else Env.nav.push(SettingsScreen())
            Btn.B -> Env.nav.pop()
            else -> return false
        }
        return true
    }

    @Composable
    override fun Content() {
        val cfg = LocalCfg.current
        val accent = LocalAccent.current
        val apps by Env.apps.apps.collectAsState()
        val tabs = drawerTabs(cfg)
        val current = tab.coerceIn(0, tabs.lastIndex)
        val items = remember(cfg.hidden, cfg.categories, apps, current) { drawerItems(cfg, apps, current) }
        val cols = cfg.look.drawerCols
        val iconSize = if (cols <= 7) 84.dp else if (cols == 8) 76.dp else 68.dp
        val grid = rememberLazyGridState()
        LaunchedEffect(sel, current) { if (items.isNotEmpty()) grid.animateScrollToItem((sel - cols).coerceAtLeast(0)) }

        Column(Modifier.fillMaxSize().background(P.Bg).padding(start = 56.dp, end = 56.dp, top = 30.dp, bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph("L1")
                Spacer(Modifier.width(14.dp))
                tabs.forEachIndexed { i, name ->
                    val on = i == current
                    Box(
                        Modifier.height(38.dp).background(if (on) accent else Color.Transparent, chamfer(10.dp))
                            .tap { tab = i; sel = 0 }.padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) { T(name.uppercase(), 13, if (on) P.OnAccent else P.Dim, FontWeight.Bold, mono = true, spacing = 1f) }
                }
                Spacer(Modifier.width(14.dp))
                Glyph("R1")
                Spacer(Modifier.weight(1f))
                Row(Modifier.tap { Env.nav.push(KeyboardScreen.search()) }, verticalAlignment = Alignment.CenterVertically) {
                    Label("Buscar"); Spacer(Modifier.width(10.dp)); Glyph("Y")
                }
            }
            Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(1.dp).background(P.Line))
            if (items.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Label("No hay apps en esta categoría") }
            } else {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    LazyVerticalGrid(
                        GridCells.Fixed(cols), Modifier.fillMaxSize(), state = grid,
                        contentPadding = PaddingValues(top = 30.dp, bottom = 40.dp),
                        verticalArrangement = Arrangement.spacedBy(52.dp),
                    ) {
                        itemsIndexed(items, key = { _, a -> a.key }) { i, app ->
                            Box(contentAlignment = Alignment.Center) {
                                AppTile(
                                    app, iconSize, i == sel,
                                    onTap = { sel = i; Actions.launch(app) },
                                    onLong = { sel = i; Env.nav.push(appMenu(app)) },
                                )
                            }
                        }
                    }
                    // La fila que asoma por abajo se funde con el fondo: indica que hay más sin cortar en seco.
                    Box(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(64.dp)
                            .background(Brush.verticalGradient(listOf(Color.Transparent, P.Bg)))
                    )
                }
            }
            val row = if (items.isEmpty()) 0 else sel / cols + 1
            val rows = (items.size + cols - 1) / cols
            HintBar(
                listOf("A" to "Abrir", "B" to "Volver", "X" to "Opciones", "SELECT" to "Fijar en Inicio"),
                left = "${items.size} apps · fila $row de $rows",
            )
        }
    }
}

fun togglePin(app: AppEntry) {
    val pinned = app.key in Env.cfg.value.pinned
    Env.cfg.update { c -> c.copy(pinned = if (pinned) c.pinned - app.key else c.pinned + app.key) }
    Actions.toast(if (pinned) "${app.label} quitada de Inicio" else "${app.label} fijada en Inicio")
}
