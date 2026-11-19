package dev.palanca.launcher.system

import android.util.Log

/**
 * Heaven FG: generación de fotogramas (LSFG) que corre dentro del driver Vulkan «Heaven FG» que el juego
 * carga como driver personalizado (adrenotools). El driver lee en vivo la propiedad debug.heavenfg.mult
 * (1 = apagado, 2 = x2, 3 = x3; vacía = su valor por defecto, x2) y Heaven la cambia con [Privileged].
 *
 * La propiedad es global: Heaven la pone al entrar en cada juego según su perfil y la deja vacía en los demás.
 */
object FrameGen {
    /** Opciones del multiplicador. El valor por defecto del driver es x2. */
    val MODES = listOf(1 to "Apagado", 2 to "x2", 3 to "x3")
    const val DEFAULT = 2

    fun name(mult: Int?): String = MODES.firstOrNull { it.first == (mult ?: DEFAULT) }?.second ?: "x$mult"

    /** Último valor enviado (null = vacío, el del driver). Evita repetir el comando en cada cambio de app. */
    @Volatile private var sent: Int? = -1

    /** Pone el multiplicador (null = el del driver). Llamar fuera del hilo de la interfaz. */
    fun set(mult: Int?): Boolean {
        if (mult == sent) return true
        val value = mult?.coerceIn(1, 4)?.toString() ?: "''"
        val ok = Privileged.run("setprop debug.heavenfg.mult $value")
        Log.i("Heaven", "FG: multiplicador ${mult ?: "por defecto"} (${if (ok) "enviado" else "PServer no disponible"})")
        if (ok) sent = mult
        return ok
    }

    private val PKG = Regex("^[A-Za-z0-9_.]+$")

    /** App en primer plano según el sistema (respaldo si Heaven acaba de arrancar). Fuera del hilo de la interfaz. */
    fun foreground(): String? {
        val out = Privileged.output("dumpsys activity activities | grep -m1 topResumedActivity", 2500) ?: return null
        return Regex("u0 ([A-Za-z0-9_.]+)/").find(out)?.groupValues?.get(1)
    }

    /**
     * ¿El juego [pkg] tiene cargado el driver Heaven FG? Se mira en sus mapas de memoria (PServer es root).
     * Tarda hasta ~1,5 s: llamar fuera del hilo de la interfaz.
     */
    fun loadedIn(pkg: String): Boolean {
        if (!PKG.matches(pkg)) return false
        // Sin variables de shell: PServer pasa el comando por dos shells y la primera las expandiría (vacías).
        val out = Privileged.output("grep -c libvulkan_heavenfg /proc/\$(pidof -s $pkg)/maps", 2000)
        val loaded = out?.lineSequence()?.any { (it.trim().toIntOrNull() ?: 0) > 0 } == true
        Log.i("Heaven", "FG: $pkg driver ${if (loaded) "cargado" else "no cargado"} (${out?.trim()?.take(40)})")
        return loaded
    }
}
