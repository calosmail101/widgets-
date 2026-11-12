package dev.palanca.launcher.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import dev.palanca.launcher.Env
import dev.palanca.launcher.system.Actions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Serializable
data class BackupFile(val kind: String, val look: Look, val config: Config? = null)

/** Temas y copias: un .zip con palanca.json y las imágenes propias (fondo e iconos). */
object Backup {
    private val dir get() = Env.ctx.filesDir

    fun export(themeOnly: Boolean) {
        val name = if (themeOnly) "tema-palanca.zip" else "copia-palanca.zip"
        Env.host?.createDocument(name) { uri ->
            uri ?: return@createDocument
            Env.scope.launch {
                val ok = withContext(Dispatchers.IO) { runCatching { write(uri, themeOnly) }.isSuccess }
                Actions.toast(if (ok) "Guardado: $name" else "No se pudo guardar")
            }
        }
    }

    private fun write(uri: Uri, themeOnly: Boolean) {
        val cfg = Env.cfg.value
        val data = BackupFile(if (themeOnly) "tema" else "copia", cfg.look, if (themeOnly) null else cfg)
        val files = buildList {
            cfg.look.wallpaperFile?.let(::add)
            if (!themeOnly) cfg.icons.values.mapNotNullTo(this) { it.file }
        }.distinct()
        ZipOutputStream(Env.ctx.contentResolver.openOutputStream(uri)!!).use { zip ->
            zip.putNextEntry(ZipEntry("palanca.json"))
            zip.write(PalancaJson.encodeToString(BackupFile.serializer(), data).toByteArray())
            zip.closeEntry()
            for (f in files) {
                val file = File(dir, f)
                if (!file.exists()) continue
                zip.putNextEntry(ZipEntry("files/$f"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    fun import() {
        Env.host?.openDocument { uri ->
            uri ?: return@openDocument
            Env.scope.launch {
                val data = withContext(Dispatchers.IO) { runCatching { read(uri) }.getOrNull() }
                if (data == null) { Actions.toast("Ese archivo no es un tema ni una copia de Palanca"); return@launch }
                Env.cfg.update { current ->
                    // Los widgets no viajan: sus identificadores solo valen en el dispositivo original.
                    data.config?.copy(widgets = current.widgets, pinnedInit = true) ?: current.copy(look = data.look)
                }
                Env.icons.clear()
                Actions.toast(if (data.config != null) "Copia restaurada" else "Tema aplicado")
            }
        }
    }

    private fun read(uri: Uri): BackupFile? {
        var data: BackupFile? = null
        ZipInputStream(Env.ctx.contentResolver.openInputStream(uri)!!).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                when {
                    entry.name == "palanca.json" -> data = PalancaJson.decodeFromString(BackupFile.serializer(), zip.readBytes().decodeToString())
                    // Solo el nombre del archivo: nunca rutas que salgan de la carpeta de la app.
                    entry.name.startsWith("files/") && !entry.isDirectory ->
                        File(dir, File(entry.name).name).outputStream().use { zip.copyTo(it) }
                }
            }
        }
        return data
    }

    fun pickWallpaper() {
        Env.host?.pickImage { uri ->
            uri ?: return@pickImage
            Env.scope.launch {
                val name = "wallpaper_${System.currentTimeMillis()}"
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        Env.ctx.contentResolver.openInputStream(uri)!!.use { input -> File(dir, name).outputStream().use { input.copyTo(it) } }
                    }.isSuccess
                }
                if (!ok) { Actions.toast("No se pudo leer la imagen"); return@launch }
                val old = Env.cfg.value.look.wallpaperFile
                Env.cfg.update { it.look { copy(wallpaper = "imagen", wallpaperFile = name) } }
                old?.let { File(dir, it).delete() }
            }
        }
    }

    /** Copia una imagen de la galería como icono de [key], reducida a 512 px. */
    fun pickIcon(key: String) {
        Env.host?.pickImage { uri ->
            uri ?: return@pickImage
            Env.scope.launch {
                val name = "icon_${key.hashCode().toUInt()}_${System.currentTimeMillis()}.png"
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        val full = Env.ctx.contentResolver.openInputStream(uri)!!.use(BitmapFactory::decodeStream)!!
                        val side = minOf(full.width, full.height)
                        val square = Bitmap.createBitmap(full, (full.width - side) / 2, (full.height - side) / 2, side, side)
                        val small = if (side > 512) Bitmap.createScaledBitmap(square, 512, 512, true) else square
                        File(dir, name).outputStream().use { small.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }.isSuccess
                }
                if (!ok) { Actions.toast("No se pudo leer la imagen"); return@launch }
                val old = Env.cfg.value.icons[key]?.file
                Env.cfg.update { it.icon(key) { copy(file = name, pack = null, drawable = null) } }
                old?.let { File(dir, it).delete() }
            }
        }
    }
}
