package dev.palanca.launcher.ui

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.palanca.launcher.Env
import dev.palanca.launcher.data.AppEntry
import dev.palanca.launcher.data.Backup
import dev.palanca.launcher.data.IconOverride
import dev.palanca.launcher.data.labelIn
import dev.palanca.launcher.icons.ICON_SHAPES
import dev.palanca.launcher.icons.IconPack
import dev.palanca.launcher.icons.PACK_ORIGINAL
import dev.palanca.launcher.input.Btn
import dev.palanca.launcher.input.PadEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val COLS = 5

/** Cambia el icono, la forma, el tamaño y el nombre de una app. */
class IconEditorScreen(private val app: AppEntry) : Screen {
    private val packs: List<IconPack> = listOf(IconPack(PACK_ORIGINAL, "Icono de fábrica")) + Env.icons.packs.list()
    private var pack by mutableIntStateOf(
        packs.indexOfFirst { it.pkg == (Env.cfg.value.icons[app.key]?.pack ?: Env.cfg.value.look.iconPack) }.coerceAtLeast(0)
    )
    private var zone by mutableIntStateOf(0) // 0 = opciones, 1 = iconos del paquete
    private var option by mutableIntStateOf(0)
    private var pick by mutableIntStateOf(0)
    private var candidates by mutableStateOf<List<String>>(emptyList())

    private fun edit(f: IconOverride.() -> IconOverride) = Env.cfg.update { it.icon(app.key, f) }
    private fun current() = Env.cfg.value.icons[app.key] ?: IconOverride()

    private fun cyclePack(dir: Int) { pack = (pack + dir + packs.size) % packs.size; pick = 0 }

    private fun cycleShape(dir: Int) {
        val ids = listOf<String?>(null) + ICON_SHAPES.map { it.first }
        val next = ids[(ids.indexOf(current().shape) + dir + ids.size) % ids.size]
        edit { copy(shape = next) }
    }

    private fun resize(dir: Int) = edit { copy(scale = ((scale * 100 + 0.5f).toInt() + dir * 5).coerceIn(60, 140) / 100f) }

    private fun activate() {
        when (option) {
            0 -> cyclePack(+1)
            1 -> cycleShape(+1)
            2 -> resize(+1)
            3 -> Env.nav.push(KeyboardScreen.text("Nombre de ${app.label}", app.labelIn(Env.cfg.value)) { t ->
                edit { copy(label = t.takeIf { it.isNotBlank() && it != app.label }) }
            })
            4 -> Backup.pickIcon(app.key)
            5 -> Env.cfg.update { it.copy(icons = it.icons - app.key) }
        }
    }

    private fun apply() {
        val p = packs[pack]
        if (p.pkg == PACK_ORIGINAL) edit { copy(pack = PACK_ORIGINAL, drawable = null, file = null) }
        else candidates.getOrNull(pick)?.let { name -> edit { copy(pack = p.pkg, drawable = name, file = null) } }
    }

    override fun onPad(e: PadEvent): Boolean {
        if (e.long) return true
        val count = if (packs[pack].pkg == PACK_ORIGINAL) 1 else candidates.size
        when (e.btn) {
            Btn.B -> if (zone == 1) zone = 0 else Env.nav.pop()
            Btn.L1 -> cyclePack(-1)
            Btn.R1 -> cyclePack(+1)
            Btn.RS_LEFT -> resize(-1)
            Btn.RS_RIGHT -> resize(+1)
            Btn.A -> if (zone == 0) activate() else apply()
            Btn.UP -> if (zone == 0) option = (option + 5) % 6 else pick = gridMove(pick, count, COLS, Btn.UP)
            Btn.DOWN -> if (zone == 0) option = (option + 1) % 6 else pick = gridMove(pick, count, COLS, Btn.DOWN)
            Btn.RS_UP -> if (zone == 1) pick = gridMove(pick, count, COLS, Btn.UP, 3)
            Btn.RS_DOWN -> if (zone == 1) pick = gridMove(pick, count, COLS, Btn.DOWN, 3)
            Btn.LEFT -> when {
                zone == 1 -> if (pick % COLS == 0) zone = 0 else pick--
                option == 0 -> cyclePack(-1)
                option == 1 -> cycleShape(-1)
                option == 2 -> resize(-1)
            }
            Btn.RIGHT -> when {
                zone == 1 -> pick = gridMove(pick, count, COLS, Btn.RIGHT)
                option == 0 -> cyclePack(+1)
                option == 1 -> cycleShape(+1)
                option == 2 -> resize(+1)
                count > 0 -> zone = 1
            }
            Btn.X -> if (count > 0) zone = 1 - zone
            else -> Unit
        }
        return true
    }

    @Composable
    override fun Content() {
        val cfg = LocalCfg.current
        val accent = LocalAccent.current
        val o = cfg.icons[app.key] ?: IconOverride()
        val p = packs[pack.coerceIn(0, packs.lastIndex)]
        val shape = o.shape ?: cfg.look.iconShape
        LaunchedEffect(p.pkg) {
            candidates = if (p.pkg == PACK_ORIGINAL) emptyList() else withContext(Dispatchers.IO) {
                // Primero el icono que el paquete propone para esta app; después, todos.
                val suggested = Env.icons.packs.mapping(p.pkg)[app.key]
                (listOfNotNull(suggested) + Env.icons.packs.allDrawables(p.pkg)).distinct()
            }
        }
        val grid = rememberLazyGridState()
        LaunchedEffect(pick, p.pkg) { if (candidates.isNotEmpty()) grid.animateScrollToItem((pick - COLS).coerceAtLeast(0)) }

        Frame(
            "X", "Editar icono", "${app.label} · los cambios se guardan al momento",
            listOf("A" to if (zone == 0) "Cambiar" else "Usar este icono", "X" to "Opciones / iconos", "L1 R1" to "Paquete", "RS" to "Tamaño", "B" to "Volver"),
        ) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                Panel(Modifier.width(230.dp).fillMaxHeight()) {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        AppIcon(app, 136.dp)
                        Spacer(Modifier.height(30.dp))
                        T(app.labelIn(cfg), 19, weight = FontWeight.Medium)
                        Spacer(Modifier.height(8.dp))
                        Label("Vista previa")
                    }
                }
                Column(Modifier.width(360.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val shapeName = o.shape?.let { id -> ICON_SHAPES.firstOrNull { it.first == id }?.second } ?: "Como el resto"
                    val rows = listOf(
                        Triple("Paquete", p.name, true), Triple("Forma", shapeName, true),
                        Triple("Tamaño", "${(o.scale * 100 + 0.5f).toInt()} %", true), Triple("Nombre", app.labelIn(cfg), false),
                        Triple("Imagen de la galería", if (o.file != null) "en uso" else "elegir", false),
                        Triple("Restablecer original", "", false),
                    )
                    rows.forEachIndexed { i, (title, value, arrows) ->
                        ListRow(title, value, zone == 0 && option == i, Modifier.tap { zone = 0; option = i; activate() }, arrows = arrows)
                    }
                }
                Panel(Modifier.weight(1f).fillMaxHeight()) {
                    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
                        Label(if (p.pkg == PACK_ORIGINAL) "Icono de fábrica" else "${p.name} · ${candidates.size} iconos")
                        if (p.pkg == PACK_ORIGINAL) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(Modifier.brackets(zone == 1, accent).tap { zone = 1; apply() }) {
                                        PackIcon(null, "", shape, app)
                                    }
                                    Spacer(Modifier.height(26.dp))
                                    T(
                                        if (packs.size == 1) "No hay paquetes de iconos instalados.\nInstala uno desde Play Store y aparecerá aquí."
                                        else "Pulsa L1 / R1 para ver tus paquetes de iconos.",
                                        14, P.Dim, maxLines = 3, align = androidx.compose.ui.text.style.TextAlign.Center,
                                    )
                                }
                            }
                        } else LazyVerticalGrid(
                            GridCells.Fixed(COLS), Modifier.fillMaxSize(), state = grid,
                            contentPadding = PaddingValues(top = 22.dp, bottom = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(24.dp),
                        ) {
                            itemsIndexed(candidates, key = { _, n -> n }) { i, name ->
                                Box(contentAlignment = Alignment.Center) {
                                    Box(Modifier.brackets(zone == 1 && i == pick, accent).tap { zone = 1; pick = i; apply() }) {
                                        PackIcon(p.pkg, name, shape, app)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Un icono suelto de un paquete (o el de fábrica de [app] si [pack] es null). */
@Composable
private fun PackIcon(pack: String?, name: String, shape: String, app: AppEntry) {
    val size = 72.dp
    val px = with(LocalDensity.current) { size.roundToPx() }
    var bitmap by remember(pack, name, shape) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(pack, name, shape) {
        bitmap = withContext(Dispatchers.Default) {
            if (pack != null) Env.icons.renderPackIcon(pack, name, shape, px)
            else {
                val factory = Env.cfg.value.let { c -> c.copy(icons = c.icons + (app.key to IconOverride(pack = PACK_ORIGINAL, shape = shape))) }
                Env.icons.render("factory|${app.key}|$shape|$px", app, factory, px)
            }
        }
    }
    Box(Modifier.size(size)) { bitmap?.let { Image(BitmapPainter(it), null, Modifier.fillMaxSize()) } }
}
