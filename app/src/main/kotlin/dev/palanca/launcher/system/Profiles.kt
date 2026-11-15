package dev.palanca.launcher.system

import android.os.Handler
import android.os.Looper
import dev.palanca.launcher.Env
import dev.palanca.launcher.data.Profile
import java.util.concurrent.Executors

/**
 * Perfiles por juego. El servicio del puntero avisa de cada app que pasa a primer plano (también las
 * que se abren desde Cocoon u otro frontend). Si la app tiene perfil, se guardan los ajustes de la
 * consola, se aplica el perfil y, al volver a Heaven o a una app sin perfil, se restauran.
 *
 * También vigila la temperatura mientras se juega y avisa una vez si la consola se calienta.
 */
object Profiles {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** App con el perfil aplicado ahora mismo, y los ajustes que había antes de aplicarlo. */
    private var active: String? = null
    private var saved: Profile? = null
    private var current: String? = null

    /** Paquetes que aparecen encima de un juego sin que se salga de él: no cuentan como cambio de app. */
    private val TRANSIENT = setOf(
        "com.android.systemui", "android", "com.rp.gameassistant", "com.google.android.inputmethod.latin",
        "com.android.permissioncontroller", "com.rp.mapping",
    )

    fun onForeground(pkg: String) {
        if (pkg in TRANSIENT || pkg == current) return
        current = pkg
        val ownLauncher = pkg == Env.ctx.packageName
        val profile = if (ownLauncher) null else Env.cfg.value.profiles[pkg]?.takeIf { !it.isEmpty() }
        when {
            profile != null && active != pkg -> {
                if (saved == null) saved = snapshot()
                active = pkg
                io.execute { apply(profile) }
            }
            profile == null && active != null -> restore()
        }
        if (!ownLauncher) startHeatWatch() else stopHeatWatch()
    }

    private fun snapshot() = Profile(
        performance = DeviceModes.performance().takeIf { it >= 0 },
        fan = DeviceModes.fan().takeIf { it >= 0 },
        fanSpeed = DeviceModes.fanSpeed(),
        hz = DeviceModes.refresh().takeIf { it > 0 },
    )

    private fun apply(p: Profile) {
        p.performance?.let { DeviceModes.setPerformance(it) }
        p.fan?.let { DeviceModes.setFan(it) }
        if (p.fan == 6) p.fanSpeed?.let { DeviceModes.setFanSpeed(it) }
        p.hz?.let { DeviceModes.setRefresh(it) }
    }

    private fun restore() {
        val back = saved
        active = null; saved = null
        if (back != null) io.execute { apply(back) }
    }

    // ---- Aviso de temperatura ----

    private const val HEAT_C = 45f
    private const val CHECK_MS = 30_000L
    private const val QUIET_MS = 10 * 60_000L
    private var watching = false
    private var lastAlert = 0L

    private val heatCheck = object : Runnable {
        override fun run() {
            if (!watching) return
            io.execute {
                val reading = Telemetry.snapshot(listOf(Metric.BAT_TEMP))[Metric.BAT_TEMP]
                val celsius = reading?.value?.toFloatOrNull()
                val now = System.currentTimeMillis()
                // Solo avisa si el ventilador no está ya fuerte (Sport o Personalizado) y no avisó hace poco.
                if (celsius != null && celsius >= HEAT_C && DeviceModes.fan() == 4 && now - lastAlert > QUIET_MS && Env.cfg.value.heatAlert) {
                    lastAlert = now
                    main.post { Actions.toast("La consola está a ${celsius.toInt()} °C. Sube el ventilador a Sport en CNTRL › BOOST") }
                }
            }
            main.postDelayed(this, CHECK_MS)
        }
    }

    private fun startHeatWatch() {
        if (watching) return
        watching = true
        main.postDelayed(heatCheck, CHECK_MS)
    }

    private fun stopHeatWatch() {
        watching = false
        main.removeCallbacks(heatCheck)
    }
}
