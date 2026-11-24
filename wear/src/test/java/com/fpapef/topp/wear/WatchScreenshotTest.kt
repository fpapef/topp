package com.fpapef.topp.wear

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.shape.CircleShape
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w227dp-h227dp-round-xhdpi")
class WatchScreenshotTest {
    private val out = "build/shots"

    private fun shot(
        name: String, state: PhoneState, phoneOk: Boolean = true, rec: Long = 0,
        gesture: Boolean = false, vec: Offset = Offset.Zero,
    ) = captureRoboImage("$out/$name.png") {
        Box(Modifier.fillMaxSize().clip(CircleShape)) {
            WatchScreen(state, phoneOk, rec, gesture, vec, { _, _, _ -> }, {}, {})
        }
    }

    @Test fun ready() = shot("montre_1_prete", PhoneState(gimbal = true, mode = "2", pan = 24, tilt = -6))
    @Test fun recording() = shot("montre_2_enregistrement", PhoneState(gimbal = true, mode = "1", recording = true, pan = -40, tilt = 10), rec = 42)
    @Test fun noPhone() = shot("montre_3_sans_telephone", PhoneState(), phoneOk = false)
    @Test fun gestures() = shot("montre_5_gestes", PhoneState(gimbal = true, mode = "1", pan = 12, tilt = 4), gesture = true, vec = Offset(0.55f, -0.3f))
    @Test fun searching() = shot("montre_4_recherche", PhoneState(gimbal = false, text = "Recherche de la nacelle"))
    @Test fun followCamera() = shot(
        "montre_6_suivi_camera",
        PhoneState(gimbal = true, mode = "1", pan = 18, tilt = 2, following = true, followText = "Suivi : caméra", followKind = "ok"),
    )
    @Test fun followWave() = shot(
        "montre_7_fais_coucou",
        PhoneState(gimbal = true, mode = "1", pan = 0, tilt = 0, following = true, followText = "Fais coucou", followKind = "id"),
    )
    @Test fun followGps() = shot(
        "montre_8_suivi_gps",
        PhoneState(gimbal = true, mode = "1", pan = -64, tilt = 0, recording = true, following = true, followText = "Suivi : GPS 23 m", followKind = "ok"),
        rec = 95,
    )
}
