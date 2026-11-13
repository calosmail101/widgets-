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
import androidx.compose.ui.unit.dp
import dev.palanca.launcher.data.DEFAULT_PINS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Alto de la pantalla en unidades de diseño. */
const val DESIGN_HEIGHT = 720f

@Composable
fun PalancaRoot() {
    val cfg by Env.cfg.flow.collectAsState()
    val apps by Env.apps.apps.collectAsState()
    val iconGen by Env.apps.generation.collectAsState()

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
            LocalIconGen provides iconGen,
        ) {
            // Dibuja de antemano los iconos del cajón y de Inicio: al abrir el cajón ya están todos.
            val look = cfg.look
            val sizes = listOf(drawerIconSize(look.drawerCols), look.dockSize.dp)
            LaunchedEffect(apps, iconGen, look.iconShape, look.iconPack, look.iconScale, look.drawerCols, look.dockSize) {
                if (Env.syncIcons) return@LaunchedEffect
                val current = Env.cfg.value
                withContext(Dispatchers.Default) {
                    for (app in apps) for (size in sizes) {
                        val px = with(density) { (size * iconScale(current, app)).roundToPx() }
                        runCatching { Env.icons.render(Env.icons.keyFor(app, current, px, iconGen), app, current, px) }
                    }
                }
            }
            val stack = Env.nav.stack
            val base = stack.indexOfLast { !it.overlay }.coerceAtLeast(0)
            for (i in base until stack.size) {
                val screen = stack[i]
                key(screen) { Box(Modifier.fillMaxSize()) { screen.Content() } }
            }
        }
    }
}
