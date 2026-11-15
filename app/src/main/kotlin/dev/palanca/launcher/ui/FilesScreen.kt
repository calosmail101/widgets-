package dev.palanca.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.palanca.launcher.Env
import dev.palanca.launcher.data.Files
import dev.palanca.launcher.input.Btn
import dev.palanca.launcher.input.PadEvent
import dev.palanca.launcher.system.Actions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Un panel del explorador: carpeta actual, foco y selección propios. */
private class Pane(start: File) {
    var dir by mutableStateOf(start)
    var items by mutableStateOf(emptyList<File>())
    var sel by mutableIntStateOf(0)
    var marked by mutableStateOf(emptySet<File>())
    /** Al subir de carpeta, el foco vuelve a la carpeta de la que se venía. */
    fun load(focus: File? = null, hidden: Boolean = false) {
        items = Files.list(dir, hidden)
        sel = focus?.let { f -> items.indexOfFirst { it.absolutePath == f.absolutePath } }?.takeIf { it >= 0 } ?: sel.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        marked = marked.filter { it.exists() && it.parentFile == dir }.toSet()
    }
    fun current(): File? = items.getOrNull(sel)
    /** Lo marcado con Y; si no hay nada marcado, el elemento con el foco. */
    fun targets(): List<File> = marked.toList().ifEmpty { listOfNotNull(current()) }
}

/**
 * Explorador de archivos a dos paneles, pensado para el mando.
 * Cruceta: moverse · A: abrir · B: subir · L1/R1: cambiar de panel · Y: marcar · X: acciones ·
 * START: lugares · SELECT: limpieza. Borrar siempre manda a la papelera.
 */
/** [origin]: punto de la pantalla del que nace y al que vuelve la ventana (el botón que la abrió). */
class FilesScreen(private val origin: TransformOrigin = TransformOrigin(0.5f, 0.5f)) : Screen, Closable {
    override val overlay get() = true
    override var closing by mutableStateOf(false)
    private val panes = listOf(Pane(Files.internal), Pane(Files.sd() ?: Files.internal))
    private var side by mutableIntStateOf(0)
    private var hidden by mutableStateOf(false)
    private var busy by mutableStateOf<String?>(null)
    private var progress by mutableStateOf(0f)
    private var job: Job? = null
    private var cancelled = false

    private val pane get() = panes[side]
    private val other get() = panes[1 - side]

    init {
        if (!Files.hasAccess()) Files.requestAccess()
        reloadAll()
        Env.scope.launch(Dispatchers.IO) { Files.purgeTrash(30) }
    }

    private fun reloadAll() = panes.forEach { it.load(hidden = hidden) }

    private fun enter(f: File) {
        if (f.isDirectory) { pane.dir = f; pane.sel = 0; pane.marked = emptySet(); pane.load(hidden = hidden) }
        else Files.open(f)
    }

    private fun up(): Boolean {
        val parent = pane.dir.parentFile ?: return false
        // No se sube por encima de la raíz de cada volumen (/storage/emulated/0, /storage/XXXX-XXXX).
        if (Files.roots().any { it.second.absolutePath == pane.dir.absolutePath }) return false
        val from = pane.dir
        pane.dir = parent; pane.marked = emptySet(); pane.load(from, hidden)
        return true
    }

    /** Copiar o mover lo marcado al otro panel, en segundo plano y con progreso. */
    private fun transfer(move: Boolean) {
        val sources = pane.targets()
        if (sources.isEmpty() || busy != null) return
        val dest = other.dir
        val warn = sources.firstNotNullOfOrNull { Files.warning(it) }
        val run = {
            cancelled = false
            busy = (if (move) "Moviendo" else "Copiando") + " ${sources.size} elemento(s)"
            job = Env.scope.launch {
                val errors = withContext(Dispatchers.IO) {
                    Files.transfer(sources, dest, move, { cancelled }) { done, total, name ->
                        progress = if (total > 0) done.toFloat() / total else 1f
                        busy = (if (move) "Moviendo " else "Copiando ") + name
                    }
                }
                busy = null; progress = 0f; pane.marked = emptySet(); reloadAll()
                Actions.toast(when { cancelled -> "Cancelado"; errors.isEmpty() -> if (move) "Movido" else "Copiado"; else -> errors.first() })
            }
        }
        if (move && warn != null) Env.nav.push(confirm("¿Mover de todos modos?", warn, "Mover") { run() }) else run()
    }

    private fun trash() {
        val files = pane.targets()
        if (files.isEmpty()) return
        val what = if (files.size == 1) "«${files[0].name}»" else "${files.size} elementos"
        val warn = files.firstNotNullOfOrNull { Files.warning(it) }
        Env.nav.push(confirm("¿Mandar $what a la papelera?", warn ?: "Se puede recuperar durante 30 días desde Lugares › Papelera.", "A la papelera") {
            Env.scope.launch {
                val errors = withContext(Dispatchers.IO) { Files.toTrash(files) }
                pane.marked = emptySet(); reloadAll()
                Actions.toast(errors.firstOrNull() ?: "En la papelera")
            }
        })
    }

    private fun rename() {
        val f = pane.current() ?: return
        Env.nav.push(KeyboardScreen.text("Nuevo nombre", f.name) { name ->
            if (name.isNotBlank() && name != f.name) {
                val target = File(f.parentFile, name)
                if (target.exists()) Actions.toast("Ya existe algo con ese nombre")
                else if (!f.renameTo(target)) Actions.toast("No se pudo renombrar")
                else Files.notifyChanged(Files.paths(target) + f.absolutePath)
                pane.load(target, hidden)
            }
        })
    }

    private fun newFolder() {
        Env.nav.push(KeyboardScreen.text("Nueva carpeta", "") { name ->
            if (name.isNotBlank()) { val d = Files.freeName(pane.dir, name); d.mkdirs(); pane.load(d, hidden) }
        })
    }

    /** Trabajo largo en segundo plano con la misma barra de progreso que copiar. */
    private fun background(label: String, done: String, work: (cancel: () -> Boolean, progress: (Long, Long) -> Unit) -> String?) {
        if (busy != null) return
        cancelled = false; busy = label; progress = 0f
        job = Env.scope.launch {
            val error = withContext(Dispatchers.IO) { work({ cancelled }) { d, t -> progress = if (t > 0) d.toFloat() / t else 0f } }
            busy = null; progress = 0f; pane.marked = emptySet(); reloadAll()
            Actions.toast(error ?: done)
        }
    }

    private fun compress(ext: String) {
        val sources = pane.targets()
        if (sources.isEmpty()) return
        val base = if (sources.size == 1) sources[0].nameWithoutExtension.ifEmpty { sources[0].name } else pane.dir.name.ifEmpty { "archivos" }
        val dest = Files.freeName(pane.dir, "$base.$ext")
        background("Comprimiendo en ${dest.name}", "Creado ${dest.name}") { cancel, progress ->
            dev.palanca.launcher.data.Archives.compress(sources, dest, cancel, progress)
        }
    }

    private fun extract(here: Boolean) {
        val archive = pane.current()?.takeIf { dev.palanca.launcher.data.Archives.canExtract(it) } ?: return
        val dest = Files.freeName(if (here) pane.dir else other.dir, archive.nameWithoutExtension)
        background("Extrayendo ${archive.name}", "Extraído en ${dest.name}") { cancel, progress ->
            dev.palanca.launcher.data.Archives.extract(archive, dest, cancel, progress)
        }
    }

    private fun properties() {
        val files = pane.targets()
        if (files.isEmpty()) return
        Env.scope.launch {
            val (bytes, count) = withContext(Dispatchers.IO) { files.sumOf { Files.size(it) } to files.sumOf { Files.count(it) } }
            Actions.toast("${Files.human(bytes)} · $count archivo(s)")
        }
    }

    private fun actions() = MenuScreen("ARCHIVOS", pane.targets().let { if (it.size == 1) it[0].name else "${it.size} elementos" }) {
        val target = Files.roots().firstOrNull { it.second.absolutePath == other.dir.absolutePath }?.first ?: other.dir.name.ifEmpty { "otro panel" }
        listOfNotNull(
            pane.current()?.takeIf { !it.isDirectory }?.let { MenuItem("Abrir con…") { Files.open(it) } },
            MenuItem("Copiar a «$target»") { transfer(false) },
            MenuItem("Mover a «$target»") { transfer(true) },
            if (pane.targets().size == 1) MenuItem("Renombrar") { rename() } else null,
            MenuItem("Nueva carpeta aquí") { newFolder() },
            MenuItem("Tamaño") { properties() },
            pane.current()?.takeIf { dev.palanca.launcher.data.Archives.canExtract(it) }?.let { MenuItem("Extraer aquí") { extract(true) } },
            pane.current()?.takeIf { dev.palanca.launcher.data.Archives.canExtract(it) }?.let { MenuItem("Extraer en «$target»") { extract(false) } },
            MenuItem("Comprimir en ZIP (rápido)") { compress("zip") },
            MenuItem("Comprimir en 7z (ocupa menos)") { compress("7z") },
            MenuItem("Mandar a la papelera") { trash() },
            MenuItem(if (hidden) "Ocultar archivos ocultos" else "Mostrar archivos ocultos") { hidden = !hidden; reloadAll() },
        )
    }

    private fun places() = MenuScreen("LUGARES", "Ir a") {
        val list = Files.roots().map { (name, dir) -> name to dir } +
            listOfNotNull(
                ("Descargas" to File(Files.internal, "Download")),
                Files.sd()?.let { "ROMs (SD)" to File(it, "ROMs") }?.takeIf { it.second.exists() },
                Files.sd()?.let { "Instaladores (SD)" to File(it, "Instaladores") }?.takeIf { it.second.exists() },
            ) + Files.trashes().map { ("Papelera" + (if (it.absolutePath.startsWith(Files.internal.absolutePath)) "" else " (SD)")) to it }
        list.map { (name, dir) -> MenuItem(name) { pane.dir = dir; pane.sel = 0; pane.marked = emptySet(); hidden = hidden || dir.name.startsWith("."); pane.load(hidden = hidden) } } +
            MenuItem("Limpieza") { Env.nav.push(CleanScreen()) } +
            MenuItem("Servidor FTP", if (dev.palanca.launcher.system.FtpService.state.value.running) "encendido" else "") { Env.nav.push(FtpScreen()) }
    }

    override fun onPad(e: PadEvent): Boolean {
        if (busy != null) {
            if (e.btn == Btn.B && !e.long) { cancelled = true; busy = "Cancelando…" }
            return true
        }
        if (e.long) return true
        val n = pane.items.size
        when (e.btn) {
            Btn.UP -> if (n > 0) pane.sel = (pane.sel - 1 + n) % n
            Btn.DOWN -> if (n > 0) pane.sel = (pane.sel + 1) % n
            Btn.RS_UP -> pane.sel = (pane.sel - 8).coerceAtLeast(0)
            Btn.RS_DOWN -> pane.sel = (pane.sel + 8).coerceAtMost((n - 1).coerceAtLeast(0))
            Btn.L1, Btn.LEFT -> side = 0
            Btn.R1, Btn.RIGHT -> side = 1
            Btn.A -> pane.current()?.let(::enter)
            Btn.B -> if (!up()) Env.nav.pop()
            Btn.Y -> pane.current()?.let { f ->
                pane.marked = if (f in pane.marked) pane.marked - f else pane.marked + f
                if (n > 0) pane.sel = (pane.sel + 1).coerceAtMost(n - 1)
            }
            Btn.X -> if (pane.targets().isNotEmpty()) Env.nav.push(actions()) else Env.nav.push(MenuScreen("ARCHIVOS", pane.dir.name) {
                listOf(MenuItem("Nueva carpeta aquí") { newFolder() }, MenuItem(if (hidden) "Ocultar archivos ocultos" else "Mostrar archivos ocultos") { hidden = !hidden; reloadAll() })
            })
            Btn.START -> Env.nav.push(places())
            Btn.SELECT -> Env.nav.push(CleanScreen())
            else -> Unit
        }
        return true
    }

    @Composable
    override fun Content() {
        val accent = LocalAccent.current
        // Nace del botón que la abrió y vuelve a él al cerrarse, como las demás ventanas de Heaven.
        Popup(origin, closing, { Env.nav.pop() }) {
            Column(Modifier.align(Alignment.Center).padding(horizontal = 40.dp, vertical = 28.dp)) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    panes.forEachIndexed { i, p -> PaneView(p, i == side, Modifier.weight(1f).fillMaxHeight()) }
                }
                Spacer(Modifier.height(12.dp))
                val b = busy
                if (b != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        T(b, 15, P.Ink, modifier = Modifier.weight(1f))
                        T("${(progress * 100).toInt()} %", 15, accent, mono = true)
                        Spacer(Modifier.width(16.dp))
                        T("B cancelar", 13, P.Dim)
                    }
                    Spacer(Modifier.height(6.dp))
                    DropSlider(progress, accent, Modifier.fillMaxWidth())
                } else HintBar(
                    listOf("A" to "Abrir", "B" to "Subir", "Y" to "Marcar", "X" to "Acciones", "L1 R1" to "Panel", "START" to "Lugares", "SELECT" to "Limpieza"),
                    left = if (!Files.hasAccess()) "falta permiso de archivos" else pane.marked.size.takeIf { it > 0 }?.let { "$it marcado(s)" },
                )
            }
        }
    }

    @Composable
    private fun PaneView(p: Pane, active: Boolean, modifier: Modifier) {
        val accent = LocalAccent.current
        val state = rememberLazyListState()
        LaunchedEffect(p.sel, p.dir) { if (p.items.isNotEmpty()) state.animateScrollToItem((p.sel - 4).coerceAtLeast(0)) }
        Window(modifier.then(if (active) Modifier else Modifier.background(Color.Transparent))) {
            val root = Files.roots().firstOrNull { p.dir.absolutePath.startsWith(it.second.absolutePath) }
            val relative = root?.let { p.dir.absolutePath.removePrefix(it.second.absolutePath).ifEmpty { "/" } } ?: p.dir.absolutePath
            Label(root?.first ?: "Archivos", if (active) accent else P.Dim)
            T(relative, 18, if (active) P.Ink else P.Dim, FontWeight.Medium, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
            if (p.items.isEmpty()) T(if (Files.hasAccess()) "Carpeta vacía" else "Heaven necesita permiso para ver los archivos", 15, P.Dim, modifier = Modifier.padding(8.dp))
            LazyColumn(state = state, contentPadding = PaddingValues(vertical = 4.dp)) {
                itemsIndexed(p.items, key = { _, f -> f.absolutePath }) { i, f ->
                    val focused = active && i == p.sel
                    val marked = f in p.marked
                    Row(
                        Modifier.fillMaxWidth().height(44.dp)
                            .background(if (focused) P.Line else if (marked) accent.copy(alpha = 0.18f) else Color.Transparent, RoundedCornerShape(12.dp))
                            .brackets(focused, accent, gap = 1.dp, stroke = 2.dp, radius = 13.dp)
                            .tap { panes.indexOf(p).let { side = it }; if (p.sel == i) enter(f) else p.sel = i }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                            if (marked) Dot(accent, 10.dp)
                            else T(if (f.isDirectory) "▸" else "·", 16, if (f.isDirectory) accent else P.Dim)
                        }
                        Spacer(Modifier.width(8.dp))
                        T(f.name, 15, if (focused) P.Ink else if (f.isDirectory) P.Ink else P.Dim, if (f.isDirectory) FontWeight.Medium else FontWeight.Normal, modifier = Modifier.weight(1f))
                        if (!f.isDirectory) T(Files.human(f.length()), 12, P.Dim, mono = true)
                    }
                }
            }
        }
    }
}

/** Ventana de confirmación con dos opciones; [ok] solo se ejecuta si se elige la acción. */
fun confirm(title: String, detail: String, action: String, ok: () -> Unit) = MenuScreen("CONFIRMAR", title) {
    listOf(MenuItem(action) { ok() }, MenuItem("Cancelar") {}, MenuItem(detail.take(80), "") {})
}

/** Una fila de la limpieza: lo que es, cuánto ocupa y, si es seguro, qué hacer con ello. */
private class CleanItem(val title: String, val note: String, val bytes: Long, val files: List<File> = emptyList(), val action: String? = null)

/**
 * Limpieza: enseña lo que ocupa cada cosa y solo ofrece borrar lo que es seguro (papelera,
 * instaladores de apps que ya están instaladas). Las cachés de shaders se muestran pero no se tocan:
 * gracias a ellas los juegos no vuelven a compilar los shaders y no dan tirones.
 */
class CleanScreen : Screen, Closable {
    override val overlay get() = true
    override var closing by mutableStateOf(false)
    private var items by mutableStateOf<List<CleanItem>?>(null)
    private var row by mutableIntStateOf(0)

    init { scan() }

    private fun scan() {
        items = null
        Env.scope.launch {
            items = withContext(Dispatchers.IO) { buildItems() }
        }
    }

    private fun buildItems(): List<CleanItem> {
        val list = ArrayList<CleanItem>()
        val bins = Files.trashes()
        list += CleanItem("Papelera de Heaven", "Lo que mandaste a la papelera; se vacía sola a los 30 días", bins.sumOf { Files.size(it) }, bins.flatMap { it.listFiles().orEmpty().toList() }, "Vaciar ahora")

        // Instaladores (.apk) de apps que ya están instaladas en la misma versión o en una más nueva.
        val pm = Env.ctx.packageManager
        val apkDirs = listOfNotNull(File(Files.internal, "Download"), Files.sd()?.let { File(it, "Download") }, Files.sd()?.let { File(it, "Instaladores") })
        val apks = apkDirs.filter { it.exists() }.flatMap { d -> d.walkTopDown().maxDepth(4).filter { it.isFile && it.extension.equals("apk", true) }.toList() }
        val installed = apks.filter { apk ->
            runCatching {
                val info = pm.getPackageArchiveInfo(apk.path, 0) ?: return@runCatching false
                val have = pm.getPackageInfo(info.packageName, 0)
                androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(have) >= androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info)
            }.getOrDefault(false)
        }
        list += CleanItem(
            "Instaladores ya instalados", "${installed.size} archivo(s) .apk de apps que ya tienes en esa versión o en una más nueva",
            installed.sumOf { it.length() }, installed, if (installed.isNotEmpty()) "A la papelera" else null,
        )

        // Cachés de shaders de los emuladores: útiles, no se borran.
        val data = File(Files.internal, "Android/data")
        val caches = data.listFiles().orEmpty().flatMap { app ->
            app.walkTopDown().maxDepth(5).filter { it.isDirectory && Regex("shader|pipeline|spirv", RegexOption.IGNORE_CASE).containsMatchIn(it.name) }.toList()
        }.filter { c -> caches_parent_ok(c) }
        list += CleanItem("Cachés de shaders", "Útiles: evitan tirones al jugar. No se borran", caches.sumOf { Files.size(it) })

        // Lo que más ocupa en cada volumen (primer nivel), solo para saberlo.
        Files.roots().forEach { (name, root) ->
            root.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith(".") && it.name != "Android" }
                .map { it to Files.size(it) }.sortedByDescending { it.second }.take(4)
                .forEach { (dir, bytes) -> list += CleanItem("$name › ${dir.name}", "Para saberlo (ábrelo desde el explorador)", bytes) }
        }
        return list
    }

    /** Evita contar dos veces una carpeta de caché que está dentro de otra. */
    private fun caches_parent_ok(dir: File): Boolean = generateSequence(dir.parentFile) { it.parentFile }
        .take(4).none { Regex("shader|pipeline|spirv", RegexOption.IGNORE_CASE).containsMatchIn(it.name) }

    private fun act(item: CleanItem) {
        if (item.action == null || item.files.isEmpty()) return
        val emptying = item.title.startsWith("Papelera")
        Env.nav.push(confirm(if (emptying) "¿Vaciar la papelera?" else "¿Mandar ${item.files.size} instalador(es) a la papelera?",
            if (emptying) "Se borra de verdad: ${Files.human(item.bytes)}" else "Liberas ${Files.human(item.bytes)} cuando se vacíe la papelera",
            item.action) {
            Env.scope.launch {
                withContext(Dispatchers.IO) { if (emptying) item.files.forEach { it.deleteRecursively() } else Files.toTrash(item.files) }
                Actions.toast(if (emptying) "Papelera vaciada" else "Instaladores en la papelera")
                scan()
            }
        })
    }

    override fun onPad(e: PadEvent): Boolean {
        if (e.long) return true
        val n = items?.size ?: 0
        when (e.btn) {
            Btn.UP -> if (n > 0) row = (row - 1 + n) % n
            Btn.DOWN -> if (n > 0) row = (row + 1) % n
            Btn.A -> items?.getOrNull(row)?.let(::act)
            Btn.B -> Env.nav.pop()
            else -> Unit
        }
        return true
    }

    @Composable
    override fun Content() {
        val accent = LocalAccent.current
        val state = rememberLazyListState()
        LaunchedEffect(row) { if (!items.isNullOrEmpty()) state.animateScrollToItem((row - 2).coerceAtLeast(0)) }
        Popup(TransformOrigin(0.5f, 0.5f), closing, { Env.nav.pop() }) {
            Window(Modifier.align(Alignment.Center).width(1000.dp).padding(vertical = 10.dp)) {
                T("Limpieza", 24, weight = FontWeight.Medium, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
                T("Solo se ofrece borrar lo que es seguro. Todo pasa antes por la papelera.", 14, P.Dim, modifier = Modifier.padding(start = 8.dp, bottom = 10.dp))
                val list = items
                if (list == null) T("Calculando lo que ocupa cada cosa…", 16, P.Dim, modifier = Modifier.padding(8.dp))
                else LazyColumn(Modifier.height(470.dp), state = state) {
                    itemsIndexed(list) { i, it ->
                        val on = i == row
                        Row(
                            Modifier.fillMaxWidth().height(62.dp)
                                .background(if (on) P.Line else Color.Transparent, RoundedCornerShape(14.dp))
                                .brackets(on, accent, gap = 1.dp, stroke = 2.dp, radius = 15.dp)
                                .tap { row = i; act(it) }.padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                T(it.title, 16, if (on) P.Ink else P.Dim, if (on) FontWeight.Medium else FontWeight.Normal)
                                T(it.note, 12, P.Dim)
                            }
                            T(Files.human(it.bytes), 15, if (it.action != null) accent else P.Dim, mono = true)
                            if (it.action != null && it.files.isNotEmpty()) { Spacer(Modifier.width(14.dp)); T(it.action, 13, if (on) accent else P.Dim) }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                HintBar(listOf("A" to "Hacer", "B" to "Cerrar"))
            }
        }
    }
}

/**
 * Servidor FTP: A lo enciende o apaga, ◀ ▶ elige qué se comparte (memoria interna o SD).
 * Muestra la dirección, el usuario y la contraseña para escribirlos en el PC.
 */
class FtpScreen : Screen, Closable {
    override val overlay get() = true
    override var closing by mutableStateOf(false)
    private val roots = Files.roots()
    private var root by mutableIntStateOf(0)

    private fun toggle() {
        val s = dev.palanca.launcher.system.FtpService.state.value
        if (s.running) dev.palanca.launcher.system.FtpService.stop(Env.ctx)
        else {
            if (!Files.hasAccess()) Files.requestAccess()
            dev.palanca.launcher.system.FtpService.start(Env.ctx, roots[root].second.absolutePath)
        }
    }

    override fun onPad(e: PadEvent): Boolean {
        if (e.long) return true
        val running = dev.palanca.launcher.system.FtpService.state.value.running
        when (e.btn) {
            Btn.LEFT -> if (!running) root = (root - 1 + roots.size) % roots.size
            Btn.RIGHT -> if (!running) root = (root + 1) % roots.size
            Btn.A -> toggle()
            Btn.B -> Env.nav.pop()
            else -> Unit
        }
        return true
    }

    @Composable
    override fun Content() {
        val accent = LocalAccent.current
        val s by dev.palanca.launcher.system.FtpService.state.collectAsState()
        Popup(TransformOrigin(0.5f, 0.5f), closing, { Env.nav.pop() }) {
            Window(Modifier.align(Alignment.Center).width(860.dp)) {
                T("Servidor FTP", 24, weight = FontWeight.Medium, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
                T("Para pasar archivos entre el PC y la consola por Wi‑Fi, sin cable.", 14, P.Dim, modifier = Modifier.padding(start = 8.dp, bottom = 12.dp))
                ListRow("Compartir", roots[root].first, !s.running, arrows = !s.running)
                Spacer(Modifier.height(4.dp))
                ListRow(if (s.running) "Encendido" else "Apagado", if (s.running) "A para apagar" else "A para encender", true) { DropSwitch(s.running) }
                Spacer(Modifier.height(12.dp))
                if (s.running) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                        Label("En el PC, abre el Explorador de archivos y escribe en la barra de direcciones")
                        T(s.address, 30, accent, mono = true, modifier = Modifier.padding(vertical = 6.dp))
                        Row {
                            Column(Modifier.weight(1f)) { Label("Usuario"); T(dev.palanca.launcher.system.FtpService.USER, 22, P.Ink, mono = true) }
                            Column(Modifier.weight(1f)) { Label("Contraseña"); T(s.password, 22, P.Ink, mono = true) }
                        }
                        T("Sigue encendido aunque salgas de Heaven (verás una notificación). Apágalo al terminar.", 13, P.Dim, maxLines = 2, modifier = Modifier.padding(top = 10.dp))
                    }
                } else s.error?.let { T("No se pudo encender: $it", 14, P.Dim, maxLines = 2, modifier = Modifier.padding(start = 8.dp)) }
                Spacer(Modifier.height(12.dp))
                HintBar(listOf("A" to if (s.running) "Apagar" else "Encender") + (if (s.running) emptyList() else listOf("◀ ▶" to "Qué compartir")) + listOf("B" to "Cerrar"))
            }
        }
    }
}


/** Restaurar partidas: juego → copia (de la más nueva a la más vieja) → confirmar. */
fun restoreMenu() = MenuScreen("PARTIDAS", "Restaurar una copia") {
    val list = dev.palanca.launcher.system.Playtime.backups()
    if (list.isEmpty()) listOf(MenuItem("Todavía no hay copias de partidas", "") {})
    else list.map { (pkg, zips) ->
        val name = Env.apps.apps.value.firstOrNull { it.pkg == pkg }?.label ?: pkg
        MenuItem(name, "${zips.size} copia(s)") {
            Env.nav.push(MenuScreen("COPIAS", name) {
                zips.map { zip ->
                    val date = zip.nameWithoutExtension.replace('_', ' ').replace(Regex("(\\d{2})-(\\d{2})$"), "\$1:\$2")
                    MenuItem(date, Files.human(zip.length())) {
                        Env.nav.push(confirm("¿Restaurar la partida del $date?", "Se cierra $name y antes se guarda la partida actual.", "Restaurar") {
                            Actions.toast("Restaurando…")
                            Env.scope.launch {
                                val msg = withContext(Dispatchers.IO) { dev.palanca.launcher.system.Playtime.restore(pkg, zip) }
                                Actions.toast(msg)
                            }
                        })
                    }
                }
            })
        }
    }
}
