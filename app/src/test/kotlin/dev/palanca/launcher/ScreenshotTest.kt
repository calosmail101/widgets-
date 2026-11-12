package dev.palanca.launcher

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.palanca.launcher.data.AppEntry
import dev.palanca.launcher.data.Cat
import dev.palanca.launcher.data.Usage
import dev.palanca.launcher.input.Btn
import dev.palanca.launcher.input.PadEvent
import dev.palanca.launcher.system.Actions
import dev.palanca.launcher.ui.Battery
import dev.palanca.launcher.ui.DrawerScreen
import dev.palanca.launcher.ui.HomeScreen
import dev.palanca.launcher.ui.IconEditorScreen
import dev.palanca.launcher.ui.KeyboardScreen
import dev.palanca.launcher.ui.PalancaRoot
import dev.palanca.launcher.ui.QuickPanel
import dev.palanca.launcher.ui.Screen
import dev.palanca.launcher.ui.SettingsScreen
import dev.palanca.launcher.ui.appMenu
import dev.palanca.launcher.ui.fixedBattery
import dev.palanca.launcher.ui.fixedNow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDateTime

/**
 * Dibuja las pantallas reales a PNG (1920×1080, como la Retroid Pocket 6) con apps de ejemplo.
 * Sirve para revisar el diseño sin dispositivo: `./gradlew :app:testDebugUnitTest` y mirar app/build/shots.
 * Los iconos son placas con iniciales porque aquí no hay apps instaladas.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w731dp-h411dp-land-420dpi", application = Application::class)
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private fun app(label: String, pkg: String, cat: Cat) = AppEntry("$pkg/.Main", pkg, ".Main", label, cat)

    private val apps = listOf(
        app("YouTube", "com.google.android.youtube", Cat.MULTIMEDIA), app("Play Store", "com.android.vending", Cat.APPS),
        app("Chrome", "com.android.chrome", Cat.APPS), app("RetroArch", "com.retroarch", Cat.EMULADORES),
        app("Dolphin", "org.dolphinemu.dolphinemu", Cat.EMULADORES), app("PPSSPP", "org.ppsspp.ppsspp", Cat.EMULADORES),
        app("Ajustes", "com.android.settings", Cat.SISTEMA), app("Spotify", "com.spotify.music", Cat.MULTIMEDIA),
        app("Netflix", "com.netflix.mediaclient", Cat.MULTIMEDIA), app("DuckStation", "com.github.stenzek.duckstation", Cat.EMULADORES),
        app("Moonlight", "com.limelight", Cat.JUEGOS), app("Steam Link", "com.valvesoftware.steamlink", Cat.JUEGOS),
        app("Archivos", "com.android.documentsui", Cat.SISTEMA), app("Galería", "com.android.gallery3d", Cat.MULTIMEDIA),
        app("Cámara", "com.android.camera2", Cat.SISTEMA), app("Calculadora", "com.android.calculator2", Cat.APPS),
        app("Gmail", "com.google.android.gm", Cat.APPS), app("Maps", "com.google.android.apps.maps", Cat.APPS),
        app("Discord", "com.discord", Cat.APPS), app("Twitch", "tv.twitch.android.app", Cat.MULTIMEDIA),
        app("melonDS", "me.magnum.melonds", Cat.EMULADORES), app("Azahar", "org.azahar_emu.azahar", Cat.EMULADORES),
        app("Reloj", "com.android.deskclock", Cat.SISTEMA), app("Telegram", "org.telegram.messenger", Cat.APPS),
    ).sortedBy { it.sortKey }

    @Before fun setUp() {
        Env.init(ApplicationProvider.getApplicationContext(), tmp.root)
        Env.syncIcons = true
        Env.nav.stack.clear()
        fixedNow = LocalDateTime.of(2026, 11, 12, 21, 47, 5)
        fixedBattery = Battery(82, false)
        Actions.storageOverride = 148_000_000_000L to 256_000_000_000L
        Env.apps.apps.value = apps
        val dolphin = apps.first { it.label == "Dolphin" }
        Env.cfg.update { it.copy(pinnedInit = true, pinned = listOf("YouTube", "Play Store", "Chrome", "RetroArch", "Dolphin", "Spotify", "Ajustes").map { n -> apps.first { a -> a.label == n }.key }) }
        Env.stats.update { it.copy(usage = mapOf(dolphin.key to Usage(System.currentTimeMillis() - 2 * 3_600_000L, 5, 9_000_000, 74 * 60_000L))) }
    }

    private fun shot(name: String, vararg screens: Screen, pad: List<Btn> = emptyList()) {
        Env.nav.push(HomeScreen())
        screens.forEach(Env.nav::push)
        compose.setContent { PalancaRoot() }
        pad.forEach { b -> compose.runOnIdle { Env.nav.top!!.onPad(PadEvent(b)) } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/shots/$name.png")
    }

    @Test fun inicio() = shot("1-inicio", pad = listOf(Btn.RIGHT, Btn.RIGHT))
    @Test fun inicioSegmentos() {
        Env.cfg.update { it.look { copy(clockStyle = "segmentos", accent = 0xFF5FE3B0, iconShape = "chaflan", wallpaper = "negro") } }
        shot("1b-inicio-segmentos", pad = listOf(Btn.UP))
    }
    @Test fun cajon() = shot("2-cajon", DrawerScreen(), pad = listOf(Btn.RIGHT, Btn.RIGHT, Btn.DOWN))
    @Test fun menuApp() = shot("3-menu-app", DrawerScreen(), appMenu(apps[3]), pad = listOf(Btn.DOWN, Btn.DOWN))
    @Test fun editarIcono() = shot("4-editar-icono", IconEditorScreen(apps.first { it.label == "Dolphin" }), pad = listOf(Btn.DOWN))
    @Test fun personalizar() = shot("5-personalizar", SettingsScreen(), pad = listOf(Btn.RIGHT))
    @Test fun mandos() = shot("5b-mandos", SettingsScreen(), pad = listOf(Btn.R1, Btn.R1, Btn.R1, Btn.R1, Btn.R1, Btn.RIGHT, Btn.DOWN))
    @Test fun buscar() = shot("6-buscar", KeyboardScreen.search(), pad = listOf(Btn.RIGHT, Btn.RIGHT, Btn.RIGHT, Btn.A, Btn.LEFT, Btn.A, Btn.UP, Btn.UP, Btn.RIGHT))
    @Test fun panel() = shot("7-panel-rapido", QuickPanel(), pad = listOf(Btn.DOWN))
    @Test fun widgets() = shot("8-widgets", pad = listOf(Btn.R1))
}
