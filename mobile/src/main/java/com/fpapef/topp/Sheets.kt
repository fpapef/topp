package com.fpapef.topp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

data class SettingsValues(
    val speed: Float,
    val invertX: Boolean,
    val invertY: Boolean,
    val connected: Boolean,
    val gestureHold: Boolean = false,
    val gestureSens: Float = 1f,
    val gestureInvX: Boolean = false,
    val gestureInvY: Boolean = false,
    val followMode: FollowMode = FollowMode.CAMERA_WATCH,
    val followGain: Float = 1f,
    val followAutoZoom: Boolean = true,
    val autoCorrectFront: Boolean = true,
    val videoUhd: Boolean = true,
    val video60: Boolean = false,
    /** Format réellement obtenu, ex. « 3840 × 2160, 30 images/s ». */
    val videoInfo: String = "",
)

interface SettingsActions {
    fun speed(v: Float)
    fun invertX(v: Boolean)
    fun invertY(v: Boolean)
    fun gestureHold(v: Boolean)
    fun gestureSens(v: Float)
    fun gestureInvX(v: Boolean)
    fun gestureInvY(v: Boolean)
    fun followMode(m: FollowMode)
    fun followGain(v: Float)
    fun followRelearn()
    fun followRecalibrate()
    fun followAutoZoom(v: Boolean)
    fun autoCorrectFront(v: Boolean)
    fun videoUhd(v: Boolean)
    fun video60(v: Boolean)
    fun setHome()
    fun relearn()
    fun toggleConnection()
    fun choose()
    fun journal()
}

@Composable
private fun SheetTitle(text: String, sub: String? = null) {
    Text(text, style = hud(24.sp, FontWeight.SemiBold, shadow = false))
    if (sub != null) {
        Spacer(Modifier.height(6.dp))
        Text(sub, style = hud(14.sp, FontWeight.Normal, Tk.SandDim, shadow = false))
    }
    Spacer(Modifier.height(18.dp))
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x1FEADFC8)))
    Spacer(Modifier.height(14.dp))
    Text(title, style = hud(13.sp, FontWeight.Medium, Tk.SandDim, shadow = false))
    Spacer(Modifier.height(6.dp))
    content()
    Spacer(Modifier.height(14.dp))
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, detail: String? = null) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = hud(16.sp, FontWeight.Normal, shadow = false))
            if (detail != null) {
                Spacer(Modifier.height(2.dp))
                Text(detail, style = hud(13.sp, FontWeight.Normal, Tk.SandDim, shadow = false))
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Tk.Panel,
                checkedTrackColor = Tk.Amber,
                uncheckedThumbColor = Tk.SandDim,
                uncheckedTrackColor = Tk.Panel,
                uncheckedBorderColor = Tk.SandFaint,
            ),
        )
    }
}

@Composable
private fun ActionRow(label: String, color: Color = Tk.Sand, onClick: () -> Unit) {
    Text(
        label,
        style = hud(16.sp, FontWeight.Medium, color, shadow = false),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 11.dp),
    )
}

/** Choix exclusif entre deux ou trois options, en pastilles côte à côte. */
@Composable
private fun Choice(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, Tk.SandFaint, RoundedCornerShape(12.dp))
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).background(if (on) Tk.Amber else Color.Transparent)
                    .clickable { onSelect(i) }.padding(vertical = 11.dp, horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = hud(14.sp, FontWeight.Medium, if (on) Tk.Panel else Tk.Sand, shadow = false))
            }
        }
    }
}

/** Choix exclusif entre options détaillées, empilées. */
@Composable
private fun ChoiceList(options: List<Pair<String, String>>, selected: Int, onSelect: (Int) -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, Tk.SandFaint, RoundedCornerShape(12.dp))
    ) {
        options.forEachIndexed { i, (title, desc) ->
            val on = i == selected
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x1FEADFC8)))
            Row(
                Modifier.fillMaxWidth().background(if (on) Color(0x26F2A93B) else Color.Transparent)
                    .clickable { onSelect(i) }.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = hud(16.sp, FontWeight.SemiBold, if (on) Tk.Amber else Tk.Sand, shadow = false))
                    Spacer(Modifier.height(3.dp))
                    Text(desc, style = hud(13.sp, FontWeight.Normal, Tk.SandDim, shadow = false))
                }
                Spacer(Modifier.width(12.dp))
                Box(
                    Modifier.size(20.dp).clip(CircleShape)
                        .border(if (on) 6.dp else 1.5.dp, if (on) Tk.Amber else Tk.SandFaint, CircleShape)
                )
            }
        }
    }
}

@Composable
fun SettingsContent(v: SettingsValues, a: SettingsActions) {
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp)
            .navigationBarsPadding()
    ) {
        SheetTitle("Réglages")
        Section("Vidéo") {
            Choice(listOf("4K", "1080p"), if (v.videoUhd) 0 else 1) { a.videoUhd(it == 0) }
            Spacer(Modifier.height(10.dp))
            Choice(listOf("30 images/s", "60 images/s"), if (v.video60) 1 else 0) { a.video60(it == 1) }
            Spacer(Modifier.height(10.dp))
            Text(
                (if (v.videoInfo.isNotBlank()) "En ce moment : ${v.videoInfo}. " else "") +
                    "Débit élevé pour une image nette. Le 60 images/s rend les mouvements plus fluides, " +
                    "le 4K donne plus de détails.",
                style = hud(13.sp, FontWeight.Normal, Tk.SandDim, shadow = false),
            )
        }
        Section("Suivi") {
            Text(
                "Le bouton Suivre garde quelqu'un dans l'image. Choisis ce qui le guide :",
                style = hud(14.sp, FontWeight.Normal, Tk.SandDim, shadow = false),
            )
            Spacer(Modifier.height(12.dp))
            val modes = listOf(FollowMode.CAMERA_WATCH, FollowMode.CAMERA, FollowMode.GPS)
            ChoiceList(
                listOf(
                    "Caméra + montre" to "La caméra te suit, la montre te reconnaît parmi plusieurs personnes, " +
                        "et son GPS reprend la main quand tu sors de l'image.",
                    "Caméra seule" to "Suit la personne vue à l'image, ou celle que tu touches. Sans la montre.",
                    "GPS seul" to "En extérieur, à plus de 10 m : la nacelle vise la position de ta montre.",
                ),
                selected = modes.indexOf(v.followMode).coerceAtLeast(0),
                onSelect = { a.followMode(modes[it]) },
            )
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Réactivité", style = hud(16.sp, FontWeight.Normal, shadow = false), modifier = Modifier.weight(1f))
                Text("×%.1f".format(v.followGain).replace('.', ','), style = hud(16.sp, FontWeight.SemiBold, Tk.Amber, shadow = false))
            }
            Slider(
                value = v.followGain,
                onValueChange = a::followGain,
                valueRange = 0.5f..1.5f,
                colors = SliderDefaults.colors(
                    thumbColor = Tk.Amber,
                    activeTrackColor = Tk.Amber,
                    inactiveTrackColor = Tk.SandFaint,
                ),
            )
            Row {
                Text("Douce", style = hud(12.sp, FontWeight.Normal, Tk.SandDim, shadow = false), modifier = Modifier.weight(1f))
                Text("Vive", style = hud(12.sp, FontWeight.Normal, Tk.SandDim, shadow = false))
            }
            Spacer(Modifier.height(6.dp))
            ToggleRow(
                "Zoom automatique", v.followAutoZoom, a::followAutoZoom,
                detail = "Caméras arrière : passe au téléobjectif 3x ou 5x quand tu es loin, " +
                    "revient au 1x quand tu te rapproches ou sors de l'image.",
            )
            ActionRow("Recaler le GPS sur moi", onClick = a::followRecalibrate)
            Text(
                "Suivi activé, mets-toi au centre de l'image à plus de 10 m, puis touche ici.",
                style = hud(13.sp, FontWeight.Normal, Tk.SandDim, shadow = false),
            )
            ActionRow("Réapprendre le sens du suivi", onClick = a::followRelearn)
        }
        Section("Joystick") {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Vitesse", style = hud(16.sp, FontWeight.Normal, shadow = false), modifier = Modifier.weight(1f))
                Text("${(v.speed * 100).roundToInt()} %", style = hud(16.sp, FontWeight.SemiBold, Tk.Amber, shadow = false))
            }
            Slider(
                value = v.speed,
                onValueChange = a::speed,
                valueRange = 0.1f..0.6f,
                colors = SliderDefaults.colors(
                    thumbColor = Tk.Amber,
                    activeTrackColor = Tk.Amber,
                    inactiveTrackColor = Tk.SandFaint,
                ),
            )
            Row {
                Text("Douce", style = hud(12.sp, FontWeight.Normal, Tk.SandDim, shadow = false), modifier = Modifier.weight(1f))
                Text("Rapide", style = hud(12.sp, FontWeight.Normal, Tk.SandDim, shadow = false))
            }
            Spacer(Modifier.height(6.dp))
            ToggleRow("Inverser gauche et droite", v.invertX, a::invertX)
            ToggleRow("Inverser haut et bas", v.invertY, a::invertY)
        }
        Section("Gestes de la montre") {
            Text(
                "Le bouton Gestes de la montre fait suivre à la nacelle les mouvements de ton poignet.",
                style = hud(14.sp, FontWeight.Normal, Tk.SandDim, shadow = false),
            )
            Spacer(Modifier.height(12.dp))
            Text("Activation", style = hud(16.sp, FontWeight.Normal, shadow = false))
            Spacer(Modifier.height(8.dp))
            Choice(
                listOf("Un appui : marche / arrêt", "Maintenir le bouton"),
                selected = if (v.gestureHold) 1 else 0,
                onSelect = { a.gestureHold(it == 1) },
            )
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Sensibilité", style = hud(16.sp, FontWeight.Normal, shadow = false), modifier = Modifier.weight(1f))
                Text("×%.1f".format(v.gestureSens).replace('.', ','), style = hud(16.sp, FontWeight.SemiBold, Tk.Amber, shadow = false))
            }
            Slider(
                value = v.gestureSens,
                onValueChange = a::gestureSens,
                valueRange = 0.5f..2f,
                colors = SliderDefaults.colors(
                    thumbColor = Tk.Amber,
                    activeTrackColor = Tk.Amber,
                    inactiveTrackColor = Tk.SandFaint,
                ),
            )
            Row {
                Text("Grands gestes", style = hud(12.sp, FontWeight.Normal, Tk.SandDim, shadow = false), modifier = Modifier.weight(1f))
                Text("Petits gestes", style = hud(12.sp, FontWeight.Normal, Tk.SandDim, shadow = false))
            }
            Spacer(Modifier.height(6.dp))
            ToggleRow("Inverser gauche et droite", v.gestureInvX, a::gestureInvX)
            ToggleRow("Inverser haut et bas", v.gestureInvY, a::gestureInvY)
        }
        Section("Caméra frontale") {
            ToggleRow(
                "Correction automatique", v.autoCorrectFront, a::autoCorrectFront,
                detail = "Avec la caméra frontale (image en miroir), la gauche et la droite sont inversées, et le haut " +
                    "et le bas suivent le sens des caméras arrière : le gyroscope du téléphone le vérifie pendant que tu pilotes.",
            )
        }
        Section("Recentrage") {
            ActionRow("Définir la position actuelle comme centre", onClick = a::setHome)
            ActionRow("Réapprendre le sens de rotation", onClick = a::relearn)
        }
        Section("Nacelle") {
            ActionRow(if (v.connected) "Déconnecter la nacelle" else "Connecter la nacelle", onClick = a::toggleConnection)
            ActionRow("Choisir une autre nacelle", onClick = a::choose)
            ActionRow("Voir le journal", onClick = a::journal)
        }
    }
}

@Composable
fun JournalContent(lines: List<String>) {
    Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
        SheetTitle("Journal", "Si quelque chose ne marche pas, envoie une capture de cet écran.")
        LazyColumn(Modifier.heightIn(max = 420.dp), reverseLayout = true) {
            items(lines.asReversed()) {
                Text(
                    it,
                    style = hud(13.sp, FontWeight.Normal, Color(0xD9EADFC8), shadow = false),
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
fun PickerContent(devices: List<GimbalBle.Seen>, onPick: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
        SheetTitle(
            "Choisir la nacelle",
            "Appareils Bluetooth détectés autour du téléphone. La liste se met à jour pendant la recherche.",
        )
        if (devices.isEmpty()) {
            Text(
                "Recherche en cours. Allume la nacelle et ferme Hohem Joy.",
                style = hud(15.sp, FontWeight.Normal, Tk.SandDim, shadow = false),
            )
        }
        LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(devices, key = { it.address }) { d ->
                Column(Modifier.fillMaxWidth().clickable { onPick(d.address) }.padding(vertical = 10.dp)) {
                    Text(
                        d.name ?: "Appareil sans nom",
                        style = hud(17.sp, FontWeight.SemiBold, if (d.likely) Tk.Amber else Tk.Sand, shadow = false),
                    )
                    Text(
                        "${d.address}, signal ${d.rssi.toString().replace("-", "−")} dBm",
                        style = hud(13.sp, FontWeight.Normal, Tk.SandDim, shadow = false),
                    )
                    if (d.likely) {
                        Text("Probablement ta nacelle", style = hud(13.sp, FontWeight.Medium, Tk.Amber, shadow = false))
                    }
                }
            }
        }
    }
}
