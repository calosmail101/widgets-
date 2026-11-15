package dev.palanca.launcher.data

import android.content.Intent
import android.os.Environment
import android.os.storage.StorageManager
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import dev.palanca.launcher.Env
import dev.palanca.launcher.system.Privileged
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Operaciones del explorador de archivos. Todo lo que tarda (copiar, mover, tamaños) se llama fuera
 * del hilo de la interfaz. Borrar nunca es definitivo: va a una papelera en la memoria interna.
 */
object Files {
    val internal: File get() = Environment.getExternalStorageDirectory()
    val trash: File get() = File(internal, ".HeavenPapelera")

    /** Memoria interna y tarjetas SD montadas, con un nombre corto. */
    fun roots(): List<Pair<String, File>> {
        val sm = Env.ctx.getSystemService(StorageManager::class.java)
        val list = sm.storageVolumes.mapNotNull { v ->
            val dir = v.directory ?: return@mapNotNull null
            (if (v.isPrimary) "Memoria interna" else "Tarjeta SD") to dir
        }
        return list.ifEmpty { listOf("Memoria interna" to internal) }
    }

    fun sd(): File? = roots().firstOrNull { it.first == "Tarjeta SD" }?.second

    fun hasAccess(): Boolean = Environment.isExternalStorageManager()

    /** Pide el permiso de todos los archivos. Con PServer se concede solo; si no, abre la pantalla de Android. */
    fun requestAccess() {
        if (hasAccess()) return
        if (Privileged.run("appops set ${Env.ctx.packageName} MANAGE_EXTERNAL_STORAGE allow")) return
        runCatching {
            Env.ctx.startActivity(
                Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, android.net.Uri.parse("package:${Env.ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** Carpetas primero y luego archivos, por nombre sin acentos. Los ocultos (empiezan por punto) solo si se piden. */
    fun list(dir: File, hidden: Boolean = false): List<File> =
        dir.listFiles().orEmpty()
            .filter { hidden || !it.name.startsWith(".") }
            .sortedWith(compareBy<File>({ !it.isDirectory }, { fold(it.name) }))

    /**
     * Carpetas que conviene no tocar sin saber lo que se hace. Devuelve el motivo para avisar antes de
     * mover o borrar (no lo impide).
     */
    fun warning(f: File): String? {
        val p = f.absolutePath
        return when {
            p.contains("/Android/data") || p.contains("/Android/obb") -> "Son datos de apps: borrarlos puede hacer perder partidas o ajustes."
            p.contains("/BotW-Recomp") -> "BOTW Recomp usa esta carpeta en el sitio: si la mueves o borras, el juego deja de arrancar."
            p.contains("/BIOS") -> "Aquí están las BIOS y las claves que usan los emuladores."
            p.contains("/Cocoon") -> "Es la biblioteca de Cocoon (carátulas y base de datos)."
            else -> null
        }
    }

    fun size(f: File): Long = if (f.isDirectory) f.walkTopDown().filter { it.isFile }.sumOf { it.length() } else f.length()
    fun count(f: File): Int = if (f.isDirectory) f.walkTopDown().count { it.isFile } else 1

    /** Nombre libre en [dir]: «juego.chd», «juego (2).chd», «juego (3).chd»… */
    fun freeName(dir: File, name: String): File {
        var target = File(dir, name)
        if (!target.exists()) return target
        val dot = name.lastIndexOf('.').takeIf { it > 0 && !File(dir, name).isDirectory } ?: name.length
        var n = 2
        while (target.exists()) { target = File(dir, name.substring(0, dot) + " ($n)" + name.substring(dot)); n++ }
        return target
    }

    /**
     * Copia o mueve [sources] a [dest]. Mover dentro del mismo volumen es instantáneo (renombrar);
     * entre volumenes se copia, se comprueba el tamaño y solo entonces se borra el original.
     * [progress] recibe bytes hechos, bytes totales y el archivo actual. Devuelve los errores.
     */
    fun transfer(sources: List<File>, dest: File, move: Boolean, cancel: () -> Boolean, progress: (Long, Long, String) -> Unit): List<String> {
        val errors = ArrayList<String>()
        val total = sources.sumOf { size(it) }
        var done = 0L
        for (src in sources) {
            if (cancel()) break
            if (dest.absolutePath.startsWith(src.absolutePath + "/") || dest == src) { errors += "${src.name}: no se puede copiar dentro de sí misma"; continue }
            val target = freeName(dest, src.name)
            if (move && sameVolume(src, dest) && src.renameTo(target)) { done += size(target); progress(done, total, src.name); continue }
            val ok = runCatching {
                src.walkTopDown().forEach { f ->
                    if (cancel()) return@forEach
                    val out = File(target, f.relativeTo(src).path)
                    if (f.isDirectory) out.mkdirs()
                    else {
                        out.parentFile?.mkdirs()
                        f.inputStream().use { input ->
                            out.outputStream().use { output ->
                                val buf = ByteArray(1 shl 20)
                                while (true) {
                                    val n = input.read(buf); if (n < 0) break
                                    output.write(buf, 0, n); done += n
                                    progress(done, total, f.name)
                                    if (cancel()) break
                                }
                            }
                        }
                    }
                }
                size(target) == size(src)
            }.getOrElse { errors += "${src.name}: ${it.message}"; false }
            if (!ok) { if (!cancel()) errors += "${src.name}: la copia no coincide con el original"; continue }
            if (move && !src.deleteRecursively()) errors += "${src.name}: copiado, pero no se pudo borrar el original"
        }
        return errors
    }

    private fun sameVolume(a: File, b: File): Boolean = roots().firstOrNull { a.absolutePath.startsWith(it.second.absolutePath) }?.second ==
        roots().firstOrNull { b.absolutePath.startsWith(it.second.absolutePath) }?.second

    /**
     * A la papelera, dentro de una carpeta con fecha y un archivo «.origen» con la ruta original.
     * Lo de la tarjeta SD va a su propia papelera para no copiar gigas de un volumen a otro.
     */
    fun toTrash(files: List<File>): List<String> {
        val errors = ArrayList<String>()
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        for (f in files) {
            val root = roots().firstOrNull { f.absolutePath.startsWith(it.second.absolutePath) }?.second ?: internal
            val bin = File(File(root, ".HeavenPapelera"), stamp).apply { mkdirs() }
            val target = freeName(bin, f.name)
            if (f.renameTo(target)) File(bin, target.name + ".origen").writeText(f.absolutePath)
            else errors += "${f.name}: no se pudo mover a la papelera"
        }
        return errors
    }

    /** Papeleras de todos los volúmenes. */
    fun trashes(): List<File> = roots().map { File(it.second, ".HeavenPapelera") }.filter { it.exists() }

    /** Borra de la papelera lo que lleve más de [days] días. */
    fun purgeTrash(days: Int) {
        val limit = System.currentTimeMillis() - days * 86_400_000L
        trashes().flatMap { it.listFiles().orEmpty().toList() }.filter { it.lastModified() < limit }.forEach { it.deleteRecursively() }
    }

    /** Abre un archivo con la app que lo entienda (elige Android si hay varias). */
    fun open(f: File) {
        val ext = f.extension.lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        val uri = FileProvider.getUriForFile(Env.ctx, "${Env.ctx.packageName}.files", f)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching {
            Env.ctx.startActivity(Intent.createChooser(intent, f.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { dev.palanca.launcher.system.Actions.toast("Ninguna app abre este archivo") }
    }

    fun human(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format(Locale.US, "%.0f MB", bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", bytes / (1L shl 10).toDouble())
        else -> "$bytes B"
    }
}
