package dev.palanca.launcher.system

import android.os.Handler
import android.os.Looper
import dev.palanca.launcher.Env
import dev.palanca.launcher.data.Archives
import dev.palanca.launcher.data.Cat
import dev.palanca.launcher.data.Files
import dev.palanca.launcher.data.categoryIn
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Tiempo de juego, copia de partidas y capturas. El servicio del puntero avisa de cada app que pasa a
 * primer plano (también las abiertas desde Cocoon), así que cuenta cualquier juego o emulador.
 */
object Playtime {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var current: String? = null
    private var since = 0L

    /** Paquete en primer plano ahora mismo (para nombrar las capturas). */
    val foreground: String? get() = current

    private fun isGame(pkg: String): Boolean {
        val app = Env.apps.apps.value.firstOrNull { it.pkg == pkg } ?: return false
        return app.categoryIn(Env.cfg.value) == Cat.JUEGOS || app.categoryIn(Env.cfg.value) == Cat.EMULADORES
    }

    fun onForeground(pkg: String) {
        if (pkg == current) return
        val previous = current
        val now = System.currentTimeMillis()
        if (previous != null && isGame(previous)) {
            val ms = now - since
            if (ms in 10_000..12 * 3_600_000L) add(previous, ms)
            // Al salir de un juego se copian sus partidas (solo si cambiaron).
            if (SAVES.containsKey(previous)) io.execute { runCatching { backup(previous) } }
        }
        current = pkg; since = now
    }

    private fun add(pkg: String, ms: Long) {
        val today = LocalDate.now()
        val keep = today.minusDays(27).toString()
        Env.stats.update { s ->
            val day = s.days[today.toString()].orEmpty()
            val days = (s.days + (today.toString() to (day + (pkg to (day[pkg] ?: 0L) + ms)))).filterKeys { it >= keep }
            s.copy(days = days)
        }
    }

    /** Minutos por paquete en los últimos [daysBack] días (hoy incluido), de más a menos. */
    fun summary(daysBack: Int): List<Pair<String, Long>> {
        val from = LocalDate.now().minusDays((daysBack - 1).toLong()).toString()
        val total = HashMap<String, Long>()
        Env.stats.value.days.filterKeys { it >= from }.values.forEach { day -> day.forEach { (pkg, ms) -> total[pkg] = (total[pkg] ?: 0L) + ms } }
        // La sesión en curso también cuenta.
        current?.takeIf { isGame(it) }?.let { total[it] = (total[it] ?: 0L) + (System.currentTimeMillis() - since) }
        return total.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }

    fun human(ms: Long): String {
        val min = ms / 60_000
        if (min < 1) return "< 1 min"
        return if (min < 60) "$min min" else "${min / 60} h ${min % 60} min"
    }

    // ---- Copia de partidas ----

    /**
     * Carpetas de partidas conocidas (relativas a la memoria interna). Solo se copian las que existen.
     * Comprobadas en la consola (15 nov): BOTW Recomp, Eden, Dolphin, Cemu y ARMSX2 (en la SD).
     */
    val SAVES: Map<String, List<String>> = mapOf(
        "com.botwrecomp.android" to listOf("Android/data/com.botwrecomp.android/files/v190/save", "Android/data/com.botwrecomp.android/files/v100/save"),
        "dev.eden.eden_emulator.nightly" to listOf("Android/data/dev.eden.eden_emulator.nightly/files/nand/user/save"),
        "org.dolphinemu.dolphinemu" to listOf("Android/data/org.dolphinemu.dolphinemu/files/GC", "Android/data/org.dolphinemu.dolphinemu/files/Wii/title"),
        "info.cemu.cemu" to listOf("Android/data/info.cemu.cemu/files/mlc01/usr/save"),
        // ARMSX2 guarda sus datos en la tarjeta SD («sd:» = relativo a la SD).
        "com.armsx2" to listOf("sd:Android/data/com.armsx2/files/memcards", "Android/data/com.armsx2/files/memcards"),
        "xendroid.compose" to listOf("Android/data/xendroid.compose/files/content"),
    )
    private const val KEEP = 3

    private fun backupRoot(): File = File(Files.sd() ?: Files.internal, "Backups/Partidas")

    /** Huella de las partidas (rutas, tamaños y fechas): si no cambió, no se copia otra vez. */
    private fun fingerprint(dirs: List<File>): String {
        val md = MessageDigest.getInstance("SHA-1")
        dirs.flatMap { d -> d.walkTopDown().filter { it.isFile }.toList() }.sortedBy { it.path }
            .forEach { md.update("${it.path}|${it.length()}|${it.lastModified()}".toByteArray()) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Copia las partidas de [pkg] en un ZIP con fecha; guarda las 3 últimas. Devuelve un mensaje. */
    private fun saveDirs(pkg: String): List<File> = SAVES[pkg].orEmpty().mapNotNull { rel ->
        if (rel.startsWith("sd:")) Files.sd()?.let { File(it, rel.removePrefix("sd:")) } else File(Files.internal, rel)
    }.filter { it.isDirectory }

    private fun label(pkg: String) = Env.apps.apps.value.firstOrNull { it.pkg == pkg }?.label ?: pkg
    private fun folderFor(pkg: String) = File(backupRoot(), label(pkg).replace(Regex("[^\\p{L}\\p{N} ._-]"), ""))

    fun backup(pkg: String, force: Boolean = false): String {
        val dirs = saveDirs(pkg)
        if (dirs.isEmpty()) return "Sin partidas"
        val folder = folderFor(pkg).apply { mkdirs() }
        val print = fingerprint(dirs)
        val mark = File(folder, ".huella")
        if (!force && mark.exists() && mark.readText() == print) return "Sin cambios"
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())
        // Dentro del ZIP cada carpeta conserva su ruta desde la carpeta común (save, GC, Wii/title…).
        val zip = Files.freeName(folder, "$stamp.zip")
        val error = Archives.compress(dirs, zip, { false }) { _, _ -> }
        if (error != null) return error
        mark.writeText(print)
        folder.listFiles { f -> f.extension == "zip" }.orEmpty().sortedByDescending { it.name }.drop(KEEP).forEach { it.delete() }
        Files.notifyChanged(listOf(zip.absolutePath))
        return "Partidas copiadas"
    }

    /** Juegos con copias guardadas, con sus copias de la más nueva a la más vieja. */
    fun backups(): List<Pair<String, List<File>>> = SAVES.keys.mapNotNull { pkg ->
        val zips = folderFor(pkg).listFiles { f -> f.extension == "zip" }.orEmpty().sortedByDescending { it.name }
        if (zips.isEmpty()) null else pkg to zips
    }

    /**
     * Restaura una copia: cierra el juego, guarda la partida actual (por si acaso), vacía sus carpetas
     * de partidas y extrae la copia elegida en su sitio. Devuelve un mensaje para el usuario.
     */
    fun restore(pkg: String, zip: File): String {
        val dirs = saveDirs(pkg)
        if (dirs.isEmpty()) return "No se encontró la carpeta de partidas de ${label(pkg)}"
        Privileged.run("am force-stop $pkg")
        Thread.sleep(800)
        // La copia elegida se aparta primero: guardar la partida actual puede borrar la copia más vieja
        // (se guardan solo 3) y esa podría ser justo la que se quiere restaurar.
        val chosen = File(Env.ctx.cacheDir, "restaurar.zip")
        zip.copyTo(chosen, overwrite = true)
        // La partida actual se guarda antes de tocar nada (aunque no haya cambiado).
        val saved = backup(pkg, force = true)
        if (saved != "Partidas copiadas") { chosen.delete(); return "No se pudo guardar la partida actual: $saved" }
        var base = dirs.first().parentFile!!
        while (dirs.any { !it.absolutePath.startsWith(base.absolutePath + File.separator) }) base = base.parentFile ?: break
        dirs.forEach { d -> d.listFiles().orEmpty().forEach { it.deleteRecursively() } }
        val error = Archives.extract(chosen, base, { false }) { _, _ -> }
        chosen.delete()
        return error ?: "Partida de ${label(pkg)} restaurada (${zip.nameWithoutExtension.replace('_', ' ')})"
    }

    /** Copia ahora las partidas de todos los juegos que tengan; devuelve cuántos se copiaron. */
    fun backupAll(): Int = SAVES.keys.count { runCatching { backup(it) == "Partidas copiadas" }.getOrDefault(false) }

    fun backupsSize(): Long = backupRoot().takeIf { it.exists() }?.let { Files.size(it) } ?: 0L

    // ---- Capturas ----

    /** Captura de pantalla con PServer, en Imágenes/Heaven con el nombre del juego y la fecha. */
    fun screenshot() {
        val pkg = current
        val label = Env.apps.apps.value.firstOrNull { it.pkg == pkg }?.label ?: "Heaven"
        val dir = File(Files.internal, "Pictures/Heaven").apply { mkdirs() }
        val name = "${label.replace(Regex("[^\\p{L}\\p{N}]+"), "_")}_${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())}.png"
        val file = File(dir, name)
        io.execute {
            val ok = Privileged.run("screencap -p ${file.absolutePath}")
            // Se espera a que el archivo exista para avisar a la galería.
            repeat(20) { if (file.exists() && file.length() > 0) return@repeat; Thread.sleep(150) }
            Files.notifyChanged(listOf(file.absolutePath))
            main.post { Actions.toast(if (ok && file.exists()) "Captura guardada en Imágenes/Heaven" else "No se pudo hacer la captura") }
        }
    }
}
