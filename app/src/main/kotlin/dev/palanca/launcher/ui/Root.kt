package dev.palanca.launcher.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import dev.palanca.launcher.Env
import androidx.compose.ui.unit.dp
import dev.palanca.launcher.data.DEFAULT_PINS
import dev.palanca.launcher.system.Music
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Alto de la pantalla en unidades de diseño. */
const val DESIGN_HEIGHT = 660f

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
            LocalFonts provides fontTheme(cfg.look.font),
            LocalTextScale provides cfg.look.textScale.coerceIn(90, 150) / 100f,
        ) {
            val accentColor = Color(cfg.look.accent)
            SideEffect { P.accentChanged(accentColor) }
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
            // Una ventana que se está cerrando ya no cuenta como "encima": lo de debajo recupera la nitidez.
            val covered = stack.indexOfLast { it.overlay && !(it is Closable && it.closing) }
            // Música de fondo: solo con Inicio o el cajón como pantalla de base y el launcher delante.
            val foreground by Env.foreground.collectAsState()
            val calm = stack.getOrNull(base).let { it is HomeScreen || it is DrawerScreen }
            LaunchedEffect(calm, foreground, cfg.music, cfg.musicOn, cfg.musicVolume) {
                Music.update(calm && foreground && cfg.musicOn, cfg.music, cfg.musicVolume)
            }
            for (i in base until stack.size) {
                val screen = stack[i]
                // Las pantallas entran subiendo y ganando opacidad; el cajón sube desde más abajo.
                // Inicio no se anima (ya está ahí) y las ventanas traen su propia entrada.
                val motion = when {
                    screen is HomeScreen || screen.overlay -> Modifier
                    else -> Modifier.enter(90.dp, 0.93f, 440)
                }
                // Cristal: lo que queda debajo de una ventana se desenfoca (Android 12+; antes no hace nada).
                // Se amplía un 8 % para que el borde oscuro que deja el desenfoque quede fuera de la pantalla.
                val depth = if (cfg.look.glass && i < covered) Modifier.graphicsLayer { scaleX = 1.08f; scaleY = 1.08f }.blur(26.dp) else Modifier
                key(screen) { Box(Modifier.fillMaxSize().then(motion).then(depth)) { screen.Content() } }
            }
        }
    }
}
