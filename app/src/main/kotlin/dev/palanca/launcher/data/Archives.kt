package dev.palanca.launcher.data

import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Comprimir y extraer en ZIP (rápido, lo abre cualquier sistema) y 7z (comprime más, tarda más).
 * Se llama fuera del hilo de la interfaz. [progress] recibe bytes hechos y totales.
 */
object Archives {
    fun canExtract(f: File) = f.isFile && f.extension.lowercase() in setOf("zip", "7z")

    /** Comprime [sources] en [dest] (.zip o .7z según la extensión). Devuelve un error o null. */
    fun compress(sources: List<File>, dest: File, cancel: () -> Boolean, progress: (Long, Long) -> Unit): String? = runCatching {
        val total = sources.sumOf { Files.size(it) }
        var done = 0L
        // Carpeta común a todo lo que se comprime: así las rutas dentro del archivo nunca llevan «../».
        var base = sources.first().parentFile!!
        while (sources.any { !it.absolutePath.startsWith(base.absolutePath + File.separator) }) base = base.parentFile ?: break
        val files = sources.flatMap { s -> s.walkTopDown().toList() }
        if (dest.extension.lowercase() == "7z") {
            SevenZOutputFile(dest).use { out ->
                for (f in files) {
                    if (cancel()) break
                    val entry = out.createArchiveEntry(f, f.relativeTo(base).path.replace('\\', '/'))
                    out.putArchiveEntry(entry)
                    if (f.isFile) f.inputStream().use { input -> pump(input, { b, o, n -> out.write(b, o, n) }, cancel) { done += it; progress(done, total) } }
                    out.closeArchiveEntry()
                }
            }
        } else {
            ZipArchiveOutputStream(dest).use { out ->
                for (f in files) {
                    if (cancel()) break
                    val name = f.relativeTo(base).path.replace('\\', '/') + if (f.isDirectory) "/" else ""
                    out.putArchiveEntry(ZipArchiveEntry(f, name))
                    if (f.isFile) f.inputStream().use { input -> pump(input, { b, o, n -> out.write(b, o, n) }, cancel) { done += it; progress(done, total) } }
                    out.closeArchiveEntry()
                }
            }
        }
        if (cancel()) { dest.delete(); "Cancelado" } else { Files.notifyChanged(listOf(dest.absolutePath)); null }
    }.getOrElse { dest.delete(); it.message ?: "No se pudo comprimir" }

    /**
     * Extrae [archive] dentro de [destDir]. Las rutas que intenten salir de la carpeta de destino
     * (por ejemplo «../../algo») se rechazan.
     */
    fun extract(archive: File, destDir: File, cancel: () -> Boolean, progress: (Long, Long) -> Unit): String? = runCatching {
        destDir.mkdirs()
        val root = destDir.canonicalPath + File.separator
        fun target(name: String): File {
            val out = File(destDir, name)
            require(out.canonicalPath.startsWith(root) || out.canonicalPath + File.separator == root) { "Ruta no válida dentro del archivo: $name" }
            return out
        }
        var done = 0L
        if (archive.extension.lowercase() == "7z") {
            SevenZFile.builder().setFile(archive).get().use { z ->
                val total = z.entries.sumOf { it.size.coerceAtLeast(0) }
                while (true) {
                    if (cancel()) break
                    val e = z.nextEntry ?: break
                    val out = target(e.name)
                    if (e.isDirectory) { out.mkdirs(); continue }
                    out.parentFile?.mkdirs()
                    out.outputStream().use { o ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            val n = z.read(buf); if (n < 0) break
                            o.write(buf, 0, n); done += n; progress(done, total)
                            if (cancel()) break
                        }
                    }
                }
            }
        } else {
            ZipFile.builder().setFile(archive).get().use { zip ->
                val entries = zip.entries.toList()
                val total = entries.sumOf { it.size.coerceAtLeast(0) }
                for (e in entries) {
                    if (cancel()) break
                    val out = target(e.name)
                    if (e.isDirectory) { out.mkdirs(); continue }
                    out.parentFile?.mkdirs()
                    zip.getInputStream(e).use { input -> out.outputStream().use { o -> pump(input, { b, off, n -> o.write(b, off, n) }, cancel) { done += it; progress(done, total) } } }
                }
            }
        }
        Files.notifyChanged(Files.paths(destDir))
        if (cancel()) "Cancelado" else null
    }.getOrElse { it.message ?: "No se pudo extraer" }

    private inline fun pump(input: InputStream, write: (ByteArray, Int, Int) -> Unit, cancel: () -> Boolean, step: (Int) -> Unit) {
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buf); if (n < 0) break
            write(buf, 0, n); step(n)
            if (cancel()) break
        }
    }

    @Suppress("unused") private fun OutputStream.ignore() = Unit
}
