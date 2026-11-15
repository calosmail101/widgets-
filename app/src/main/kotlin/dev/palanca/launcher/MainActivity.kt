package dev.palanca.launcher

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import dev.palanca.launcher.data.WidgetSlot
import dev.palanca.launcher.input.Btn
import dev.palanca.launcher.input.GamepadInput
import dev.palanca.launcher.input.PadEvent
import dev.palanca.launcher.input.Scheduler
import dev.palanca.launcher.system.Actions
import dev.palanca.launcher.system.Trigger
import dev.palanca.launcher.ui.HomeScreen
import dev.palanca.launcher.ui.PalancaRoot
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.max

class MainActivity : ComponentActivity(), HostBridge {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var input: GamepadInput
    private lateinit var home: HomeScreen

    private var onImage: ((Uri?) -> Unit)? = null
    private var onCreated: ((Uri?) -> Unit)? = null
    private var onOpened: ((Uri?) -> Unit)? = null
    private val imagePicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { onImage?.invoke(it); onImage = null }
    private val zipCreator = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { onCreated?.invoke(it); onCreated = null }
    private val textCreator = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { onCreated?.invoke(it); onCreated = null }
    private val opener = registerForActivityResult(ActivityResultContracts.OpenDocument()) { onOpened?.invoke(it); onOpened = null }
    private var onAudio: ((List<Uri>) -> Unit)? = null
    private val audioPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        // Permiso persistente: las canciones siguen accesibles tras reiniciar la consola.
        uris.forEach { runCatching { contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        onAudio?.invoke(uris); onAudio = null
    }
    private var pendingWidget: Pair<Int, AppWidgetProviderInfo>? = null

    override val activity: Activity get() = this

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        home = Env.nav.stack.firstOrNull() as? HomeScreen ?: HomeScreen().also { Env.nav.stack.clear(); Env.nav.push(it) }
        Env.host = this
        input = GamepadInput(
            object : Scheduler {
                override fun post(delayMs: Long, task: Runnable) { handler.postDelayed(task, delayMs) }
                override fun cancel(task: Runnable) = handler.removeCallbacks(task)
            },
            swapAB = { Env.cfg.value.swapAB },
            emit = ::onPad,
            analog = { x, y -> Env.nav.top?.onStick(x, y) },
        )
        // El fondo de Android solo se pide al sistema cuando se usa: así el negro es negro de verdad.
        lifecycleScope.launch {
            Env.cfg.flow.map { it.look.wallpaper == "sistema" }.distinctUntilChanged().collect { system ->
                if (system) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
                    window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
                    window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
                }
            }
        }
        setContent { PalancaRoot() }
    }

    override fun onDestroy() {
        if (Env.host === this) Env.host = null
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        runCatching { Env.widgets.host.startListening() }
    }

    override fun onStop() {
        runCatching { Env.widgets.host.stopListening() }
        super.onStop()
    }

    /**
     * Con un juego pesado abierto, Android cerraba Heaven por falta de memoria una y otra vez (12 veces
     * en dos días en la Pocket 6). En segundo plano y con memoria justa, Heaven suelta la caché de iconos
     * (unos 40 MB de imágenes) para pesar menos; al volver los redibuja.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) runCatching { Env.icons.trim() }
    }

    override fun onResume() {
        super.onResume()
        Env.foreground.value = true
        Actions.onReturn()
        // estado.json para leerlo por ADB (como mucho una vez por hora).
        Env.scope.launch(kotlinx.coroutines.Dispatchers.IO) { dev.palanca.launcher.data.Diagnostics.writeState() }
        immersive()
    }

    override fun onPause() {
        Env.foreground.value = false
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive() else input.reset()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Botón Home con el launcher ya delante: vuelve a la primera pantalla.
        if (intent.action == Intent.ACTION_MAIN && hasWindowFocus()) { Env.nav.home(); home.reset() }
    }

    @Suppress("DEPRECATION")
    private fun immersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.run {
                hide(WindowInsets.Type.systemBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    // ---- Entrada del mando -------------------------------------------------------------

    private fun onPad(e: PadEvent) {
        val top = Env.nav.top ?: return
        if (top.onPad(e)) {
            when {
                e.long -> Env.feedback.heavy()
                e.btn == Btn.A -> Env.feedback.select()
                e.btn == Btn.B -> Env.feedback.back()
                else -> Env.feedback.move()
            }
        } else {
            val trigger = Trigger.entries.firstOrNull { it.btn == e.btn && it.long == e.long } ?: return
            Env.feedback.select()
            Actions.run(Actions.actionFor(trigger))
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        Env.keyCapture?.let { capture ->
            // Modo "aprender botón": cualquier tecla (salvo Atrás, que cancela) se entrega tal cual.
            if (event.keyCode != KeyEvent.KEYCODE_BACK) { if (event.action == KeyEvent.ACTION_UP) capture(event.keyCode); return true }
        }
        if ((down || event.action == KeyEvent.ACTION_UP) && input.onKey(event.keyCode, down, event.repeatCount)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.isFromSource(InputDevice.SOURCE_JOYSTICK) && ev.action == MotionEvent.ACTION_MOVE) {
            input.onAxes(
                lx = ev.getAxisValue(MotionEvent.AXIS_X), ly = ev.getAxisValue(MotionEvent.AXIS_Y),
                hatX = ev.getAxisValue(MotionEvent.AXIS_HAT_X), hatY = ev.getAxisValue(MotionEvent.AXIS_HAT_Y),
                rx = ev.getAxisValue(MotionEvent.AXIS_Z), ry = ev.getAxisValue(MotionEvent.AXIS_RZ),
                lt = max(ev.getAxisValue(MotionEvent.AXIS_LTRIGGER), ev.getAxisValue(MotionEvent.AXIS_BRAKE)),
                rt = max(ev.getAxisValue(MotionEvent.AXIS_RTRIGGER), ev.getAxisValue(MotionEvent.AXIS_GAS)),
            )
            return true
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    // ---- Selectores del sistema --------------------------------------------------------

    override fun pickImage(onResult: (Uri?) -> Unit) {
        onImage = onResult
        runCatching { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            .onFailure { Actions.toast("No hay ningún selector de imágenes disponible") }
    }

    override fun createDocument(name: String, mime: String, onResult: (Uri?) -> Unit) {
        onCreated = onResult
        runCatching { (if (mime == "text/plain") textCreator else zipCreator).launch(name) }.onFailure { Actions.toast("No hay ningún gestor de archivos disponible") }
    }

    override fun openDocument(types: Array<String>, onResult: (Uri?) -> Unit) {
        onOpened = onResult
        runCatching { opener.launch(types) }
            .onFailure { Actions.toast("No hay ningún gestor de archivos disponible") }
    }

    override fun pickAudio(onResult: (List<Uri>) -> Unit) {
        onAudio = onResult
        runCatching { audioPicker.launch(arrayOf("audio/*")) }.onFailure { Actions.toast("No hay ningún gestor de archivos disponible") }
    }

    // ---- Widgets: enlazar → configurar → guardar -------------------------------------------

    override fun addWidget(provider: AppWidgetProviderInfo) {
        val host = Env.widgets.host
        val manager = Env.widgets.manager ?: return
        val id = host.allocateAppWidgetId()
        pendingWidget = id to provider
        if (manager.bindAppWidgetIdIfAllowed(id, provider.provider)) configureWidget()
        else runCatching {
            @Suppress("DEPRECATION")
            startActivityForResult(
                Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider.provider),
                REQ_BIND,
            )
        }.onFailure { dropWidget() }
    }

    private fun configureWidget() {
        val (id, provider) = pendingWidget ?: return
        if (provider.configure == null) { commitWidget(); return }
        runCatching { Env.widgets.host.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_CONFIGURE, null) }
            .onFailure { commitWidget() }
    }

    private fun commitWidget() {
        val (id, provider) = pendingWidget ?: return
        pendingWidget = null
        val cols = (provider.minWidth / resources.displayMetrics.density / 90f).toInt().coerceIn(1, 4)
        Env.cfg.update { it.copy(widgets = it.widgets + WidgetSlot(id, provider.provider.flattenToString(), cols)) }
    }

    private fun dropWidget() {
        pendingWidget?.let { runCatching { Env.widgets.host.deleteAppWidgetId(it.first) } }
        pendingWidget = null
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_BIND -> if (resultCode == RESULT_OK) configureWidget() else dropWidget()
            REQ_CONFIGURE -> if (resultCode == RESULT_OK) commitWidget() else dropWidget()
        }
    }

    private companion object {
        const val REQ_BIND = 71
        const val REQ_CONFIGURE = 72
    }
}
