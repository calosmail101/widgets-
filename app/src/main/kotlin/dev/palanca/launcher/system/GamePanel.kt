package dev.palanca.launcher.system

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import dev.palanca.launcher.Env
import java.util.concurrent.Executors

/**
 * Panel rápido dentro del juego (L1 + R1 + X): modo de rendimiento, ventilador, frecuencia de pantalla,
 * brillo y, si el juego usa el driver Heaven FG, la generación de fotogramas, sin salir del juego.
 * Mientras está abierto, la cruceta y B lo manejan a él y no llegan al juego.
 */
class GamePanel(private val service: AccessibilityService) {
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val io = Executors.newSingleThreadExecutor()
    private var view: PanelView? = null
    private var row = 0

    private var performance = 0
    private var fan = 0
    private var hz = 0
    private var brightness = 0
    // Heaven FG: solo se muestra si el juego en marcha tiene cargado ese driver (se comprueba al abrir).
    private var game: String? = null
    private var fgLoaded = false
    private var fg = FrameGen.DEFAULT

    val open get() = view != null

    private fun rows() = buildList {
        if (performance >= 0) add("modo")
        if (fan >= 0) add("ventilador")
        if (hz > 0) add("pantalla")
        if (fgLoaded) add("fg")
        add("brillo")
    }

    fun toggle() = if (open) close() else show()

    private fun show() {
        if (!Env.ready) return
        performance = DeviceModes.performance(); fan = DeviceModes.fan(); hz = DeviceModes.refresh(); brightness = Actions.brightness()
        row = 0
        // El juego: el que vio Profiles (si Heaven acaba de arrancar aún no lo sabe: se pregunta al sistema abajo).
        game = Profiles.current?.takeIf { it != Env.ctx.packageName }
        fgLoaded = false; fg = game?.let { Env.cfg.value.profiles[it]?.fg } ?: FrameGen.DEFAULT
        val d = service.resources.displayMetrics.density
        val v = PanelView(service)
        val lp = WindowManager.LayoutParams(
            (360 * d).toInt(), height(), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Con foco: la cruceta de la Pocket 6 son ejes (HAT) y Android solo los convierte en flechas para la
            // ventana enfocada. Mientras el panel está abierto, el juego no recibe la cruceta.
            // Sin FLAG_NOT_TOUCHABLE: Android limita al 80 % la opacidad de las capas que no aceptan toques y el
            // juego se transparentaba. NOT_TOUCH_MODAL deja que los toques fuera del panel lleguen al juego.
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START; x = (24 * d).toInt()
            alpha = 1f; windowAnimations = 0
        }
        if (runCatching { wm.addView(v, lp) }.isSuccess) { view = v; v.requestFocus() }
        val known = game
        io.execute {
            val g = known ?: FrameGen.foreground()?.takeIf { it != Env.ctx.packageName } ?: return@execute
            val loaded = FrameGen.loadedIn(g)
            v.post {
                if (!loaded || view !== v) return@post
                if (game == null) { game = g; fg = Env.cfg.value.profiles[g]?.fg ?: FrameGen.DEFAULT }
                fgLoaded = true
                runCatching { lp.height = height(); wm.updateViewLayout(v, lp) }
                redraw()
            }
        }
    }

    private fun height() = ((58 + rows().size * 44) * service.resources.displayMetrics.density).toInt()

    fun close() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }

    /** Teclas mientras el panel está abierto. Devuelve true si se consumen (no llegan al juego). */
    fun handleKey(event: KeyEvent): Boolean {
        if (!open) return false
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> if (down) { row = (row - 1 + rows().size) % rows().size; redraw() }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (down) { row = (row + 1) % rows().size; redraw() }
            KeyEvent.KEYCODE_DPAD_LEFT -> if (down) change(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BUTTON_A -> if (down) change(+1)
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> if (!down) close()
            // L1, R1 y X se dejan pasar: forman la combinación que abre y cierra el panel.
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_X -> return false
            else -> return false
        }
        return true
    }

    private fun <T> cycle(options: List<Pair<Int, T>>, current: Int, dir: Int): Int {
        val i = options.indexOfFirst { it.first == current }.coerceAtLeast(0)
        return options[(i + dir + options.size) % options.size].first
    }

    private fun change(dir: Int) {
        when (rows().getOrNull(row)) {
            "modo" -> { performance = cycle(DeviceModes.PERFORMANCE, performance, dir); val v = performance; io.execute { DeviceModes.setPerformance(v) } }
            "ventilador" -> { fan = cycle(DeviceModes.FAN, fan, dir); val v = fan; io.execute { DeviceModes.setFan(v) } }
            "pantalla" -> { hz = if (hz == 120) 60 else 120; val v = hz; io.execute { DeviceModes.setRefresh(v) } }
            "fg" -> {
                fg = cycle(FrameGen.MODES, fg, dir); val v = fg
                io.execute { FrameGen.set(v) }
                game?.let { Profiles.saveFrameGen(it, v) }
            }
            "brillo" -> if (Actions.canWriteSettings()) { brightness = (brightness + dir * 10).coerceIn(1, 100); Actions.setBrightness(brightness) }
        }
        redraw()
    }

    private fun redraw() { view?.invalidate() }

    private fun value(r: String) = when (r) {
        "modo" -> DeviceModes.name(DeviceModes.PERFORMANCE, performance)
        "ventilador" -> DeviceModes.name(DeviceModes.FAN, fan)
        "pantalla" -> "$hz Hz"
        "fg" -> FrameGen.name(fg)
        else -> if (Actions.canWriteSettings()) "$brightness %" else "sin permiso"
    }

    private fun title(r: String) = when (r) { "modo" -> "Modo"; "ventilador" -> "Ventilador"; "pantalla" -> "Pantalla"; "fg" -> "Generación de fotogramas"; else -> "Brillo" }

    private inner class PanelView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val accent = Env.cfg.value.look.accent.toInt()

        init { isFocusable = true; isFocusableInTouchMode = true }

        /** Flechas que Android crea a partir de la cruceta (ejes HAT) y botones que lleguen a la ventana. */
        override fun dispatchKeyEvent(event: KeyEvent): Boolean = handleKey(event) || super.dispatchKeyEvent(event)

        override fun onDraw(canvas: Canvas) {
            val d = resources.displayMetrics.density
            // Cristal oscuro con borde fino, como las ventanas de Heaven.
            paint.style = Paint.Style.FILL; paint.color = 0xFF101114.toInt()
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 26 * d, 26 * d, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.2f * d; paint.color = 0x33FFFFFF
            canvas.drawRoundRect(0.6f * d, 0.6f * d, width - 0.6f * d, height - 0.6f * d, 26 * d, 26 * d, paint)
            paint.style = Paint.Style.FILL
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            paint.textSize = 12 * d; paint.color = accent
            canvas.drawText("HEAVEN · AJUSTES RÁPIDOS", 22 * d, 30 * d, paint)
            rows().forEachIndexed { i, r ->
                val top = (44 + i * 44) * d
                if (i == row) {
                    paint.color = 0x22FFFFFF
                    canvas.drawRoundRect(10 * d, top, width - 10 * d, top + 38 * d, 14 * d, 14 * d, paint)
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = 2 * d; paint.color = accent
                    canvas.drawRoundRect(10 * d, top, width - 10 * d, top + 38 * d, 14 * d, 14 * d, paint)
                    paint.style = Paint.Style.FILL
                }
                val baseline = top + 19 * d - (paint.descent() + paint.ascent()) / 2
                paint.textSize = 15 * d
                paint.typeface = Typeface.create(Typeface.DEFAULT, if (i == row) Typeface.BOLD else Typeface.NORMAL)
                paint.color = if (i == row) 0xFFF2F3F5.toInt() else 0xFFA0A4AC.toInt()
                canvas.drawText(title(r), 24 * d, baseline, paint)
                val text = if (i == row) "‹  ${value(r)}  ›" else value(r)
                paint.color = if (i == row) accent else 0xFFA0A4AC.toInt()
                canvas.drawText(text, width - 24 * d - paint.measureText(text), baseline, paint)
            }
        }
    }
}
