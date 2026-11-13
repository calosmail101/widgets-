package dev.palanca.launcher

import android.view.KeyEvent
import dev.palanca.launcher.data.Cat
import dev.palanca.launcher.data.classify
import dev.palanca.launcher.input.Btn
import dev.palanca.launcher.input.GamepadInput
import dev.palanca.launcher.input.PadEvent
import dev.palanca.launcher.input.Scheduler
import dev.palanca.launcher.ui.gridMove
import org.junit.Assert.assertEquals
import org.junit.Test

/** Reloj falso: las tareas se ejecutan al avanzar el tiempo a mano. */
private class FakeScheduler : Scheduler {
    var now = 0L
    private val tasks = ArrayList<Pair<Long, Runnable>>()
    override fun post(delayMs: Long, task: Runnable) { tasks += (now + delayMs) to task }
    override fun cancel(task: Runnable) { tasks.removeAll { it.second === task } }
    fun advance(ms: Long) {
        val end = now + ms
        while (true) {
            val next = tasks.filter { it.first <= end }.minByOrNull { it.first } ?: break
            tasks.remove(next); now = next.first; next.second.run()
        }
        now = end
    }
}

class InputTest {
    private val clock = FakeScheduler()
    private val events = ArrayList<PadEvent>()
    private var swap = false
    private val input = GamepadInput(clock, { swap }, { events += it })

    private fun axes(lx: Float = 0f, ly: Float = 0f, hatX: Float = 0f, hatY: Float = 0f, rx: Float = 0f, ry: Float = 0f, lt: Float = 0f) =
        input.onAxes(lx, ly, hatX, hatY, rx, ry, lt, 0f)

    @Test fun normalButtonsFireTheInstantTheyArePressed() {
        input.onKey(KeyEvent.KEYCODE_BUTTON_A, true, 0)
        assertEquals(listOf(PadEvent(Btn.A)), events)
        clock.advance(GamepadInput.LONG_MS * 2) // mantenerlo no genera pulsación larga ni repeticiones
        input.onKey(KeyEvent.KEYCODE_BUTTON_A, false, 0)
        assertEquals(1, events.size)
    }

    @Test fun holdableButtonsFireOnRelease() {
        input.onKey(KeyEvent.KEYCODE_BUTTON_START, true, 0)
        assertEquals(emptyList<PadEvent>(), events)
        clock.advance(100)
        input.onKey(KeyEvent.KEYCODE_BUTTON_START, false, 0)
        assertEquals(listOf(PadEvent(Btn.START)), events)
    }

    @Test fun longPressFiresOnceAndSuppressesShort() {
        input.onKey(KeyEvent.KEYCODE_BUTTON_SELECT, true, 0)
        clock.advance(GamepadInput.LONG_MS + 10)
        input.onKey(KeyEvent.KEYCODE_BUTTON_SELECT, false, 0)
        assertEquals(listOf(PadEvent(Btn.SELECT, long = true)), events)
    }

    @Test fun dpadKeyRepeatsWhileHeldAndStopsOnRelease() {
        input.onKey(KeyEvent.KEYCODE_DPAD_RIGHT, true, 0)
        input.onKey(KeyEvent.KEYCODE_DPAD_RIGHT, true, 1) // repetición del sistema: se ignora
        assertEquals(1, events.size)
        clock.advance(GamepadInput.FIRST_REPEAT_MS + GamepadInput.REPEAT_MS * 2)
        assertEquals(4, events.size)
        assertEquals(PadEvent(Btn.RIGHT, repeat = true), events.last())
        input.onKey(KeyEvent.KEYCODE_DPAD_RIGHT, false, 0)
        clock.advance(2000)
        assertEquals(4, events.size)
    }

    @Test fun stickNeedsAClearPushAndHasHysteresis() {
        axes(lx = 0.4f)
        assertEquals(0, events.size)
        axes(lx = 0.8f)
        assertEquals(listOf(PadEvent(Btn.RIGHT)), events)
        axes(lx = 0.45f) // sigue por encima del umbral de salida: no se suelta ni se repite la pulsación
        assertEquals(1, events.size)
        axes(lx = 0.1f)
        clock.advance(2000)
        assertEquals(1, events.size)
    }

    @Test fun hatDpadWorksAndVerticalWinsWhenDominant() {
        axes(hatY = -1f)
        axes()
        axes(lx = 0.3f, ly = 0.9f)
        assertEquals(listOf(PadEvent(Btn.UP), PadEvent(Btn.DOWN)), events)
    }

    @Test fun rightStickAndAnalogTrigger() {
        axes(rx = -0.9f)
        axes(lt = 0.9f)
        axes(lt = 0f)
        assertEquals(listOf(PadEvent(Btn.RS_LEFT), PadEvent(Btn.L2)), events)
    }

    @Test fun nintendoLayoutSwapsFaceButtons() {
        swap = true
        input.onKey(KeyEvent.KEYCODE_BUTTON_A, true, 0); input.onKey(KeyEvent.KEYCODE_BUTTON_A, false, 0)
        input.onKey(KeyEvent.KEYCODE_BUTTON_Y, true, 0); input.onKey(KeyEvent.KEYCODE_BUTTON_Y, false, 0)
        assertEquals(listOf(PadEvent(Btn.B), PadEvent(Btn.X)), events)
    }

    @Test fun nonGamepadKeysAreNotConsumed() {
        assertEquals(false, input.onKey(KeyEvent.KEYCODE_VOLUME_UP, true, 0))
    }

    @Test fun gridMoveStaysInsideAndFallsToLastItem() {
        // 10 elementos en 4 columnas: filas 0-3, 4-7, 8-9
        assertEquals(0, gridMove(0, 10, 4, Btn.LEFT))
        assertEquals(3, gridMove(3, 10, 4, Btn.RIGHT))
        assertEquals(9, gridMove(9, 10, 4, Btn.RIGHT))
        assertEquals(9, gridMove(7, 10, 4, Btn.DOWN)) // no hay elemento debajo: va al último
        assertEquals(9, gridMove(9, 10, 4, Btn.DOWN))
        assertEquals(1, gridMove(5, 10, 4, Btn.UP))
        assertEquals(0, gridMove(0, 0, 4, Btn.DOWN))
    }

    @Test fun categories() {
        assertEquals(Cat.EMULADORES, classify("org.dolphinemu.dolphinemu", -1, false))
        assertEquals(Cat.JUEGOS, classify("com.example.game", android.content.pm.ApplicationInfo.CATEGORY_GAME, false))
        assertEquals(Cat.MULTIMEDIA, classify("com.example.video", android.content.pm.ApplicationInfo.CATEGORY_VIDEO, false))
        assertEquals(Cat.SISTEMA, classify("com.android.settings", -1, true))
        assertEquals(Cat.APPS, classify("com.example.notes", -1, false))
    }
}
