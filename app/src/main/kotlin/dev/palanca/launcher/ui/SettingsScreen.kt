package dev.palanca.launcher.ui

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.TransformOrigin
import dev.palanca.launcher.Env
import dev.palanca.launcher.data.Backup
import dev.palanca.launcher.data.Config
import dev.palanca.launcher.data.Diagnostics
import dev.palanca.launcher.icons.ICON_SHAPES
import dev.palanca.launcher.icons.ICON_TINTS
import dev.palanca.launcher.input.Btn
import dev.palanca.launcher.input.PadEvent
import dev.palanca.launcher.system.ActionId
import dev.palanca.launcher.system.Actions
import dev.palanca.launcher.system.SOUND_PACKS
import dev.palanca.launcher.system.Trigger
import dev.palanca.launcher.system.DeviceModes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class SRow(val title: String)
class Toggle(title: String, val get: (Config) -> Boolean, val set: (Config, Boolean) -> Config) : SRow(title)
class Choice(
    title: String, val options: () -> List<Pair<String, String>>, val get: (Config) -> String,
    val swatches: Boolean = false, val set: (Config, String) -> Config,
) : SRow(title)
class Range(
    title: String, val min: Int, val max: Int, val step: Int, val unit: String,
    val get: (Config) -> Int, val set: (Config, Int) -> Config,
) : SRow(title)
class Act(title: String, val value: () -> String = { "" }, val run: () -> Unit) : SRow(title)
class Section(val title: String, val note: String, val rows: List<SRow>)

/**
 * Activa el puntero. Con PServer, Heaven lo hace solo (también levanta el bloqueo de «ajustes
 * restringidos» de Android 13). Sin PServer, explica el camino y abre los ajustes de la app.
 */
private fun enablePointer() {
    Env.scope.launch {
        val sent = withContext(Dispatchers.IO) { DeviceModes.enablePointer() }
        if (sent) {
            // El sistema tarda un momento en arrancar el servicio.
            repeat(12) { delay(250); if (Actions.pointerEnabled()) { Actions.toast("Puntero activado: mantén L3 + R3 en cualquier app"); return@launch } }
        }
        Actions.toast("Android lo bloquea: en Información de la app › ⋮ › «Permitir ajustes restringidos», y luego actívalo en Accesibilidad")
        runCatching {
            Env.ctx.startActivity(
                android.content.Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${Env.ctx.packageName}"))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

private fun pointerKeyName(code: Int): String = when {
    code == 0 -> "L3 + R3"
    code == DeviceModes.M2_KEY && DeviceModes.m2Key() == code -> "M2"
    else -> android.view.KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")
}

/** Asigna una tecla al M2 (con PServer) y la elige como botón del puntero. */
private fun useM2ForPointer() {
    Env.scope.launch {
        // Siempre se envía: aunque el valor ya esté guardado, el mando puede no haberlo recibido todavía.
        if (!withContext(Dispatchers.IO) { DeviceModes.setM2Key(DeviceModes.M2_KEY) }) {
            Actions.toast("Esta consola no permite asignar el M2"); return@launch
        }
        repeat(12) { if (DeviceModes.m2Key() == DeviceModes.M2_KEY) return@repeat; delay(250) }
        if (DeviceModes.m2Key() != DeviceModes.M2_KEY) { Actions.toast("La consola no aceptó el cambio del M2"); return@launch }
        Env.cfg.update { it.copy(pointerKey = DeviceModes.M2_KEY) }
        Actions.toast(if (Actions.pointerEnabled()) "Listo: pulsa M2 en cualquier app para el puntero" else "M2 asignado. Activa también «Puntero con mando»")
    }
}

/** Versión instalada y compilación, para saber qué Heaven hay en la consola. */
private fun versionText(): String = runCatching {
    val info = Env.ctx.packageManager.getPackageInfo(Env.ctx.packageName, 0)
    "${info.versionName} · compilación ${androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info)}"
}.getOrDefault("?")

private fun sections(): List<Section> = listOf(
    Section(
        "Tema", "Fuente y color de toda la interfaz",
        listOf(
            Toggle("Ventanas y piezas de cristal", { it.look.glass }) { c, v -> c.look { copy(glass = v) } },
            Range("Tamaño del texto", 100, 140, 1, "%", { it.look.textScale }) { c, v -> c.look { copy(textScale = v) } },
            Choice("Fuente", { FONT_THEMES.map { it.id to it.name } }, { it.look.font }) { c, v -> c.look { copy(font = v) } },
            Choice("Color de acento", { ACCENTS.map { it.first.toString() to it.second } }, { it.look.accent.toString() }, swatches = true) { c, v ->
                c.look { copy(accent = v.toLong()) }
            },
            Act("Rueda de color", { "#%06X".format(Env.cfg.value.look.accent and 0xFFFFFF) }) { Env.nav.push(ColorPickerScreen()) },
        ),
    ),
    Section(
        "Reloj", "Así se ve en Inicio",
        listOf(
            Choice("Estilo", { CLOCK_STYLES }, { it.look.clockStyle }) { c, v -> c.look { copy(clockStyle = v) } },
            Toggle("Formato de 24 horas", { it.look.clock24 }) { c, v -> c.look { copy(clock24 = v) } },
            Toggle("Mostrar fecha", { it.look.clockDate }) { c, v -> c.look { copy(clockDate = v) } },
            Toggle("Segundos", { it.look.clockSeconds }) { c, v -> c.look { copy(clockSeconds = v) } },
        ),
    ),
    Section(
        "Iconos", "Ajustes para todas las apps. Cada icono se cambia con X sobre la app",
        listOf(
            Choice("Forma", { ICON_SHAPES }, { it.look.iconShape }) { c, v -> c.look { copy(iconShape = v) } },
            Choice(
                "Paquete de iconos", { listOf("" to "Originales") + Env.icons.packs.list().map { it.pkg to it.name } },
                { it.look.iconPack ?: "" },
            ) { c, v -> c.look { copy(iconPack = v.ifEmpty { null }) } },
            Range("Tamaño", 70, 130, 1, "%", { (it.look.iconScale * 100 + 0.5f).toInt() }) { c, v -> c.look { copy(iconScale = v / 100f) } },
            Choice("Estilo de los iconos", { ICON_TINTS }, { it.look.iconTint }) { c, v -> c.look { copy(iconTint = v) } },
            Act("Añadir paquete desde un archivo .apk") {
                Env.host?.openDocument(arrayOf("application/vnd.android.package-archive", "application/octet-stream")) { uri -> if (uri != null) Backup.addIconPack(uri) }
            },
            Toggle("Mostrar nombres", { it.look.labels }) { c, v -> c.look { copy(labels = v) } },
            Act("Conseguir paquetes de iconos", { "Play Store" }) {
                Actions.open(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("market://search?q=icon%20pack&c=apps"))
            },
            Act("Restablecer iconos personalizados", { "${Env.cfg.value.icons.size} cambiados" }) {
                Env.cfg.update { it.copy(icons = emptyMap()) }
            },
        ),
    ),
    Section(
        "Fondo", "Los fondos animados solo se mueven con Inicio en pantalla",
        listOf(
            Choice("Tipo", { WALLPAPERS }, { it.look.wallpaper }) { c, v -> c.look { copy(wallpaper = v) } },
            Act("Elegir imagen", { if (Env.cfg.value.look.wallpaperFile != null) "cambiar" else "ninguna" }) { Backup.pickWallpaper() },
            Range("Oscurecer", 0, 90, 1, "%", { it.look.wallpaperDim }) { c, v -> c.look { copy(wallpaperDim = v) } },
        ),
    ),
    Section(
        "Rejilla y tamaño", "Cuántas apps caben y cómo de grandes",
        listOf(
            Range("Columnas del cajón", 5, 9, 1, "", { it.look.drawerCols }) { c, v -> c.look { copy(drawerCols = v) } },
            Range("Iconos de Inicio", 64, 104, 1, "", { it.look.dockSize }) { c, v -> c.look { copy(dockSize = v) } },
            Act("Mover las piezas de Inicio") { Env.nav.pop(); Env.nav.push(HomeEditScreen()) },
            Act("Devolver las piezas a su sitio") { Env.cfg.update { it.look { copy(offsets = emptyMap()) } } },
        ),
    ),
    Section(
        "Estadísticas", "En Inicio: sube a una tarjeta y pulsa A para cambiarla, moverla o quitarla",
        listOf(
            Choice("Tamaño", { STAT_SIZES }, { it.look.statSize }) { c, v -> c.look { copy(statSize = v) } },
            Choice("Estilo", { STAT_STYLES }, { it.look.statStyle }) { c, v -> c.look { copy(statStyle = v) } },
            Act("Añadir estadística", { "${Env.cfg.value.look.stats.size} en Inicio" }) {
                Env.nav.push(metricPicker("Añadir a Inicio") { m -> Env.cfg.update { c -> c.look { copy(stats = stats + m.name) } } })
            },
            Act("Ponerlas todas") { Env.cfg.update { c -> c.look { copy(stats = dev.palanca.launcher.system.Metric.entries.map { it.name }) } } },
            Act("Quitar todas") { Env.cfg.update { c -> c.look { copy(stats = emptyList()) } } },
            Toggle("Tarjeta para continuar con la última app", { it.look.cardContinue }) { c, v -> c.look { copy(cardContinue = v) } },
        ),
    ),
    Section(
        "Sonido", "La música suena solo en Inicio y en el cajón; al abrir cualquier otra cosa se apaga",
        listOf(
            Toggle("Sonidos al navegar", { it.sound }) { c, v -> c.copy(sound = v) },
            Choice("Paquete de sonidos", { SOUND_PACKS }, { it.soundPack }) { c, v -> c.copy(soundPack = v) },
            Toggle("Vibración", { it.haptics }) { c, v -> c.copy(haptics = v) },
            Toggle("Música de fondo", { it.musicOn }) { c, v -> c.copy(musicOn = v) },
            Act("Añadir canciones", { "${Env.cfg.value.music.size} en la lista" }) {
                Env.host?.pickAudio { uris -> Env.cfg.update { c -> c.copy(music = (c.music + uris.map { it.toString() }).distinct()) } }
            },
            Range("Volumen de la música", 1, 60, 1, "%", { it.musicVolume }) { c, v -> c.copy(musicVolume = v) },
            Act("Quitar todas las canciones") { Env.cfg.update { it.copy(music = emptyList()) } },
        ),
    ),
    Section(
        "Mandos", "Los atajos funcionan en Inicio y en el cajón",
        listOf<SRow>(Toggle("Diseño Nintendo (intercambia A/B y X/Y)", { it.swapAB }) { c, v -> c.copy(swapAB = v) }) +
            Trigger.entries.map { trigger ->
                Choice(trigger.title, { ActionId.entries.map { it.name to it.title } }, { Actions.actionFor(trigger).name }) { c, v ->
                    c.copy(shortcuts = c.shortcuts + (trigger.name to v))
                }
            } + listOf(
                Act("Puntero con mando", { if (Actions.pointerEnabled()) "activado" else "pulsa A para activarlo" }) {
                    if (Actions.pointerEnabled()) Actions.open(Settings.ACTION_ACCESSIBILITY_SETTINGS) else enablePointer()
                },
                Act("Botón del puntero", { pointerKeyName(Env.cfg.value.pointerKey) }) {
                    Env.nav.push(KeyCaptureScreen("Botón del puntero") { code -> Env.cfg.update { it.copy(pointerKey = code) } })
                },
            ) + (if (DeviceModes.backButtonsSupported()) listOf(
                // El M2 de fábrica no envía ninguna tecla: Heaven le asigna una y la usa para el puntero.
                Act("Usar M2 (botón trasero) para el puntero", { if (DeviceModes.m2Key() == DeviceModes.M2_KEY && Env.cfg.value.pointerKey == DeviceModes.M2_KEY) "en uso" else "" }) {
                    useM2ForPointer()
                },
            ) else emptyList()) + listOf(
                Act("Volver a L3 + R3 para el puntero") { Env.cfg.update { it.copy(pointerKey = 0) } },
            ),
    ),
    Section(
        "Copia de seguridad", "Un tema guarda el aspecto; la copia completa lo guarda todo",
        listOf(
            Act("Exportar tema") { Backup.export(themeOnly = true) },
            Act("Exportar copia completa") { Backup.export(themeOnly = false) },
            Act("Importar tema o copia") { Backup.import() },
        ),
    ),
    Section(
        "Sistema", "Heaven",
        listOf(
            Act("Usar Heaven como pantalla de inicio") { Actions.open(Settings.ACTION_HOME_SETTINGS) },
            Toggle("Avisar si la consola se calienta jugando (45 °C)", { it.heatAlert }) { c, v -> c.copy(heatAlert = v) },
            Act("Ajustes de Android") { Actions.open(Settings.ACTION_SETTINGS) },
            Act("Archivos y limpieza") { Env.nav.push(FilesScreen()) },
            Act("Recargar lista de apps") { Env.icons.clear(); Env.apps.reload() },
            Act("Memoria que usa Heaven", { "${android.os.Debug.getPss() / 1024} MB" }) { },
            Act("Versión", { versionText() }) { },
            Act("Exportar diagnóstico del dispositivo", { "guardar .txt" }) { Diagnostics.export() },
        ),
    ),
)

class SettingsScreen : Screen, Closable {
    override val overlay get() = true
    override var closing by mutableStateOf(false)
    private val all = sections()
    private var section by mutableIntStateOf(0)
    private var zone by mutableIntStateOf(0) // 0 = secciones, 1 = filas
    private var row by mutableIntStateOf(0)

    private fun step(r: SRow, dir: Int) {
        when (r) {
            is Toggle -> Env.cfg.update { r.set(it, !r.get(it)) }
            is Choice -> {
                val options = r.options()
                if (options.isEmpty()) return
                val i = options.indexOfFirst { it.first == r.get(Env.cfg.value) }.coerceAtLeast(0)
                val next = options[(i + dir + options.size) % options.size].first
                Env.cfg.update { r.set(it, next) }
            }
            is Range -> Env.cfg.update { r.set(it, (r.get(it) + dir * r.step).coerceIn(r.min, r.max)) }
            is Act -> r.run()
        }
    }

    override fun onPad(e: PadEvent): Boolean {
        if (e.long) return true
        val rows = all[section].rows
        row = row.coerceIn(0, rows.lastIndex)
        val current = rows[row]
        when (e.btn) {
            Btn.L1 -> { section = (section + all.size - 1) % all.size; row = 0 }
            Btn.R1 -> { section = (section + 1) % all.size; row = 0 }
            Btn.UP, Btn.RS_UP -> if (zone == 0) { section = (section + all.size - 1) % all.size; row = 0 } else row = (row + rows.size - 1) % rows.size
            Btn.DOWN, Btn.RS_DOWN -> if (zone == 0) { section = (section + 1) % all.size; row = 0 } else row = (row + 1) % rows.size
            Btn.RIGHT -> if (zone == 0) zone = 1 else if (current is Choice || current is Range) step(current, +1)
            Btn.LEFT -> if (zone == 1) { if (current is Choice || current is Range) step(current, -1) else zone = 0 }
            Btn.A -> if (zone == 0) zone = 1 else step(current, +1)
            Btn.B -> if (zone == 1) zone = 0 else Env.nav.pop()
            Btn.START -> Env.nav.pop()
            // SELECT guarda esta ventana y abre CNTRL al momento.
            Btn.SELECT -> { Env.nav.pop(); Env.nav.push(QuickPanel()) }
            else -> Unit
        }
        return true
    }

    @Composable
    override fun Content() {
        val cfg = LocalCfg.current
        val accent = LocalAccent.current
        val current = all[section]
        val list = rememberLazyListState()
        LaunchedEffect(row, section) { list.animateScrollToItem((row - 4).coerceAtLeast(0)) }
        // Nace de la esquina superior derecha, del lado del botón START.
        Popup(TransformOrigin(1f, 0f), closing, { Env.nav.pop() }) {
          Window(Modifier.align(Alignment.Center).width(1120.dp).height(640.dp)) {
            Row(Modifier.padding(start = 8.dp, bottom = 14.dp), verticalAlignment = Alignment.Bottom) {
                T("Custom", 24, weight = FontWeight.Medium)
                Spacer(Modifier.width(16.dp))
                T(current.note, 14, P.Dim, modifier = Modifier.weight(1f).padding(bottom = 3.dp))
            }
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                Column(Modifier.width(250.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    all.forEachIndexed { i, s ->
                        val on = i == section
                        Row(
                            Modifier.fillMaxWidth().height(48.dp)
                                .background(if (on) accent else Color.Transparent, chamfer(12.dp))
                                .brackets(on && zone == 0, P.Ink, gap = 3.dp, stroke = 2.5.dp, radius = 16.8.dp)
                                .tap { section = i; row = 0; zone = 1 }.padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            T(s.title, 16, if (on) P.OnAccent else P.Dim, if (on) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    if (current.title == "Reloj") {
                        Panel(Modifier.fillMaxWidth().height(132.dp)) {
                            val now by rememberNow(cfg.look.clockSeconds)
                            Row(Modifier.fillMaxSize().padding(horizontal = 26.dp), verticalAlignment = Alignment.CenterVertically) {
                                ClockView(cfg.look, if (cfg.look.clockStyle in listOf("apilado", "palabras")) 84.dp else 70.dp, now)
                                Spacer(Modifier.weight(1f))
                                if (cfg.look.clockDate) Label(dateText(now), P.Ink, size = 13)
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                    }
                    if (current.title == "Tema") {
                        Panel(Modifier.fillMaxWidth().height(132.dp)) {
                            Column(Modifier.fillMaxSize().padding(horizontal = 26.dp), verticalArrangement = Arrangement.Center) {
                                T("Heaven", 30, weight = FontWeight.Medium)
                                Spacer(Modifier.height(8.dp))
                                Label("${fontTheme(cfg.look.font).name} — 0123456789", accent, size = 14)
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                    }
                    LazyColumn(state = list, contentPadding = PaddingValues(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        itemsIndexed(current.rows) { i, r ->
                            val on = zone == 1 && i == row
                            val tap = Modifier.tap { zone = 1; row = i; step(r, +1) }
                            when (r) {
                                is Toggle -> ListRow(r.title, "", on, tap) { DropSwitch(r.get(cfg)) }
                                is Choice -> {
                                    val options = r.options()
                                    val value = r.get(cfg)
                                    if (r.swatches) ListRow(r.title, "", on, tap) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                            options.forEach { (id, _) ->
                                                val picked = id == value
                                                Box(
                                                    Modifier.size(if (picked) 26.dp else 18.dp)
                                                        .then(if (picked) Modifier.border(2.dp, P.Ink, chamfer(7.dp)).padding(4.dp) else Modifier)
                                                        .background(Color(id.toLong()), chamfer(if (picked) 5.dp else 6.dp))
                                                )
                                            }
                                        }
                                    } else ListRow(r.title, options.firstOrNull { it.first == value }?.second ?: "—", on, tap, arrows = true)
                                }
                                is Range -> ListRow(r.title, "", on, tap) {
                                    T("${r.get(cfg)}${if (r.unit.isEmpty()) "" else " " + r.unit}", 14, if (on) accent else P.Dim, mono = true)
                                    Spacer(Modifier.width(16.dp))
                                    DropSlider((r.get(cfg) - r.min).toFloat() / (r.max - r.min), if (on) accent else P.Dim, Modifier.width(210.dp))
                                }
                                is Act -> ListRow(r.title, r.value(), on, tap)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            HintBar(listOf("A" to "Cambiar", "◀ ▶" to "Ajustar", "L1 R1" to "Sección", "B" to "Volver"))
          }
        }
    }
}
