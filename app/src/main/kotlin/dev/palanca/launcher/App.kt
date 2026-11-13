package dev.palanca.launcher

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.Uri
import dev.palanca.launcher.data.AppRepository
import dev.palanca.launcher.data.Config
import dev.palanca.launcher.data.JsonStore
import dev.palanca.launcher.data.Stats
import dev.palanca.launcher.icons.IconPacks
import dev.palanca.launcher.icons.IconRenderer
import dev.palanca.launcher.system.Feedback
import dev.palanca.launcher.system.WidgetHost
import dev.palanca.launcher.ui.Navigator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** Lo que solo la Activity puede hacer: selectores del sistema y flujo de widgets. */
interface HostBridge {
    val activity: Activity
    fun pickImage(onResult: (Uri?) -> Unit)
    fun createDocument(name: String, mime: String, onResult: (Uri?) -> Unit)
    fun openDocument(onResult: (Uri?) -> Unit)
    fun addWidget(provider: android.appwidget.AppWidgetProviderInfo)
}

/** Dependencias de la app. Un único sitio, sin inyección: es un launcher pequeño. */
object Env {
    lateinit var ctx: Context
    lateinit var cfg: JsonStore<Config>
    lateinit var stats: JsonStore<Stats>
    lateinit var apps: AppRepository
    lateinit var icons: IconRenderer
    lateinit var feedback: Feedback
    lateinit var widgets: WidgetHost
    val nav = Navigator()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var host: HostBridge? = null

    /** En pruebas: dibuja los iconos en el mismo hilo para que salgan en las capturas. */
    var syncIcons = false
    val ready get() = ::ctx.isInitialized

    fun init(context: Context, dir: File = context.filesDir) {
        ctx = context.applicationContext
        cfg = JsonStore(File(dir, "config.json"), Config.serializer(), ::Config, scope)
        stats = JsonStore(File(dir, "stats.json"), Stats.serializer(), ::Stats, scope)
        apps = AppRepository(ctx, scope)
        icons = IconRenderer(ctx, IconPacks(ctx))
        feedback = Feedback(ctx)
        widgets = WidgetHost(ctx)
    }
}

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Env.init(this)
        Env.apps.start()
    }
}
