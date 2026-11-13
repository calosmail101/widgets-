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
)

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
    val haptics: Boolean = true,
) {
    fun look(f: Look.() -> Look) = copy(look = look.f())
    fun icon(key: String, f: IconOverride.() -> IconOverride): Config {
        val next = (icons[key] ?: IconOverride()).f()
        return copy(icons = if (next == IconOverride()) icons - key else icons + (key to next))
    }
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
