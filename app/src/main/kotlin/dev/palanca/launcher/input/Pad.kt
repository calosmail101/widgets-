package dev.palanca.launcher.input

import android.view.KeyEvent
import kotlin.math.abs

/** Botones lógicos del mando. RS_* son "empujones" del stick derecho. */
enum class Btn {
    UP, DOWN, LEFT, RIGHT,
    A, B, X, Y, L1, R1, L2, R2, L3, R3, START, SELECT,
    RS_UP, RS_DOWN, RS_LEFT, RS_RIGHT;

    val isDirection get() = this == UP || this == DOWN || this == LEFT || this == RIGHT
}

data class PadEvent(val btn: Btn, val long: Boolean = false, val repeat: Boolean = false)

/** Reloj inyectable para poder probar las repeticiones sin Android. */
interface Scheduler {
    fun post(delayMs: Long, task: Runnable)
    fun cancel(task: Runnable)
}

/**
 * Convierte teclas y ejes crudos en [PadEvent].
 *
 * No depende del sistema de foco de Android: la cruceta puede llegar como teclas o como
 * eje HAT, y el stick como eje X/Y; todo acaba en las mismas cuatro direcciones con
 * repetición propia. Los botones se emiten al soltar (pulsación corta) o al mantenerlos
 * [LONG_MS] (pulsación larga), nunca ambos.
 */
class GamepadInput(
    private val scheduler: Scheduler,
    private val swapAB: () -> Boolean,
    private val emit: (PadEvent) -> Unit,
) {
    private inner class Repeater {
        var current: Btn? = null
            private set
        private var count = 0
        private val tick = object : Runnable {
            override fun run() {
                val b = current ?: return
                count++
                emit(PadEvent(b, repeat = true))
                scheduler.post(if (count > 8) FAST_MS else REPEAT_MS, this)
            }
        }

        fun set(btn: Btn?) {
            if (btn == current) return
            scheduler.cancel(tick)
            current = btn
            count = 0
            if (btn != null) {
                emit(PadEvent(btn))
                scheduler.post(FIRST_REPEAT_MS, tick)
            }
        }
    }

    private val left = Repeater()
    private val right = Repeater()
    private var keyDir: Btn? = null
    private var axisDir: Btn? = null
    private var rightDir: Btn? = null

    private val held = HashMap<Btn, Runnable>()
    private val longFired = HashSet<Btn>()
    private var triggerKeysSeen = false
    private var l2Axis = false
    private var r2Axis = false

    /** Valores analógicos del stick derecho (-1..1) para quien quiera leerlos. */
    var rightX = 0f; private set
    var rightY = 0f; private set

    fun mapKey(keyCode: Int): Btn? {
        val swap = swapAB()
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> Btn.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> Btn.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> Btn.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> Btn.RIGHT
            KeyEvent.KEYCODE_BUTTON_A -> if (swap) Btn.B else Btn.A
            KeyEvent.KEYCODE_BUTTON_B -> if (swap) Btn.A else Btn.B
            KeyEvent.KEYCODE_BUTTON_X -> if (swap) Btn.Y else Btn.X
            KeyEvent.KEYCODE_BUTTON_Y -> if (swap) Btn.X else Btn.Y
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> Btn.A
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> Btn.B
            KeyEvent.KEYCODE_BUTTON_L1 -> Btn.L1
            KeyEvent.KEYCODE_BUTTON_R1 -> Btn.R1
            KeyEvent.KEYCODE_BUTTON_L2 -> Btn.L2
            KeyEvent.KEYCODE_BUTTON_R2 -> Btn.R2
            KeyEvent.KEYCODE_BUTTON_THUMBL -> Btn.L3
            KeyEvent.KEYCODE_BUTTON_THUMBR -> Btn.R3
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> Btn.START
            KeyEvent.KEYCODE_BUTTON_SELECT -> Btn.SELECT
            else -> null
        }
    }

    /** @return true si la tecla es del mando y queda consumida. */
    fun onKey(keyCode: Int, down: Boolean, repeatCount: Int): Boolean {
        val btn = mapKey(keyCode) ?: return false
        if (btn.isDirection) {
            if (down) {
                if (repeatCount == 0) keyDir = btn
            } else if (keyDir == btn) keyDir = null
            left.set(keyDir ?: axisDir)
            return true
        }
        if (btn == Btn.L2 || btn == Btn.R2) triggerKeysSeen = true
        if (down) {
            if (repeatCount == 0 && btn !in held) press(btn)
        } else release(btn)
        return true
    }

    /**
     * Ejes del mando. [hatX]/[hatY] son la cruceta cuando llega como eje; [lx]/[ly] el stick
     * izquierdo; [rx]/[ry] el derecho; [lt]/[rt] los gatillos analógicos.
     */
    fun onAxes(lx: Float, ly: Float, hatX: Float, hatY: Float, rx: Float, ry: Float, lt: Float, rt: Float) {
        axisDir = direction(hatX, hatY, 0.5f, 0.5f, axisDir, Btn.LEFT, Btn.RIGHT, Btn.UP, Btn.DOWN)
            ?: direction(lx, ly, STICK_ON, STICK_OFF, axisDir, Btn.LEFT, Btn.RIGHT, Btn.UP, Btn.DOWN)
        left.set(keyDir ?: axisDir)

        rightX = rx; rightY = ry
        rightDir = direction(rx, ry, STICK_ON, STICK_OFF, rightDir, Btn.RS_LEFT, Btn.RS_RIGHT, Btn.RS_UP, Btn.RS_DOWN)
        right.set(rightDir)

        if (!triggerKeysSeen) {
            val l = if (l2Axis) lt > 0.3f else lt > 0.6f
            val r = if (r2Axis) rt > 0.3f else rt > 0.6f
            if (l != l2Axis) { l2Axis = l; if (l) press(Btn.L2) else release(Btn.L2) }
            if (r != r2Axis) { r2Axis = r; if (r) press(Btn.R2) else release(Btn.R2) }
        }
    }

    /** Suelta todo (al perder el foco de la ventana, por ejemplo). */
    fun reset() {
        keyDir = null; axisDir = null; rightDir = null
        left.set(null); right.set(null)
        held.values.forEach(scheduler::cancel)
        held.clear(); longFired.clear()
        l2Axis = false; r2Axis = false
    }

    private fun press(btn: Btn) {
        val task = Runnable {
            longFired += btn
            emit(PadEvent(btn, long = true))
        }
        held[btn] = task
        scheduler.post(LONG_MS, task)
    }

    private fun release(btn: Btn) {
        val task = held.remove(btn) ?: return
        scheduler.cancel(task)
        if (!longFired.remove(btn)) emit(PadEvent(btn))
    }

    private fun direction(
        x: Float, y: Float, on: Float, off: Float, previous: Btn?,
        l: Btn, r: Btn, u: Btn, d: Btn,
    ): Btn? {
        val ax = abs(x); val ay = abs(y)
        // Histéresis: cuesta más entrar que mantenerse, para que el stick no "tiemble".
        val threshold = if (previous != null) off else on
        if (ax < threshold && ay < threshold) return null
        // Mantén el eje anterior salvo que el otro gane con claridad (evita diagonales nerviosas).
        val prevHorizontal = previous == l || previous == r
        val horizontal = when {
            previous == null -> ax >= ay
            prevHorizontal -> ax >= ay * 0.7f
            else -> ax * 0.7f > ay
        }
        return if (horizontal) (if (x < 0) l else r) else (if (y < 0) u else d)
    }

    companion object {
        const val LONG_MS = 450L
        const val FIRST_REPEAT_MS = 340L
        const val REPEAT_MS = 110L
        const val FAST_MS = 60L
        const val STICK_ON = 0.6f
        const val STICK_OFF = 0.35f
    }
}
