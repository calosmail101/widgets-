package dev.palanca.launcher.system

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.view.Choreographer
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import dev.palanca.launcher.Env
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Puntero controlado con el mando para apps que solo entienden toques.
 *
 * Mantén L3 + R3 para activarlo o apagarlo. Con el puntero activo:
 * cruceta o stick = mover · A = tocar · X = toque largo · B = atrás ·
 * L1/R1 = desplazar arriba/abajo · START = salir.
 *
 * Mientras está apagado no intercepta nada: los juegos reciben el mando intacto.
 * En Android 14+ también lee los sticks; en versiones anteriores solo puede leer teclas,
 * así que el movimiento depende de que la cruceta llegue como teclas DPAD.
 */
class PointerService : AccessibilityService() {
    companion object {
        @Volatile var instance: PointerService? = null
            private set
    }

    private var active = false
    private var cursor: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var x = 0f
    private var y = 0f
    private var screenW = 0
    private var screenH = 0
    private var sizePx = 0

    private var l3 = false
    private var r3 = false
    private var comboUsed = false
    private val held = HashSet<Int>()
    private var stickX = 0f
    private var stickY = 0f
    private var looping = false
    private var lastFrame = 0L

    private val wm get() = getSystemService(Context.WINDOW_SERVICE) as WindowManager

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onUnbind(intent: android.content.Intent?): Boolean {
        setActive(false); instance = null
        return super.onUnbind(intent)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_THUMBL -> l3 = down
            KeyEvent.KEYCODE_BUTTON_THUMBR -> r3 = down
        }
        if (l3 && r3 && !comboUsed) { comboUsed = true; setActive(!active); return true }
        if (!l3 && !r3) comboUsed = false
        if (!active) return false

        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (down) held += event.keyCode else held -= event.keyCode
                startLoop(); true
            }
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER -> { if (!down) tap(60); true }
            KeyEvent.KEYCODE_BUTTON_X -> { if (!down) tap(650); true }
            KeyEvent.KEYCODE_BUTTON_B -> { if (!down) performGlobalAction(GLOBAL_ACTION_BACK); true }
            KeyEvent.KEYCODE_BUTTON_L1 -> { if (!down) swipe(+1); true }
            KeyEvent.KEYCODE_BUTTON_R1 -> { if (!down) swipe(-1); true }
            KeyEvent.KEYCODE_BUTTON_START -> { if (!down) setActive(false); true }
            KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR -> true
            else -> false
        }
    }

    /** Android 14+: solo llega mientras el puntero está activo (ver [setActive]). */
    override fun onMotionEvent(event: MotionEvent) {
        if (!active || event.action != MotionEvent.ACTION_MOVE) return
        fun pick(a: Float, b: Float, c: Float) = listOf(a, b, c).maxBy { abs(it) }
        stickX = pick(event.getAxisValue(MotionEvent.AXIS_Z), event.getAxisValue(MotionEvent.AXIS_X), event.getAxisValue(MotionEvent.AXIS_HAT_X))
        stickY = pick(event.getAxisValue(MotionEvent.AXIS_RZ), event.getAxisValue(MotionEvent.AXIS_Y), event.getAxisValue(MotionEvent.AXIS_HAT_Y))
        startLoop()
    }

    private fun setActive(on: Boolean) {
        if (on == active) return
        active = on
        held.clear(); stickX = 0f; stickY = 0f
        if (Build.VERSION.SDK_INT >= 34) runCatching {
            // Los ejes del mando se capturan solo con el puntero activo; apagado, van al juego.
            serviceInfo = serviceInfo.apply { motionEventSources = if (on) InputDevice.SOURCE_JOYSTICK else 0 }
        }
        if (on) show() else hide()
        Toast.makeText(this, if (on) "Puntero activado · START para salir" else "Puntero desactivado", Toast.LENGTH_SHORT).show()
    }

    private fun show() {
        val size = Point()
        if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.let { size.set(it.width(), it.height()) }
        else @Suppress("DEPRECATION") wm.defaultDisplay.getRealSize(size)
        screenW = size.x; screenH = size.y
        x = screenW / 2f; y = screenH / 2f
        sizePx = (resources.displayMetrics.density * 34).toInt()
        val accent = if (Env.ready) Env.cfg.value.look.accent.toInt() else 0xFFFF6A2B.toInt()
        val view = object : View(this) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(canvas: Canvas) {
                val c = width / 2f
                paint.style = Paint.Style.STROKE; paint.strokeWidth = width * 0.12f; paint.color = 0xCC000000.toInt()
                canvas.drawCircle(c, c, c * 0.72f, paint)
                paint.strokeWidth = width * 0.07f; paint.color = accent
                canvas.drawCircle(c, c, c * 0.72f, paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(c, c, c * 0.16f, paint)
            }
        }
        val lp = WindowManager.LayoutParams(
            sizePx, sizePx, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        params = lp; cursor = view
        place()
        runCatching { wm.addView(view, lp) }
    }

    private fun hide() {
        looping = false
        cursor?.let { runCatching { wm.removeView(it) } }
        cursor = null
    }

    private fun place() {
        val lp = params ?: return
        lp.x = (x - sizePx / 2f).toInt(); lp.y = (y - sizePx / 2f).toInt()
        cursor?.takeIf { it.isAttachedToWindow }?.let { runCatching { wm.updateViewLayout(it, lp) } }
    }

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(now: Long) {
            if (!looping || !active) { looping = false; return }
            val dt = ((now - lastFrame) / 1e9f).coerceIn(0f, 0.05f)
            lastFrame = now
            var dx = stickX; var dy = stickY
            if (hypot(dx, dy) < 0.18f) { dx = 0f; dy = 0f }
            if (KeyEvent.KEYCODE_DPAD_LEFT in held) dx -= 0.8f
            if (KeyEvent.KEYCODE_DPAD_RIGHT in held) dx += 0.8f
            if (KeyEvent.KEYCODE_DPAD_UP in held) dy -= 0.8f
            if (KeyEvent.KEYCODE_DPAD_DOWN in held) dy += 0.8f
            if (dx == 0f && dy == 0f) { looping = false; return }
            // Curva cuadrática: fino cerca del centro, rápido a fondo.
            val speed = screenW * 0.9f * hypot(dx, dy).coerceAtMost(1.2f)
            x = (x + dx * speed * dt).coerceIn(0f, screenW - 1f)
            y = (y + dy * speed * dt).coerceIn(0f, screenH - 1f)
            place()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun startLoop() {
        if (looping) return
        looping = true
        lastFrame = System.nanoTime()
        Choreographer.getInstance().postFrameCallback(frame)
    }

    private fun tap(ms: Long) {
        val path = Path().apply { moveTo(x, y) }
        runCatching {
            dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, ms)).build(), null, null)
        }
    }

    private fun swipe(direction: Int) {
        val path = Path().apply { moveTo(x, y); lineTo(x, (y + direction * screenH * 0.3f).coerceIn(1f, screenH - 2f)) }
        runCatching {
            dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 220)).build(), null, null)
        }
    }
}
