package dev.palanca.launcher.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.os.Process
import android.os.UserHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.text.Normalizer

enum class Cat(val title: String) {
    JUEGOS("Juegos"), EMULADORES("Emuladores"), MULTIMEDIA("Multimedia"), APPS("Apps"), SISTEMA("Sistema")
}

data class AppEntry(
    val key: String,
    val pkg: String,
    val cls: String,
    val label: String,
    val category: Cat,
) {
    val component get() = ComponentName(pkg, cls)
    val sortKey: String = fold(label)
}

/** Minúsculas y sin acentos, para ordenar y buscar. */
fun fold(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()

private val EMULATORS = listOf(
    "com.retroarch", "org.libretro", "org.dolphinemu", "org.mm.jr", "xyz.aethersx2", "com.sbro.emucorex",
    "org.ppsspp", "com.github.stenzek.duckstation", "org.citra", "io.github.lime3ds", "org.azahar_emu",
    "io.github.borked3ds", "org.yuzu", "dev.eden", "org.sudachi", "org.citron", "com.antutu.yuzu",
    "me.magnum.melonds", "com.dsemu.drastic", "org.mupen64plusae", "com.flycast", "org.vita3k",
    "net.rpcsx", "aenu.aps3e", "com.explusalpha", "org.scummvm", "com.winlator", "com.micewine",
    "org.devmiyax.yabasanshiro", "com.github.eka2l1", "paulscode.android.mupen64plus", "io.github.mandarine3ds",
    "org.es_de", "rip.moth.cocoonshell", "com.magneticchen.daijishou",
    "org.pegasus_frontend", "net.kdt.pojavlaunch", "com.gamenative",
    // Vistos en la Retroid Pocket 6 del usuario (15 nov 2026): no estaban y caían en Juegos o Apps.
    "com.armsx2", "info.cemu", "xendroid", "app.gamenative", "com.xiaoji.egggame", "com.droiddeck",
    "com.rfandango", "io.github.kalasyan.recompa",
)
private val GAME_HINTS = listOf(
    "com.limelight", "com.valvesoftware", "com.nvidia.geforcenow", "com.microsoft.xcloud",
    "com.microsoft.xboxone", "com.gamepass", "com.playstation.remoteplay", "com.steam", "dev.rewasd.artemis",
    "com.papi.artemis", "xyz.chiaki", "com.amazon.luna", "com.epicgames",
    // Ports y recompilaciones nativas: son juegos, aunque no se declaren como tales.
    "com.botwrecomp", "com.t6requiem",
)

/** Categoría automática. Función pura para poder probarla. */
fun classify(pkg: String, appCategory: Int, isSystem: Boolean): Cat = when {
    EMULATORS.any { pkg.startsWith(it) } -> Cat.EMULADORES
    appCategory == ApplicationInfo.CATEGORY_GAME || GAME_HINTS.any { pkg.startsWith(it) } -> Cat.JUEGOS
    appCategory == ApplicationInfo.CATEGORY_AUDIO || appCategory == ApplicationInfo.CATEGORY_VIDEO ||
        appCategory == ApplicationInfo.CATEGORY_IMAGE -> Cat.MULTIMEDIA
    isSystem -> Cat.SISTEMA
    else -> Cat.APPS
}

class AppRepository(private val ctx: Context, private val scope: CoroutineScope) {
    private val launcherApps = ctx.getSystemService(LauncherApps::class.java)
    val apps = MutableStateFlow<List<AppEntry>>(emptyList())

    /** Sube cada vez que cambia un paquete: sirve para invalidar la caché de iconos. */
    val generation = MutableStateFlow(0)

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(p: String?, u: UserHandle?) = reload()
        override fun onPackageAdded(p: String?, u: UserHandle?) = reload()
        override fun onPackageChanged(p: String?, u: UserHandle?) = reload()
        override fun onPackagesAvailable(p: Array<out String>?, u: UserHandle?, r: Boolean) = reload()
        override fun onPackagesUnavailable(p: Array<out String>?, u: UserHandle?, r: Boolean) = reload()
    }

    fun start() {
        runCatching { launcherApps?.registerCallback(callback) }
        reload()
    }

    fun reload() {
        scope.launch(Dispatchers.IO) {
            val list = runCatching {
                launcherApps?.getActivityList(null, Process.myUserHandle()).orEmpty()
                    .filter { it.componentName.packageName != ctx.packageName }
                    .map {
                        val info = it.applicationInfo
                        val system = info.flags and ApplicationInfo.FLAG_SYSTEM != 0 &&
                            info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0
                        AppEntry(
                            key = it.componentName.packageName + "/" + it.componentName.className,
                            pkg = it.componentName.packageName,
                            cls = it.componentName.className,
                            label = it.label?.toString() ?: it.componentName.packageName,
                            category = classify(info.packageName, info.category, system),
                        )
                    }
                    .sortedBy { it.sortKey }
            }.getOrDefault(emptyList())
            apps.value = list
            generation.value++
        }
    }

    fun launch(app: AppEntry, options: android.os.Bundle? = null): Boolean = runCatching {
        launcherApps!!.startMainActivity(app.component, Process.myUserHandle(), null, options)
    }.isSuccess

    fun details(app: AppEntry) {
        runCatching { launcherApps?.startAppDetailsActivity(app.component, Process.myUserHandle(), null, null) }
    }
}

/** Categoría efectiva: la que eligió el usuario o la automática. */
fun AppEntry.categoryIn(cfg: Config): Cat =
    cfg.categories[key]?.let { name -> Cat.entries.firstOrNull { it.name == name } } ?: category

fun AppEntry.labelIn(cfg: Config): String = cfg.icons[key]?.label?.takeIf { it.isNotBlank() } ?: label

/** Apps que se fijan solas la primera vez, en este orden si están instaladas. */
val DEFAULT_PINS = listOf(
    "com.android.vending", "com.google.android.youtube", "com.android.chrome", "com.retroarch",
    "com.retroarch.aarch64", "org.dolphinemu.dolphinemu", "xyz.aethersx2.android", "org.ppsspp.ppsspp",
    "com.android.settings",
)
