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
import android.os.SystemClock
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Puntero controlado con el mando para apps que solo entienden toques.
 *
 * Mantén L3 + R3 para activarlo o apagarlo. Con el puntero activo:
 * stick izquierdo o cruceta = mover · A = tocar · X = toque largo · B = atrás ·
 * stick derecho o L1/R1 = desplazar · START = salir.
 *
 * Cómo lee los sticks: mientras está activo, la ventana del cursor toma el foco de entrada,
 * así que Android le entrega a ella los ejes del mando (funciona en cualquier versión).
 * Apagado no hay ventana ni foco: los juegos reciben el mando intacto.
 */
class PointerService : AccessibilityService() {
    companion object {
        @Volatile var instance: PointerService? = null
            private set

        // Muelle del anillo: poco amortiguado para que rebote una vez y se asiente.
        private const val STIFFNESS = 320f
        private const val DAMPING = 15f
    }

    private var active = false
    private var cursor: CursorView? = null
    private var params: WindowManager.LayoutParams? = null
    private var x = 0f
    private var y = 0f
    private var screenW = 0
    private var screenH = 0
    private var sizePx = 0
    private var density = 1f

    // Anillo: desplazamiento respecto al punto y su velocidad.
    private var ringX = 0f
    private var ringY = 0f
    private var ringVx = 0f
    private var ringVy = 0f

    private var l1 = false
    private var r1 = false
    private var select = false
    private var startKey = false
    private var shotCombo = false
    private var xKey = false
    private var panelCombo = false
    private val panel by lazy { GamePanel(this) }
    private var hudCombo = false
    private var hud: HudView? = null
    private var hudJob: kotlinx.coroutines.Job? = null
    private var l3 = false
    private var r3 = false
    private var comboUsed = false
    private val held = HashSet<Int>()
    private var stickX = 0f
    private var stickY = 0f
    private var scrollY = 0f
    private var lastSwipe = 0L
    private var looping = false
    private var lastFrame = 0L

    private val wm get() = getSystemService(Context.WINDOW_SERVICE) as WindowManager

    override fun onServiceConnected() { instance = this }
    /** Cada cambio de ventana dice qué app está delante: sirve para los perfiles por juego. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (Env.ready) runCatching { Profiles.onForeground(pkg) }
    }
    override fun onInterrupt() = Unit
    override fun onUnbind(intent: android.content.Intent?): Boolean {
        setActive(false); if (hud != null) toggleHud(); panel.close(); instance = null
        return super.onUnbind(intent)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean = handleKey(event)

    private fun handleKey(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        // Estadísticas en juego: L1 + R1 + SELECT a la vez. No se consumen: el juego sigue recibiéndolas.
        when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_L1 -> l1 = down
            KeyEvent.KEYCODE_BUTTON_R1 -> r1 = down
            KeyEvent.KEYCODE_BUTTON_SELECT -> select = down
        }
        if (l1 && r1 && select) { if (!hudCombo) { hudCombo = true; toggleHud() } } else hudCombo = false
        // Captura: L1 + R1 + START en cualquier app. Tampoco se consumen.
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_START) startKey = down
        if (l1 && r1 && startKey) { if (!shotCombo) { shotCombo = true; Playtime.screenshot() } } else shotCombo = false
        // Panel rápido dentro del juego: L1 + R1 + X lo abre o lo cierra; abierto, maneja la cruceta y B.
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_X) xKey = down
        if (l1 && r1 && xKey) { if (!panelCombo) { panelCombo = true; panel.toggle() } } else panelCombo = false
        if (panel.open && panel.handleKey(event)) return true
        val toggleKey = if (Env.ready) Env.cfg.value.pointerKey else 0
        if (toggleKey != 0) {
            // Botón propio elegido por el usuario: enciende o apaga al soltarlo y nunca llega a la app.
            if (event.keyCode == toggleKey) { if (!down) setActive(!active); return true }
        } else {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_THUMBL -> l3 = down
                KeyEvent.KEYCODE_BUTTON_THUMBR -> r3 = down
            }
            if (l3 && r3 && !comboUsed) { comboUsed = true; setActive(!active); return true }
            if (!l3 && !r3) comboUsed = false
        }
        if (!active) return false

        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (down) held += event.keyCode else held -= event.keyCode
                startLoop(); true
            }
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER -> { if (!down) tap(60); true }
            KeyEvent.KEYCODE_BUTTON_X -> { if (!down) tap(650); true }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> {
                if (!down && !performGlobalAction(GLOBAL_ACTION_BACK)) Thread { Privileged.run("input keyevent 4") }.start()
                true
            }
            KeyEvent.KEYCODE_BUTTON_L1 -> { if (!down) swipe(+1); true }
            KeyEvent.KEYCODE_BUTTON_R1 -> { if (!down) swipe(-1); true }
            KeyEvent.KEYCODE_BUTTON_START -> { if (!down) setActive(false); true }
            KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR -> true
            else -> false
        }
    }

    private fun onSticks(event: MotionEvent) {
        fun pick(a: Float, b: Float) = if (abs(a) >= abs(b)) a else b
        stickX = pick(event.getAxisValue(MotionEvent.AXIS_X), event.getAxisValue(MotionEvent.AXIS_HAT_X))
        stickY = pick(event.getAxisValue(MotionEvent.AXIS_Y), event.getAxisValue(MotionEvent.AXIS_HAT_Y))
        scrollY = event.getAxisValue(MotionEvent.AXIS_RZ)
        startLoop()
    }

    /** Ventana del cursor. Toma el foco de entrada para recibir los ejes del mando. */
    private inner class CursorView(context: Context, private val accent: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        init { isFocusable = true; isFocusableInTouchMode = true }

        override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
            if (event.isFromSource(InputDevice.SOURCE_JOYSTICK) && event.action == MotionEvent.ACTION_MOVE) {
                onSticks(event)
                return true
            }
            return super.dispatchGenericMotionEvent(event)
        }

        // Normalmente las teclas ya llegan por onKeyEvent; esto cubre el caso de que el sistema no las filtre.
        override fun dispatchKeyEvent(event: KeyEvent): Boolean = handleKey(event) || super.dispatchKeyEvent(event)

        override fun onDraw(canvas: Canvas) {
            val c = width / 2f
            val ring = 9f * density
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3.4f * density; paint.color = 0xB0000000.toInt()
            canvas.drawCircle(c + ringX, c + ringY, ring, paint)
            paint.strokeWidth = 1.8f * density; paint.color = accent
            canvas.drawCircle(c + ringX, c + ringY, ring, paint)
            paint.style = Paint.Style.FILL
            paint.color = 0xB0000000.toInt()
            canvas.drawCircle(c, c, 3.6f * density, paint)
            paint.color = accent
            canvas.drawCircle(c, c, 2.6f * density, paint)
        }
    }

    /** Tira de estadísticas sobre cualquier app: una píldora translúcida arriba a la izquierda, sin foco ni toques. */
    private inner class HudView(context: Context) : View(context) {
        var text = "…"
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            val d = resources.displayMetrics.density
            paint.textSize = 13.5f * d
            paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            val w = paint.measureText(text) + 22 * d
            paint.style = Paint.Style.FILL; paint.color = 0x99101114.toInt()
            canvas.drawRoundRect(0f, 0f, w, height.toFloat(), height / 2f, height / 2f, paint)
            paint.color = 0xFFF2F3F5.toInt()
            canvas.drawText(text, 11 * d, height / 2f - (paint.descent() + paint.ascent()) / 2, paint)
        }
    }

    private fun toggleHud() {
        hud?.let { view ->
            hudJob?.cancel(); hudJob = null
            runCatching { wm.removeView(view) }
            hud = null
            Thread { FpsMeter.stop() }.start()
            return
        }
        if (!Env.ready) return
        val d = resources.displayMetrics.density
        val view = HudView(this)
        val lp = WindowManager.LayoutParams(
            (520 * d).toInt(), (26 * d).toInt(), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; x = (10 * d).toInt(); y = (8 * d).toInt() }
        if (runCatching { wm.addView(view, lp) }.isFailure) return
        hud = view
        val metrics = listOf(Metric.CPU_TEMP, Metric.GPU_TEMP, Metric.GPU_LOAD, Metric.POWER, Metric.BATTERY)
        // Una lectura por segundo, fuera del hilo de la interfaz; solo mientras la tira está visible.
        hudJob = Env.scope.launch {
            withContext(Dispatchers.IO) { FpsMeter.start() }
            while (true) {
                val line = withContext(Dispatchers.IO) {
                    val fps = FpsMeter.read()
                    val r = Telemetry.snapshot(metrics)
                    fun v(m: Metric) = r[m]?.takeIf { it.available }?.value ?: "—"
                    "FPS ${fps ?: "—"}   CPU ${v(Metric.CPU_TEMP)}°   GPU ${v(Metric.GPU_TEMP)}° ${v(Metric.GPU_LOAD)}%   ${v(Metric.POWER)} W   ${v(Metric.BATTERY)}%"
                }
                view.text = line; view.invalidate()
                delay(700)
            }
        }
    }

    private fun setActive(on: Boolean) {
        if (on == active) return
        active = on
        held.clear(); stickX = 0f; stickY = 0f; scrollY = 0f
        if (on) show() else hide()
        Toast.makeText(this, if (on) "Puntero activado · START para salir" else "Puntero desactivado", Toast.LENGTH_SHORT).show()
    }

    private fun show() {
        val size = Point()
        if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.let { size.set(it.width(), it.height()) }
        else @Suppress("DEPRECATION") wm.defaultDisplay.getRealSize(size)
        screenW = size.x; screenH = size.y
        x = screenW / 2f; y = screenH / 2f
        density = resources.displayMetrics.density
        sizePx = (density * 64).toInt()
        ringX = 0f; ringY = 0f; ringVx = 0f; ringVy = 0f
        val accent = if (Env.ready) Env.cfg.value.look.accent.toInt() else 0xFFFF6A2B.toInt()
        val view = CursorView(this, accent)
        // Sin FLAG_NOT_FOCUSABLE: la ventana recibe teclas y ejes. Sigue sin recibir toques, que pasan a la app.
        val lp = WindowManager.LayoutParams(
            sizePx, sizePx, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        params = lp; cursor = view
        place()
        runCatching { wm.addView(view, lp); view.requestFocus() }
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

    /**
     * Un fotograma de movimiento. Solo corre mientras el puntero se mueve o el anillo
     * todavía rebota; quieto no consume nada.
     */
    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(now: Long) {
            if (!looping || !active) { looping = false; return }
            val dt = ((now - lastFrame) / 1e9f).coerceIn(0.001f, 0.05f)
            lastFrame = now
            var dx = stickX; var dy = stickY
            if (hypot(dx, dy) < 0.18f) { dx = 0f; dy = 0f }
            if (KeyEvent.KEYCODE_DPAD_LEFT in held) dx -= 0.8f
            if (KeyEvent.KEYCODE_DPAD_RIGHT in held) dx += 0.8f
            if (KeyEvent.KEYCODE_DPAD_UP in held) dy -= 0.8f
            if (KeyEvent.KEYCODE_DPAD_DOWN in held) dy += 0.8f
            val moving = dx != 0f || dy != 0f
            if (moving) {
                // Curva cuadrática: fino cerca del centro, rápido a fondo.
                val speed = screenW * 0.9f * hypot(dx, dy).coerceAtMost(1.2f)
                val nx = (x + dx * speed * dt).coerceIn(0f, screenW - 1f)
                val ny = (y + dy * speed * dt).coerceIn(0f, screenH - 1f)
                // El anillo se queda atrás lo que avanza el punto y el muelle lo trae de vuelta.
                ringX -= nx - x; ringY -= ny - y
                x = nx; y = ny
                place()
            }
            ringVx += (-STIFFNESS * ringX - DAMPING * ringVx) * dt
            ringVy += (-STIFFNESS * ringY - DAMPING * ringVy) * dt
            ringX += ringVx * dt; ringY += ringVy * dt
            // El anillo se retrasa como mucho hasta que el punto toca su borde interior: nunca se sale.
            val limit = 5.2f * density
            val length = hypot(ringX, ringY)
            if (length > limit) { ringX *= limit / length; ringY *= limit / length }
            cursor?.invalidate()

            val scrolling = abs(scrollY) > 0.6f
            if (scrolling && SystemClock.uptimeMillis() - lastSwipe > 280) {
                lastSwipe = SystemClock.uptimeMillis()
                swipe(if (scrollY < 0) +1 else -1)
            }
            val settled = hypot(ringX, ringY) < 0.4f && hypot(ringVx, ringVy) < 6f
            if (!moving && !scrolling && settled) {
                ringX = 0f; ringY = 0f; ringVx = 0f; ringVy = 0f
                cursor?.invalidate()
                looping = false
                return
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun startLoop() {
        if (looping) return
        looping = true
        lastFrame = System.nanoTime()
        Choreographer.getInstance().postFrameCallback(frame)
    }

    /**
     * Toca en la posición del puntero. Primero con un gesto de accesibilidad; si el sistema lo
     * rechaza o lo cancela, con el comando de toque del servicio privilegiado de la consola.
     */
    private fun tap(ms: Long) {
        val px = x.toInt(); val py = y.toInt()
        // Nunca en el hilo principal: «input tap» espera a que la ventana tocada procese el toque, y si esa
        // ventana es la de Heaven (que está esperando aquí), se bloquean los dos hasta que Android avisa de
        // que Heaven no responde. Pasaba al tocar el óvalo de música con el puntero.
        fun fallback() {
            Thread { Privileged.run(if (ms > 300) "input swipe $px $py $px $py $ms" else "input tap $px $py") }.start()
        }
        val path = Path().apply { moveTo(x, y); lineTo(x + 1f, y + 1f) }
        val sent = runCatching {
            dispatchGesture(
                GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, ms)).build(),
                object : GestureResultCallback() {
                    override fun onCancelled(gestureDescription: GestureDescription?) = fallback()
                },
                null,
            )
        }.getOrDefault(false)
        if (!sent) fallback()
    }

    private fun swipe(direction: Int) {
        val path = Path().apply { moveTo(x, y); lineTo(x, (y + direction * screenH * 0.3f).coerceIn(1f, screenH - 2f)) }
        runCatching {
            dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 220)).build(), null, null)
        }
    }
}
