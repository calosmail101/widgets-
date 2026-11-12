package dev.palanca.launcher.icons

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Shader
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.palanca.launcher.data.AppEntry
import dev.palanca.launcher.data.Config
import dev.palanca.launcher.data.labelIn
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

data class IconPack(val pkg: String, val name: String)

/** Formas de máscara disponibles: id guardado en la configuración → nombre visible. */
val ICON_SHAPES = listOf(
    "squircle" to "Squircle", "circulo" to "Círculo", "cuadrado" to "Cuadrado",
    "chaflan" to "Chaflán", "hexagono" to "Hexágono", "ninguna" to "Sin máscara",
)

/** Valor de [dev.palanca.launcher.data.IconOverride.pack] que fuerza el icono de fábrica. */
const val PACK_ORIGINAL = "original"

fun shapePath(shape: String, size: Float): Path {
    val p = Path()
    val c = size / 2f
    when (shape) {
        "circulo" -> p.addCircle(c, c, c, Path.Direction.CW)
        "cuadrado" -> p.addRoundRect(RectF(0f, 0f, size, size), size * 0.12f, size * 0.12f, Path.Direction.CW)
        "chaflan" -> {
            val k = size * 0.22f
            p.moveTo(k, 0f); p.lineTo(size, 0f); p.lineTo(size, size - k)
            p.lineTo(size - k, size); p.lineTo(0f, size); p.lineTo(0f, k); p.close()
        }
        "hexagono" -> {
            for (i in 0 until 6) {
                val a = Math.toRadians(60.0 * i)
                val x = c + c * cos(a).toFloat(); val y = c + c * 0.98f * sin(a).toFloat()
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            p.close()
        }
        else -> { // superelipse (squircle)
            val n = 4.6
            for (i in 0..72) {
                val a = Math.toRadians(i * 5.0)
                val ca = cos(a); val sa = sin(a)
                val x = c + c * (abs(ca).pow(2 / n) * sign(ca)).toFloat()
                val y = c + c * (abs(sa).pow(2 / n) * sign(sa)).toFloat()
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            p.close()
        }
    }
    return p
}

/** Lee paquetes de iconos estándar (formato ADW/Nova: appfilter.xml). */
class IconPacks(private val ctx: Context) {
    private val pm = ctx.packageManager
    private val mappings = ConcurrentHashMap<String, Map<String, String>>()
    private val drawables = ConcurrentHashMap<String, List<String>>()
    private val resources = ConcurrentHashMap<String, Resources>()

    fun list(): List<IconPack> {
        val found = LinkedHashMap<String, IconPack>()
        for (action in listOf("org.adw.launcher.THEMES", "com.novalauncher.THEME", "com.gau.go.launcherex.theme")) {
            runCatching {
                for (ri in pm.queryIntentActivities(Intent(action), 0)) {
                    val pkg = ri.activityInfo.packageName
                    found.getOrPut(pkg) { IconPack(pkg, ri.activityInfo.applicationInfo.loadLabel(pm).toString()) }
                }
            }
        }
        return found.values.sortedBy { it.name.lowercase() }
    }

    fun name(pkg: String): String = runCatching {
        pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
    }.getOrDefault(pkg)

    private fun res(pack: String): Resources? =
        resources[pack] ?: runCatching { pm.getResourcesForApplication(pack) }.getOrNull()?.also { resources[pack] = it }

    private fun parser(pack: String, name: String): XmlPullParser? {
        val r = res(pack) ?: return null
        val id = r.getIdentifier(name, "xml", pack)
        if (id != 0) return r.getXml(id)
        return runCatching {
            XmlPullParserFactory.newInstance().newPullParser().apply { setInput(r.assets.open("$name.xml"), "UTF-8") }
        }.getOrNull()
    }

    /** "paquete/Actividad" → nombre del drawable dentro del paquete de iconos. */
    fun mapping(pack: String): Map<String, String> = mappings.getOrPut(pack) {
        val map = HashMap<String, String>()
        runCatching {
            val xml = parser(pack, "appfilter") ?: return@runCatching
            var event = xml.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && xml.name == "item") {
                    val component = xml.getAttributeValue(null, "component")
                    val drawable = xml.getAttributeValue(null, "drawable")
                    if (component != null && drawable != null && component.startsWith("ComponentInfo{")) {
                        map.putIfAbsent(component.substring(14, component.length - 1), drawable)
                    }
                }
                event = xml.next()
            }
        }
        map
    }

    /** Todos los iconos del paquete, para elegir uno a mano. */
    fun allDrawables(pack: String): List<String> = drawables.getOrPut(pack) {
        val names = LinkedHashSet<String>()
        runCatching {
            val xml = parser(pack, "drawable") ?: return@runCatching
            var event = xml.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && xml.name == "item") {
                    xml.getAttributeValue(null, "drawable")?.let(names::add)
                }
                event = xml.next()
            }
        }
        if (names.isEmpty()) names += mapping(pack).values.sorted()
        names.toList()
    }

    @Suppress("DEPRECATION")
    fun load(pack: String, drawable: String): Drawable? = runCatching {
        val r = res(pack) ?: return null
        val id = r.getIdentifier(drawable, "drawable", pack).takeIf { it != 0 }
            ?: r.getIdentifier(drawable, "mipmap", pack)
        if (id == 0) null else r.getDrawable(id)
    }.getOrNull()

    fun invalidate() { mappings.clear(); drawables.clear(); resources.clear() }
}

/** Dibuja iconos ya enmascarados a un bitmap y los guarda en caché. */
class IconRenderer(private val ctx: Context, val packs: IconPacks) {
    private val pm = ctx.packageManager
    private val cache = LruCache<String, ImageBitmap>(400)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun clear() { cache.evictAll(); packs.invalidate() }
    fun cached(key: String): ImageBitmap? = cache.get(key)

    fun keyFor(app: AppEntry, cfg: Config, px: Int, generation: Int): String {
        val l = cfg.look
        return "${app.key}|$px|$generation|${l.iconShape}|${l.iconPack}|${cfg.icons[app.key]?.hashCode() ?: 0}"
    }

    fun render(key: String, app: AppEntry, cfg: Config, px: Int): ImageBitmap {
        cache.get(key)?.let { return it }
        val o = cfg.icons[app.key]
        val shape = o?.shape ?: cfg.look.iconShape
        val bmp = draw(source(app, cfg), app.labelIn(cfg), shape, px).asImageBitmap()
        cache.put(key, bmp)
        return bmp
    }

    /** Icono suelto de un paquete, para la rejilla del editor. */
    fun renderPackIcon(pack: String, drawable: String, shape: String, px: Int): ImageBitmap {
        val key = "pack|$pack|$drawable|$shape|$px"
        cache.get(key)?.let { return it }
        val bmp = draw(packs.load(pack, drawable), drawable, shape, px).asImageBitmap()
        cache.put(key, bmp)
        return bmp
    }

    private fun source(app: AppEntry, cfg: Config): Drawable? {
        val o = cfg.icons[app.key]
        o?.file?.let { name ->
            BitmapFactory.decodeFile(File(ctx.filesDir, name).path)?.let { return BitmapDrawable(ctx.resources, it) }
        }
        if (o?.pack != PACK_ORIGINAL) {
            if (o?.pack != null && o.drawable != null) packs.load(o.pack, o.drawable)?.let { return it }
            cfg.look.iconPack?.let { pack ->
                packs.mapping(pack)[app.key]?.let { packs.load(pack, it) }?.let { return it }
            }
        }
        return runCatching { pm.getActivityIcon(app.component) }.getOrNull()
    }

    @Synchronized
    private fun draw(d: Drawable?, label: String, shape: String, px: Int): Bitmap {
        val size = max(px, 16)
        val content = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(content)
        var masked = shape != "ninguna"
        paint.shader = null
        when {
            d == null -> {
                // Sin icono disponible: placa de color estable con las iniciales.
                masked = true
                val hue = ((label.hashCode() % 360) + 360) % 360
                canvas.drawColor(Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.5f, 0.42f)))
                paint.color = 0xFFEDEAE3.toInt()
                paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                paint.textSize = size * 0.34f
                paint.textAlign = Paint.Align.CENTER
                val words = label.split(' ', '-', '_').filter { it.isNotBlank() }
                val initials = (if (words.size >= 2) words.take(2).joinToString("") { it.take(1) } else label.take(2))
                    .uppercase().ifEmpty { "?" }
                val bounds = Rect()
                paint.getTextBounds(initials, 0, initials.length, bounds)
                canvas.drawText(initials, size / 2f, size / 2f + bounds.height() / 2f, paint)
            }
            masked && d is AdaptiveIconDrawable -> {
                // Las capas miden 108dp y solo se ven 72dp: sobresalen un 25 % por cada lado.
                val extra = (size * 0.25f).toInt()
                for (layer in listOf(d.background, d.foreground)) {
                    layer?.setBounds(-extra, -extra, size + extra, size + extra)
                    layer?.draw(canvas)
                }
            }
            masked && d is BitmapDrawable && d.bitmap != null -> {
                // Imagen propia: recorte centrado para que llene la forma.
                val b = d.bitmap
                val side = minOf(b.width, b.height)
                val src = Rect((b.width - side) / 2, (b.height - side) / 2, (b.width + side) / 2, (b.height + side) / 2)
                paint.color = Color.WHITE
                canvas.drawBitmap(b, src, Rect(0, 0, size, size), paint)
            }
            else -> { d.setBounds(0, 0, size, size); d.draw(canvas) }
        }
        if (!masked) return content
        // Máscara con borde suavizado: se pinta la forma usando el contenido como textura.
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        paint.color = Color.WHITE
        paint.shader = BitmapShader(content, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        Canvas(out).drawPath(shapePath(if (shape == "ninguna") "squircle" else shape, size.toFloat()), paint)
        paint.shader = null
        content.recycle()
        return out
    }
}
