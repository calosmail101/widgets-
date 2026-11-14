package dev.palanca.launcher.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

val PalancaJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
}

/** Icono personalizado de una app. Todo es opcional: lo que falte usa el ajuste global. */
@Serializable
data class IconOverride(
    val pack: String? = null,
    val drawable: String? = null,
    val file: String? = null,
    val shape: String? = null,
    val scale: Float = 1f,
    val label: String? = null,
)

@Serializable
data class WidgetSlot(val id: Int, val provider: String, val cols: Int = 2)

/** Todo lo visual: es lo que viaja en un "tema" exportado. */
@Serializable
data class Look(
    val accent: Long = 0xFFFF6A2B,
    val font: String = "grotesk",
    val clockStyle: String = "matriz",
    val clock24: Boolean = true,
    val clockDate: Boolean = true,
    val clockSeconds: Boolean = false,
    val iconShape: String = "squircle",
    val iconPack: String? = null,
    val iconScale: Float = 1f,
    val labels: Boolean = true,
    val wallpaper: String = "trama",
    val wallpaperFile: String? = null,
    val wallpaperDim: Int = 45,
    val drawerCols: Int = 7,
    val dockSize: Int = 84,
    val cardContinue: Boolean = true,
    /** Estadísticas de Inicio, en orden (nombres de Metric). */
    val stats: List<String> = listOf("BATTERY", "STORAGE"),
    val statSize: String = "auto",
    val statStyle: String = "segmentos",
    /** Versión del diseño ya aplicada a esta configuración (ver [migrate]). */
    val design: Int = 1,
    /** Ventanas translúcidas con el fondo desenfocado. */
    val glass: Boolean = true,
    /** Tamaño del texto en porcentaje. */
    val textScale: Int = 112,
    /** Desplazamiento de las piezas de Inicio (reloj, botones, tarjetas) en unidades de diseño: "x,y". */
    val offsets: Map<String, String> = emptyMap(),
    /** Estilo de color de los iconos: normal, tintado (acento) o claro. */
    val iconTint: String = "normal",
)

/**
 * Un ajuste de la consola con dos estados, descubierto comparando el sistema antes y después de
 * cambiar su mosaico. [kind] es "ajuste" (tabla [table]) o "propiedad".
 */
@Serializable
data class Learned(
    val kind: String, val table: String = "", val key: String,
    /** Valor del ajuste para cada estado y su nombre visible, en el mismo orden. */
    val values: List<String> = emptyList(), val names: List<String> = emptyList(),
    // Formato antiguo (dos estados); se sigue leyendo para no perder lo ya aprendido.
    val a: String = "", val b: String = "", val nameA: String = "", val nameB: String = "",
) {
    fun states(): List<Pair<String, String>> = if (values.isNotEmpty()) values.zip(names) else listOf(a to nameA, b to nameB)
}

@Serializable
data class Config(
    val look: Look = Look(),
    val pinned: List<String> = emptyList(),
    val pinnedInit: Boolean = false,
    val hidden: Set<String> = emptySet(),
    val categories: Map<String, String> = emptyMap(),
    val icons: Map<String, IconOverride> = emptyMap(),
    val widgets: List<WidgetSlot> = emptyList(),
    val swapAB: Boolean = false,
    val shortcuts: Map<String, String> = emptyMap(),
    val sound: Boolean = true,
    val soundPack: String = "toque",
    /** Canciones de fondo elegidas por el usuario (URIs con permiso persistente). */
    val music: List<String> = emptyList(),
    val musicOn: Boolean = true,
    val musicVolume: Int = 15,
    /** App que abre el botón Jugar de Inicio (clave paquete/actividad). */
    val playApp: String? = null,
    /** Tecla que enciende y apaga el puntero; 0 = mantener L3 + R3. */
    val pointerKey: Int = 0,
    /** Controles de la consola aprendidos con el detector (estilo de mando, modo de gatillos). */
    val learned: Map<String, Learned> = emptyMap(),
    val haptics: Boolean = true,
) {
    fun look(f: Look.() -> Look) = copy(look = look.f())
    fun icon(key: String, f: IconOverride.() -> IconOverride): Config {
        val next = (icons[key] ?: IconOverride()).f()
        return copy(icons = if (next == IconOverride()) icons - key else icons + (key to next))
    }
}

/**
 * Lleva una configuración antigua al diseño actual. Solo toca los valores que seguían
 * en su valor de fábrica: lo que el usuario eligió a mano se respeta.
 */
fun migrate(old: Config): Config {
    // Los paquetes de sonido antiguos ya no existen: pasan al nuevo por defecto.
    val c = if (old.soundPack in setOf("toque", "burbuja", "cristal", "arcade")) old else old.copy(soundPack = "toque")
    if (c.look.design >= 2) return c
    val l = c.look
    return c.copy(
        look = l.copy(
            design = 2,
            font = if (l.font == "grotesk") "halo" else l.font,
            accent = if (l.accent == 0xFFFF6A2B) 0xFF7C8CFF else l.accent,
            clockStyle = if (l.clockStyle == "matriz") "duo" else l.clockStyle,
            wallpaper = if (l.wallpaper == "trama") "aurora" else l.wallpaper,
            dockSize = if (l.dockSize == 84) 92 else l.dockSize,
        )
    )
}

@Serializable
data class Usage(val last: Long = 0, val count: Int = 0, val totalMs: Long = 0, val lastSessionMs: Long = 0)

@Serializable
data class Stats(val usage: Map<String, Usage> = emptyMap(), val pendingKey: String? = null, val pendingAt: Long = 0)

/** Estado persistente en un JSON. Las escrituras se agrupan y van fuera del hilo principal. */
class JsonStore<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    private val default: () -> T,
    scope: CoroutineScope,
) {
    private val state = MutableStateFlow(load())
    val flow: StateFlow<T> get() = state
    val value: T get() = state.value
    private val dirty = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch(Dispatchers.IO) {
            for (signal in dirty) {
                runCatching {
                    val tmp = File(file.parentFile, file.name + ".tmp")
                    tmp.writeText(PalancaJson.encodeToString(serializer, state.value))
                    tmp.renameTo(file)
                }
            }
        }
    }

    private fun load(): T = runCatching {
        if (file.exists()) PalancaJson.decodeFromString(serializer, file.readText()) else default()
    }.getOrElse { default() }

    fun update(f: (T) -> T) {
        val before = state.value
        val after = f(before)
        if (after == before) return
        state.value = after
        dirty.trySend(Unit)
    }
}
