package com.fpapef.topp

import android.app.Application
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Rend les écrans en PNG pour vérifier le design sans téléphone. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class HudScreenshotTest {
    private val out = "build/shots"

    private val noop = object : UiActions {
        override fun record() {}
        override fun mode() {}
        override fun recenter() {}
        override fun flipCamera() {}
        override fun settings() {}
        override fun chooseGimbal() {}
        override fun joystick(x: Float, y: Float) {}
        override fun follow() {}
        override fun pickPerson(x: Float, y: Float) {}
        override fun lens(z: Float) {}
    }

    /** Fausse image de caméra : ciel, horizon, bâtiments, sol de latérite. */
    @Composable
    private fun FakeScene() {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF7FA9C9), Color(0xFFD9C9A8)))))
        Canvas(Modifier.fillMaxSize()) {
            val h = size.height
            val w = size.width
            drawRect(Color(0xFFB8643A), Offset(0f, h * 0.68f), Size(w, h * 0.32f))
            val blocks = listOf(0.05f to 0.30f, 0.18f to 0.22f, 0.33f to 0.40f, 0.52f to 0.28f, 0.66f to 0.35f, 0.82f to 0.25f)
            blocks.forEachIndexed { i, (x, hh) ->
                drawRect(
                    if (i % 2 == 0) Color(0xFFEDE6D8) else Color(0xFFC9B79A),
                    Offset(w * x, h * (0.68f - hh)), Size(w * 0.12f, h * hh),
                )
            }
            drawRect(Color(0xFF2E3B2A), Offset(w * 0.44f, h * 0.45f), Size(w * 0.05f, h * 0.23f))
        }
    }

    @Composable
    private fun Themed(content: @Composable () -> Unit) =
        MaterialTheme(colorScheme = darkColorScheme(primary = Tk.Amber, surface = Tk.Panel)) { content() }

    @Test
    @Config(qualifiers = "w891dp-h412dp-land-xhdpi")
    fun landscapeReadyRecording() {
        captureRoboImage("$out/1_paysage_enregistrement.png") {
            Themed {
                ToppHud(
                    UiState(
                        gimbal = GimbalBle.Status.READY, modeLabel = "2", watchConnected = true,
                        recording = true, recSeconds = 42, relPan = 24f, relTilt = -6f, gesturesActive = true,
                    ),
                    noop,
                ) { FakeScene() }
            }
        }
    }

    @Test
    @Config(qualifiers = "w891dp-h412dp-land-xhdpi")
    fun landscapeIdle() {
        captureRoboImage("$out/2_paysage_pret.png") {
            Themed {
                ToppHud(
                    UiState(gimbal = GimbalBle.Status.READY, modeLabel = "1", watchConnected = false, relPan = 0f, relTilt = 0f),
                    noop,
                ) { FakeScene() }
            }
        }
    }

    @Test
    @Config(qualifiers = "w412dp-h891dp-port-xhdpi")
    fun portraitSearching() {
        captureRoboImage("$out/3_portrait_recherche.png") {
            Themed { ToppHud(UiState(gimbal = GimbalBle.Status.SCANNING), noop) { FakeScene() } }
        }
    }

    @Test
    @Config(qualifiers = "w412dp-h3000dp-port-xhdpi")
    fun settingsSheet() {
        captureRoboImage("$out/4_reglages.png") {
            Themed {
                Box(Modifier.fillMaxSize().background(Tk.Panel)) {
                    SettingsContent(
                        SettingsValues(0.35f, invertX = false, invertY = true, connected = true),
                        object : SettingsActions {
                            override fun speed(v: Float) {}
                            override fun invertX(v: Boolean) {}
                            override fun invertY(v: Boolean) {}
                            override fun gestureHold(v: Boolean) {}
                            override fun gestureSens(v: Float) {}
                            override fun gestureInvX(v: Boolean) {}
                            override fun gestureInvY(v: Boolean) {}
                            override fun followMode(m: FollowMode) {}
                            override fun followGain(v: Float) {}
                            override fun followRelearn() {}
                            override fun followRecalibrate() {}
                            override fun followAutoZoom(v: Boolean) {}
                            override fun followFraming(f: Framing) {}
                            override fun blockUltraWide(v: Boolean) {}
                            override fun autoCorrectFront(v: Boolean) {}
                            override fun videoUhd(v: Boolean) {}
                            override fun video60(v: Boolean) {}
                            override fun setHome() {}
                            override fun relearn() {}
                            override fun toggleConnection() {}
                            override fun choose() {}
                            override fun journal() {}
                        },
                    )
                }
            }
        }
    }

    @Test
    @Config(qualifiers = "w412dp-h891dp-port-xhdpi")
    fun pickerSheet() {
        captureRoboImage("$out/5_choix_nacelle.png") {
            Themed {
                Box(Modifier.fillMaxSize().background(Tk.Panel)) {
                    PickerContent(
                        listOf(
                            GimbalBle.Seen("D4:1A:22:07:5B:90", "SV3-A12B34C", -58, true),
                            GimbalBle.Seen("5C:F3:70:12:9A:01", "Galaxy Watch7 (A1B2)", -44, false),
                            GimbalBle.Seen("7A:11:9E:42:00:3D", null, -81, false),
                        )
                    ) {}
                }
            }
        }
    }

    private val ready = UiState(
        gimbal = GimbalBle.Status.READY, modeLabel = "1", watchConnected = true, relPan = 8f, relTilt = -3f,
    )

    @Test
    @Config(qualifiers = "w891dp-h412dp-land-xhdpi")
    fun followCamera() {
        captureRoboImage("$out/6_suivi_camera.png") {
            Themed {
                ToppHud(
                    ready.copy(
                        recording = true, recSeconds = 75,
                        lenses = listOf(1f, 3f, 5f), zoom = 2.4f,
                        follow = FollowUi(
                            on = true, status = FollowStatus.CAMERA, you = true, autoZoom = true,
                            people = listOf(
                                PersonUi(1, Box(0.42f, 0.2f, 0.57f, 0.95f), target = true),
                                PersonUi(2, Box(0.72f, 0.33f, 0.8f, 0.78f), target = false),
                            ),
                        ),
                    ),
                    noop,
                ) { FakeScene() }
            }
        }
    }

    @Test
    @Config(qualifiers = "w412dp-h891dp-port-xhdpi")
    fun followIdentify() {
        captureRoboImage("$out/7_suivi_identifier.png") {
            Themed {
                ToppHud(
                    ready.copy(
                        lenses = listOf(1f, 3f, 5f), zoom = 1f,
                        follow = FollowUi(
                            on = true, status = FollowStatus.IDENTIFY, aspect = 9f / 16f, autoZoom = true,
                            people = listOf(
                                PersonUi(1, Box(0.1f, 0.42f, 0.36f, 0.78f), target = false),
                                PersonUi(2, Box(0.55f, 0.38f, 0.9f, 0.82f), target = false),
                            ),
                        ),
                    ),
                    noop,
                ) { FakeScene() }
            }
        }
    }

    @Test
    @Config(qualifiers = "w891dp-h412dp-land-xhdpi")
    fun followGps() {
        captureRoboImage("$out/8_suivi_gps.png") {
            Themed {
                ToppHud(
                    ready.copy(follow = FollowUi(on = true, status = FollowStatus.GPS, distance = 23)),
                    noop,
                ) { FakeScene() }
            }
        }
    }
}
