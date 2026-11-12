package dev.palanca.launcher.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import dev.palanca.launcher.Env
import dev.palanca.launcher.data.DEFAULT_PINS

/** Alto de la pantalla en unidades de diseño. */
const val DESIGN_HEIGHT = 720f

@Composable
fun PalancaRoot() {
    val cfg by Env.cfg.flow.collectAsState()
    val apps by Env.apps.apps.collectAsState()

    // Primera vez: fija unas cuantas apps conocidas para que Inicio no salga vacío.
    LaunchedEffect(apps.isNotEmpty()) {
        if (apps.isNotEmpty() && !Env.cfg.value.pinnedInit) {
            val picks = DEFAULT_PINS.mapNotNull { pkg -> apps.firstOrNull { it.pkg == pkg }?.key }.take(7)
            Env.cfg.update { it.copy(pinned = (it.pinned + picks).distinct(), pinnedInit = true) }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Toda la interfaz se mide en unidades de diseño: 720 de alto, sea cual sea la pantalla.
        val density = Density(constraints.maxHeight / DESIGN_HEIGHT, 1f)
        CompositionLocalProvider(
            LocalDensity provides density,
            LocalCfg provides cfg,
            LocalAccent provides Color(cfg.look.accent),
        ) {
            val stack = Env.nav.stack
            val base = stack.indexOfLast { !it.overlay }.coerceAtLeast(0)
            for (i in base until stack.size) {
                val screen = stack[i]
                key(screen) { Box(Modifier.fillMaxSize()) { screen.Content() } }
            }
        }
    }
}
