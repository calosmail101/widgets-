package dev.palanca.launcher.data

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import dev.palanca.launcher.Env
import dev.palanca.launcher.system.Actions
import dev.palanca.launcher.system.Privileged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Informe de texto con lo que este dispositivo deja leer: sensores de temperatura, ventilador,
 * ajustes propios del fabricante (modos de rendimiento), mandos y versión de Android.
 * Sirve para saber qué se puede integrar en Heaven en esta consola concreta.
 * No incluye datos personales: ni cuentas, ni archivos, ni lista completa de apps.
 */
object Diagnostics {
    private val INTERESTING = Regex("controller|style|trigger|charge|abxy|light|fan|perf|therm|mode|game|boost|cool|turbo|led|rgb|joystick|gamepad|retroid|odin|ayn|refresh|fps|qs_tiles|power_save|low_power", RegexOption.IGNORE_CASE)
    private val VENDOR_PACKAGES = Regex("retroid|odin|ayn|moorechip|gamekey|gameassist|keymap|joystick|handheld|fan|performance|thermal|gamespace|gamemode", RegexOption.IGNORE_CASE)

    fun export() {
        Env.host?.createDocument("heaven-diagnostico.txt", "text/plain") { uri ->
            uri ?: return@createDocument
            Env.scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching { Env.ctx.contentResolver.openOutputStream(uri)!!.use { it.write(build(Env.ctx).toByteArray()) } }.isSuccess
                }
                Actions.toast(if (ok) "Diagnóstico guardado" else "No se pudo guardar el diagnóstico")
            }
        }
    }

    private fun read(path: String): String? = runCatching { File(path).readText().trim() }.getOrNull()

    /** Carpeta de Heaven en Descargas: estado.json y los reportes. Se lee con un solo «adb pull». */
    private fun folder(): File = File(Files.internal, "Download/Heaven").apply { mkdirs() }
    private var lastState = 0L

    /**
     * Escribe Download/Heaven/estado.json: versión de Heaven, ajustes de la consola, perfiles, apps con su
     * versión y categoría, y tiempo de juego. Pensado para que un agente o un script lo lean por ADB sin
     * tener que explorar la consola. Como mucho una vez por hora salvo que se pida ([force]).
     */
    fun writeState(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastState < 3_600_000L) return
        lastState = now
        runCatching {
            val ctx = Env.ctx
            val pm = ctx.packageManager
            val cfg = Env.cfg.value
            fun q(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            fun sys(k: String) = runCatching { android.provider.Settings.System.getString(ctx.contentResolver, k) }.getOrNull() ?: ""
            val heaven = pm.getPackageInfo(ctx.packageName, 0)
            // Una línea por entrada y se unen al final: más fácil de leer y de mantener.
            val consola = listOf("performance_mode", "fan_mode", "fan_speed", "peak_refresh_rate", "percent_80_charge_limit", "flip_button_layout",
                "no_create_gamepad_button_layout", "trigger_input_mode", "remap_custom_to_m1_value", "remap_custom_to_m2_value")
                .joinToString(", ") { "${q(it)}: ${q(sys(it))}" }
            val perfiles = cfg.profiles.entries.joinToString(", ") { (pkg, p) -> "${q(pkg)}: {\"modo\": ${p.performance}, \"ventilador\": ${p.fan}, \"velocidad\": ${p.fanSpeed}, \"hz\": ${p.hz}}" }
            val apps = Env.apps.apps.value.map { app ->
                val version = runCatching { pm.getPackageInfo(app.pkg, 0).versionName }.getOrNull() ?: ""
                "    {\"paquete\": ${q(app.pkg)}, \"nombre\": ${q(app.label)}, \"version\": ${q(version)}, \"categoria\": ${q(app.categoryIn(cfg).title)}, \"fijada\": ${app.key in cfg.pinned}}"
            }
            val juego = dev.palanca.launcher.system.Playtime.summary(7).joinToString(", ") { (pkg, ms) -> "${q(pkg)}: ${ms / 60_000}" }
            val lines = listOf(
                "{",
                "  \"generado\": ${q(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()))},",
                "  \"heaven\": {\"version\": ${q(heaven.versionName ?: "")}, \"compilacion\": ${androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(heaven)}},",
                "  \"consola\": {$consola, \"puntero\": ${dev.palanca.launcher.system.Actions.pointerEnabled()}, \"boton_puntero\": ${cfg.pointerKey}},",
                "  \"perfiles\": {$perfiles},",
                "  \"apps\": [",
                apps.joinToString(",\n"),
                "  ],",
                "  \"tiempo_de_juego_7_dias_min\": {$juego}",
                "}",
            )
            val json = lines.joinToString("\n") + "\n"
            File(folder(), "estado.json").writeText(json)
        }
    }

    /**
     * Reporte de un problema en un ZIP (Download/Heaven/reportes): diagnóstico, estado.json, el registro del
     * sistema de los últimos minutos y las tres últimas capturas. Para mandarlo a quien lo vaya a revisar.
     */
    fun report(done: (String) -> Unit) {
        Env.scope.launch {
            val msg = withContext(Dispatchers.IO) {
                runCatching {
                    val stamp = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm", java.util.Locale.US).format(java.util.Date())
                    val work = File(Env.ctx.getExternalFilesDir(null), "reporte-$stamp").apply { deleteRecursively(); mkdirs() }
                    File(work, "diagnostico.txt").writeText(build(Env.ctx))
                    writeState(force = true)
                    File(folder(), "estado.json").copyTo(File(work, "estado.json"), overwrite = true)
                    // El registro del sistema solo lo puede leer PServer (las apps normales solo ven el suyo).
                    val log = File(work, "registro.txt")
                    Privileged.run("sh -c \"logcat -d -t 4000 > ${log.absolutePath}\"")
                    repeat(30) { if (log.exists() && log.length() > 0) return@repeat; Thread.sleep(200) }
                    val shots = listOf("Pictures/Screenshots", "Pictures/Heaven").map { File(Files.internal, it) }
                        .flatMap { it.listFiles().orEmpty().toList() }.filter { it.isFile }.sortedByDescending { it.lastModified() }.take(3)
                    shots.forEach { it.copyTo(File(work, it.name), overwrite = true) }
                    val zip = File(File(folder(), "reportes").apply { mkdirs() }, "reporte-$stamp.zip")
                    val error = Archives.compress(work.listFiles().orEmpty().toList(), zip, { false }) { _, _ -> }
                    work.deleteRecursively()
                    Files.notifyChanged(listOf(zip.absolutePath))
                    error ?: "Reporte guardado en Descargas/Heaven/reportes"
                }.getOrElse { "No se pudo crear el reporte: ${it.message}" }
            }
            done(msg)
        }
    }

    fun build(ctx: Context): String = buildString {
        fun section(title: String, body: StringBuilder.() -> Unit) {
            append("\n== ").append(title).append(" ==\n")
            runCatching { body() }.onFailure { append("(error: ").append(it.javaClass.simpleName).append(")\n") }
        }
        append("Diagnóstico de Heaven\n")

        section("Dispositivo") {
            append("${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE} / ${Build.PRODUCT})\n")
            append("placa ${Build.BOARD} · hardware ${Build.HARDWARE}")
            if (Build.VERSION.SDK_INT >= 31) append(" · SoC ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
            append("\nAndroid ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · compilación ${Build.DISPLAY}\n")
        }

        section("Pantalla") {
            val display = ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            append("actual ${display.refreshRate} Hz\n")
            display.supportedModes.forEach { append("modo ${it.physicalWidth}x${it.physicalHeight} @ ${it.refreshRate} Hz\n") }
        }

        section("Mandos") {
            for (id in InputDevice.getDeviceIds()) {
                val d = InputDevice.getDevice(id) ?: continue
                if (d.sources and InputDevice.SOURCE_GAMEPAD != InputDevice.SOURCE_GAMEPAD &&
                    d.sources and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) continue
                append("${d.name} · vendor ${d.vendorId} product ${d.productId} · fuentes 0x${d.sources.toString(16)}\n")
                append("  ejes: ").append(d.motionRanges.joinToString { MotionEvent.axisToString(it.axis) }).append('\n')
                val keys = intArrayOf(
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
                    KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR, KeyEvent.KEYCODE_BUTTON_START,
                    KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_MODE,
                )
                val has = d.hasKeys(*keys)
                append("  teclas: cruceta=${has[0]} A=${has[1]} L2=${has[2]} R2=${has[3]} L3=${has[4]} R3=${has[5]} start=${has[6]} select=${has[7]} mode=${has[8]}\n")
            }
        }

        section("Zonas térmicas") {
            val zones = File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }
                ?.sortedBy { it.name.removePrefix("thermal_zone").toIntOrNull() ?: 0 }.orEmpty()
            if (zones.isEmpty()) append("sin acceso a /sys/class/thermal\n")
            zones.take(140).forEach { z -> append("${z.name}: ${read("${z.path}/type") ?: "?"} = ${read("${z.path}/temp") ?: "sin acceso"}\n") }
            File("/sys/class/thermal").listFiles { f -> f.name.startsWith("cooling_device") }?.take(60)?.forEach { c ->
                append("${c.name}: ${read("${c.path}/type") ?: "?"} estado ${read("${c.path}/cur_state") ?: "?"}/${read("${c.path}/max_state") ?: "?"}\n")
            }
        }

        section("Ventilador y sensores (hwmon)") {
            val mons = File("/sys/class/hwmon").listFiles().orEmpty()
            if (mons.isEmpty()) append("sin acceso a /sys/class/hwmon\n")
            mons.forEach { m ->
                append("${m.name}: ${read("${m.path}/name") ?: "?"}\n")
                m.listFiles { f -> Regex("(fan|pwm|temp)\\d+(_input|_enable|_label)?").matches(f.name) }?.sortedBy { it.name }?.forEach {
                    append("  ${it.name} = ${read(it.path) ?: "sin acceso"}\n")
                }
            }
        }

        section("CPU y GPU") {
            for (i in 0 until Runtime.getRuntime().availableProcessors()) {
                val base = "/sys/devices/system/cpu/cpu$i/cpufreq"
                append("cpu$i: ${read("$base/scaling_cur_freq") ?: "sin acceso"} / ${read("$base/cpuinfo_max_freq") ?: "?"} · ${read("$base/scaling_governor") ?: "?"}\n")
            }
            for (f in listOf("gpu_busy_percentage", "gpuclk", "temp", "devfreq/cur_freq", "devfreq/governor")) {
                append("kgsl $f = ${read("/sys/class/kgsl/kgsl-3d0/$f") ?: "sin acceso"}\n")
            }
        }

        section("Batería") {
            val bm = ctx.getSystemService(BatteryManager::class.java)
            append("corriente ahora ${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)}\n")
            append("carga ${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)} %\n")
        }

        for (table in listOf("system", "secure", "global")) section("Ajustes ($table) que parecen modos") {
            var shown = 0
            ctx.contentResolver.query(Uri.parse("content://settings/$table"), arrayOf("name", "value"), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    if (INTERESTING.containsMatchIn(name)) { append("$name = ${c.getString(1)?.take(300)}\n"); shown++ }
                }
            }
            if (shown == 0) append("(ninguno visible)\n")
        }

        section("Propiedades del sistema") {
            val process = Runtime.getRuntime().exec("getprop")
            process.inputStream.bufferedReader().useLines { lines ->
                lines.filter { INTERESTING.containsMatchIn(it) }.take(150).forEach { append(it).append('\n') }
            }
        }

        section("Servicio privilegiado (PServer)") {
            append("disponible: ${Privileged.available()}\n")
            if (Privileged.available()) {
                for (command in listOf(
                    "id", "settings get system fan_mode", "settings get system performance_mode",
                    "ls /sys/class/hwmon/", "cat /sys/class/hwmon/hwmon*/name", "cat /sys/class/hwmon/hwmon*/fan1_input",
                    "dumpsys SurfaceFlinger --timestats -dump | head -c 1200",
                )) append("$ $command\n").append(Privileged.output(command)?.trim()?.take(1400) ?: "(sin salida)").append('\n')
            }
        }

        section("Apps del fabricante") {
            val pm = ctx.packageManager
            @Suppress("DEPRECATION")
            val packages = pm.getInstalledPackages(PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS)
            packages.filter { VENDOR_PACKAGES.containsMatchIn(it.packageName) }.sortedBy { it.packageName }.forEach { p ->
                append("${p.packageName} (${p.versionName})\n")
                p.activities?.filter { it.exported }?.forEach { append("  actividad ${it.name}\n") }
                p.services?.filter { it.exported }?.forEach { append("  servicio ${it.name}${it.permission?.let { perm -> " [$perm]" } ?: ""}\n") }
                p.receivers?.filter { it.exported }?.forEach { append("  receptor ${it.name}${it.permission?.let { perm -> " [$perm]" } ?: ""}\n") }
            }
        }
    }
}
