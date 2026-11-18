package dev.palanca.launcher.system

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.view.Display
import dev.palanca.launcher.Env
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.abs
import kotlin.math.roundToInt

enum class Metric(val title: String) {
    BATTERY("Batería"), POWER("Consumo"), BAT_TEMP("Temps"), CPU_TEMP("T. CPU"), GPU_TEMP("T. GPU"),
    CPU_FREQ("CPU"), GPU_LOAD("Uso GPU"), RAM("Memoria"), STORAGE("Interno"), SD("Tarjeta SD"), PING("Ping"), WIFI("Wi‑Fi"), REFRESH("Pantalla");

    companion object {
        fun of(id: String) = entries.firstOrNull { it.name == id }
    }
}

/**
 * Una lectura lista para pintar. [fraction] (0..1) alimenta barras, anillos y gráficas.
 * [available] = false cuando este dispositivo no deja leer el dato.
 */
data class Reading(
    val value: String, val unit: String = "", val fraction: Float = 0f,
    val detail: String = "", val available: Boolean = true,
) {
    companion object { val NONE = Reading("—", detail = "sin acceso", available = false) }
}

/**
 * Lecturas del sistema en tiempo real. Solo muestrea las métricas que están en pantalla,
 * cada [PERIOD_MS], fuera del hilo de la interfaz y únicamente con el launcher en primer plano.
 * Coste: unas pocas lecturas de archivos pequeños cada dos segundos.
 */
object Telemetry {
    const val PERIOD_MS = 2000L
    private const val HISTORY = 40

    val readings = MutableStateFlow<Map<Metric, Reading>>(emptyMap())
    val history = MutableStateFlow<Map<Metric, List<Float>>>(emptyMap())

    /** Lecturas fijas para pruebas y capturas. */
    var fixed: Map<Metric, Reading>? = null

    private var cpuZones: List<String>? = null
    private var gpuZones: List<String>? = null
    private var lastPing: Reading? = null
    private var tick = 0

    suspend fun run(metrics: List<Metric>) {
        fixed?.let { f ->
            readings.value = f
            history.value = f.mapValues { (m, r) -> List(HISTORY) { i -> (r.fraction * (0.75f + 0.25f * kotlin.math.sin(i * 0.5f + m.ordinal))).coerceIn(0f, 1f) } }
            return
        }
        if (metrics.isEmpty()) return
        while (true) {
            Env.foreground.first { it }
            val now = withContext(Dispatchers.IO) { metrics.associateWith { m -> runCatching { sample(m) }.getOrDefault(Reading.NONE) } }
            tick++
            readings.value = now
            history.value = now.mapValues { (m, r) -> (history.value[m].orEmpty() + r.fraction).takeLast(HISTORY) }
            delay(PERIOD_MS)
        }
    }

    private var drawAvg = 0.0

    /**
     * Tiempo de batería que queda al consumo actual («≈ 2 h 40 min»), o null cargando o sin datos.
     * Carga restante (µAh) entre corriente (µA), con la corriente suavizada para que no salte.
     */
    fun remaining(): String? = runCatching {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        if (bm.isCharging) return@runCatching null
        val charge = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).toDouble()
        var amps = abs(bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).toDouble())
        if (charge <= 0 || amps <= 0) return@runCatching null
        if (amps < 30_000) amps *= 1000 // algunos dispositivos dan mA en vez de µA
        drawAvg = if (drawAvg <= 0) amps else drawAvg * 0.8 + amps * 0.2
        val minutes = (charge / drawAvg * 60).toInt().coerceIn(1, 48 * 60)
        if (minutes < 60) "≈ $minutes min" else "≈ ${minutes / 60} h ${minutes % 60} min"
    }.getOrNull()

    /** Una lectura suelta de varias métricas (para la tira de estadísticas en juego). Llamar fuera del hilo de la interfaz. */
    fun snapshot(metrics: List<Metric>): Map<Metric, Reading> = metrics.associateWith { m -> runCatching { sample(m) }.getOrDefault(Reading.NONE) }

    private val ctx: Context get() = Env.ctx
    private fun readLong(path: String): Long? = runCatching { File(path).readText().trim().toLong() }.getOrNull()
    private fun battery(): Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    private fun gb(bytes: Long) = bytes / 1e9

    private fun zones(match: (String) -> Boolean): List<String> =
        File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }.orEmpty()
            .filter { z -> runCatching { match(File(z, "type").readText().trim().lowercase()) }.getOrDefault(false) }
            .map { it.path + "/temp" }.filter { readLong(it) != null }

    /** Media suavizada por grupo de sensores (°C). Los sensores por núcleo saltan >10 °C en milisegundos. */
    private val smoothed = java.util.concurrent.ConcurrentHashMap<List<String>, Pair<Float, Long>>()

    /**
     * Temperatura del chip: MEDIA de todos sus sensores, suavizada (~2 s), en lugar del máximo instantáneo
     * de unos pocos núcleos. El aviso usa el pico real, para no esconder un sobrecalentamiento.
     */
    private fun temperature(paths: List<String>): Reading {
        val vals = paths.mapNotNull(::readLong).map { if (it > 1000) it / 1000f else it.toFloat() }.filter { it in 1f..150f }
        if (vals.isEmpty()) return Reading.NONE
        val mean = vals.average().toFloat()
        val now = android.os.SystemClock.elapsedRealtime()
        val c = smoothed[paths]?.let { (prev, t) ->
            val k = 1f - kotlin.math.exp(-(now - t).coerceAtLeast(0L) / 2000f)   // constante de 2 s, sea cual sea el muestreo
            prev + (mean - prev) * k
        } ?: mean
        smoothed[paths] = c to now
        val peak = vals.max()
        return Reading(c.roundToInt().toString(), "°C", ((c - 25f) / 60f).coerceIn(0f, 1f),
            if (peak >= 85f) "pico ${peak.roundToInt()}°" else if (c >= 75f) "caliente" else "")
    }

    private fun volume(path: File, detail: String): Reading {
        val s = StatFs(path.path)
        if (s.totalBytes <= 0) return Reading.NONE
        return Reading(gb(s.availableBytes).roundToInt().toString(), "GB", 1f - s.availableBytes.toFloat() / s.totalBytes, detail)
    }

    private fun sample(m: Metric): Reading = when (m) {
        Metric.BATTERY -> {
            val i = battery() ?: return Reading.NONE
            val pct = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            Reading(pct.toString(), "%", pct / 100f, if (charging) "cargando" else "")
        }
        Metric.POWER -> {
            val bm = ctx.getSystemService(BatteryManager::class.java)
            var amps = abs(bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).toDouble())
            // La mayoría de dispositivos informa en microamperios; algunos, en miliamperios.
            amps = if (amps > 30_000) amps / 1e6 else amps / 1e3
            val volts = (battery()?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0) / 1000.0
            if (amps == 0.0 || volts == 0.0) Reading.NONE
            else (amps * volts).let { w -> Reading(String.format("%.1f", w), "W", (w / 15.0).toFloat().coerceIn(0f, 1f), "ahora") }
        }
        Metric.BAT_TEMP -> {
            val t = (battery()?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE)
            if (t == Int.MIN_VALUE) Reading.NONE else (t / 10f).let { c -> Reading(c.roundToInt().toString(), "°C", ((c - 20f) / 30f).coerceIn(0f, 1f)) }
        }
        Metric.CPU_TEMP -> temperature(cpuZones ?: zones { "cpu" in it }.also { cpuZones = it })
        Metric.GPU_TEMP -> temperature(gpuZones ?: zones { "gpu" in it }.also { gpuZones = it })
        Metric.CPU_FREQ -> {
            val cores = (0 until Runtime.getRuntime().availableProcessors()).mapNotNull { i ->
                val base = "/sys/devices/system/cpu/cpu$i/cpufreq"
                val cur = readLong("$base/scaling_cur_freq"); val max = readLong("$base/cpuinfo_max_freq")
                if (cur != null && max != null && max > 0) cur to max else null
            }
            if (cores.isEmpty()) Reading.NONE
            else Reading(
                String.format("%.1f", cores.maxOf { it.first } / 1e6), "GHz",
                cores.map { it.first.toFloat() / it.second }.average().toFloat(), "núcleo más rápido",
            )
        }
        Metric.GPU_LOAD -> {
            val pct = runCatching { File("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage").readText().filter(Char::isDigit).toInt() }.getOrNull()
            if (pct == null) Reading.NONE else Reading(pct.toString(), "%", pct / 100f)
        }
        Metric.RAM -> {
            val info = ActivityManager.MemoryInfo().also { ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
            val used = info.totalMem - info.availMem
            Reading(String.format("%.1f", gb(used)), "GB", used.toFloat() / info.totalMem, "en uso de ${gb(info.totalMem).roundToInt()} GB")
        }
        Metric.STORAGE -> Actions.storageOverride?.let { (free, total) ->
            Reading(gb(free).roundToInt().toString(), "GB", 1f - free.toFloat() / total, "libres")
        } ?: volume(Environment.getDataDirectory(), "libres")
        Metric.SD -> ctx.getExternalFilesDirs(null).drop(1).firstOrNull { it != null }?.let { volume(it, "libres") }
            ?: Reading("—", detail = "sin tarjeta", available = false)
        Metric.PING -> {
            // Una conexión TCP corta cada tres muestras (6 s); entre medias se repite el último valor.
            if (tick % 3 == 0 || lastPing == null) {
                val start = System.nanoTime()
                lastPing = runCatching {
                    Socket().use { it.connect(InetSocketAddress("1.1.1.1", 443), 1500) }
                    val ms = ((System.nanoTime() - start) / 1e6).roundToInt()
                    Reading(ms.toString(), "ms", (ms / 200f).coerceIn(0f, 1f), if (ms < 60) "bueno" else if (ms < 120) "normal" else "alto")
                }.getOrDefault(Reading("—", detail = "sin red", available = false))
            }
            lastPing!!
        }
        Metric.WIFI -> {
            @Suppress("DEPRECATION")
            val info = ctx.getSystemService(WifiManager::class.java)?.connectionInfo
            val rssi = info?.rssi ?: -127
            if (rssi <= -127) Reading("—", detail = "sin Wi‑Fi", available = false)
            else Reading(rssi.toString(), "dBm", ((rssi + 90) / 50f).coerceIn(0f, 1f), "${info!!.linkSpeed} Mbps")
        }
        Metric.REFRESH -> {
            val hz = ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).refreshRate
            Reading(hz.roundToInt().toString(), "Hz", (hz / 120f).coerceIn(0f, 1f), "refresco")
        }
    }
}
