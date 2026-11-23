package com.fpapef.topp

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// ---------- Jetons de design ----------

object Tk {
    /** Lignes et textes du viseur : sable chaud, comme l'affichage d'un moniteur de tournage. */
    val Sand = Color(0xFFEADFC8)
    val SandDim = Color(0x99EADFC8)
    val SandFaint = Color(0x4DEADFC8)
    /** Rouge réservé à l'enregistrement. */
    val Tally = Color(0xFFFF3B30)
    /** Nacelle prête. */
    val Kinkeliba = Color(0xFF9CCB72)
    /** Mouvement, recherche, choix. */
    val Amber = Color(0xFFF2A93B)
    val Panel = Color(0xFF15181D)
    val Scrim = Color(0x66000000)
}

val Barlow = FontFamily(
    Font(R.font.barlow_regular, FontWeight.Normal),
    Font(R.font.barlow_medium, FontWeight.Medium),
    Font(R.font.barlow_semibold, FontWeight.SemiBold),
)

private val hudShadow = Shadow(Color(0xB3000000), Offset(0f, 1.5f), 8f)

fun hud(
    size: TextUnit,
    weight: FontWeight = FontWeight.Medium,
    color: Color = Tk.Sand,
    shadow: Boolean = true,
) = TextStyle(
    fontFamily = Barlow,
    fontSize = size,
    fontWeight = weight,
    color = color,
    fontFeatureSettings = "tnum",
    shadow = if (shadow) hudShadow else null,
)

// ---------- État affiché ----------

data class UiState(
    val gimbal: GimbalBle.Status = GimbalBle.Status.OFF,
    val modeLabel: String = "?",
    val watchConnected: Boolean = false,
    val recording: Boolean = false,
    val recSeconds: Long = 0,
    val relPan: Float? = null,
    val relTilt: Float? = null,
    val recentering: Boolean = false,
    val gesturesActive: Boolean = false,
)

interface UiActions {
    fun record()
    fun mode()
    fun recenter()
    fun flipCamera()
    fun settings()
    fun chooseGimbal()
    fun joystick(x: Float, y: Float)
}

fun statusLabel(s: GimbalBle.Status, recentering: Boolean) = when {
    recentering -> "Recentrage"
    s == GimbalBle.Status.READY -> "Nacelle prête"
    s == GimbalBle.Status.SCANNING -> "Recherche de la nacelle"
    s == GimbalBle.Status.CONNECTING -> "Connexion à la nacelle"
    else -> "Nacelle déconnectée"
}

fun formatDegrees(v: Float): String {
    val r = v.roundToInt()
    return when {
        r > 0 -> "+$r°"
        r < 0 -> "−${-r}°"
        else -> "0°"
    }
}

fun formatDuration(sec: Long) = "%02d:%02d".format(sec / 60, sec % 60)

// ---------- Écran principal ----------

@Composable
fun ToppHud(state: UiState, actions: UiActions, preview: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val portrait = maxHeight > maxWidth
        preview()

        // Voiles en haut et en bas pour lire le texte sur n'importe quelle image
        Box(
            Modifier.fillMaxWidth().height(120.dp).align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(Color(0x80000000), Color.Transparent)))
        )
        Box(
            Modifier.fillMaxWidth().height(150.dp).align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x80000000))))
        )

        ViewfinderMarks(Modifier.fillMaxSize().safeDrawingPadding().padding(10.dp), state.recording)

        StatusBlock(
            state, actions,
            Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(start = 28.dp, top = 22.dp),
        )

        Row(
            Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(end = 22.dp, top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GlyphButton(onClick = actions::flipCamera, description = "Changer de caméra", size = 44.dp) { glyphFlip(it) }
            GlyphButton(onClick = actions::settings, description = "Réglages", size = 44.dp) { glyphSliders(it) }
        }

        if (state.recording) {
            RecTimer(state.recSeconds, Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 22.dp))
        }

        Column(
            Modifier.align(Alignment.BottomStart).safeDrawingPadding().padding(start = 28.dp, bottom = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AttitudeDial(
                relPan = state.relPan,
                relTilt = state.relTilt,
                enabled = state.gimbal == GimbalBle.Status.READY,
                onMove = actions::joystick,
                modifier = Modifier.size(172.dp),
            )
            Spacer(Modifier.height(8.dp))
            val pan = state.relPan
            val tilt = state.relTilt
            Text(
                if (pan != null && tilt != null) "Pan ${formatDegrees(pan)}    Tilt ${formatDegrees(tilt)}"
                else "Angles en attente",
                style = hud(13.sp, FontWeight.Medium, Tk.SandDim),
            )
        }

        Column(
            Modifier.align(if (portrait) Alignment.BottomEnd else Alignment.CenterEnd)
                .safeDrawingPadding().padding(end = 26.dp, bottom = if (portrait) 22.dp else 0.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            GlyphButton(
                onClick = actions::recenter,
                description = "Recentrer la nacelle",
                enabled = state.gimbal == GimbalBle.Status.READY,
                active = state.recentering,
            ) { glyphRecenter(it) }
            ShutterButton(state.recording, actions::record)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                RailButton(
                    onClick = actions::mode,
                    description = "Changer de mode",
                    enabled = state.gimbal == GimbalBle.Status.READY,
                ) { tint ->
                    Text(
                        if (state.modeLabel == "?") "–" else state.modeLabel,
                        style = hud(20.sp, FontWeight.SemiBold, tint, shadow = false),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text("Mode", style = hud(12.sp, FontWeight.Medium, Tk.SandDim))
            }
        }
    }
}

@Composable
private fun StatusBlock(state: UiState, actions: UiActions, modifier: Modifier) {
    val ready = state.gimbal == GimbalBle.Status.READY
    val searching = state.gimbal == GimbalBle.Status.SCANNING || state.gimbal == GimbalBle.Status.CONNECTING
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Topp", style = hud(24.sp, FontWeight.SemiBold).copy(letterSpacing = (-0.3).sp))
            Spacer(Modifier.width(14.dp))
            StatusDot(
                color = when {
                    ready -> Tk.Kinkeliba
                    searching -> Tk.Amber
                    else -> Tk.SandFaint
                },
                pulsing = searching || state.recentering,
            )
            Spacer(Modifier.width(8.dp))
            Text(statusLabel(state.gimbal, state.recentering), style = hud(16.sp, FontWeight.Medium))
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val gest = state.watchConnected && state.gesturesActive
            Canvas(Modifier.size(14.dp)) {
                glyphWatch(
                    when {
                        gest -> Tk.Amber
                        state.watchConnected -> Tk.Sand
                        else -> Tk.SandFaint
                    }
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(
                when {
                    gest -> "La montre pilote avec tes gestes"
                    state.watchConnected -> "Montre connectée"
                    else -> "Montre non connectée"
                },
                style = hud(
                    13.sp, if (gest) FontWeight.Medium else FontWeight.Normal,
                    when {
                        gest -> Tk.Amber
                        state.watchConnected -> Tk.SandDim
                        else -> Tk.SandFaint
                    },
                ),
            )
        }
        if (!ready && !searching) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Choisir la nacelle",
                style = hud(14.sp, FontWeight.SemiBold, Tk.Amber).copy(textDecoration = TextDecoration.Underline),
                modifier = Modifier.clickable(onClick = actions::chooseGimbal).padding(vertical = 4.dp),
            )
        } else if (searching) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Choisir dans la liste",
                style = hud(13.sp, FontWeight.Medium, Tk.Amber).copy(textDecoration = TextDecoration.Underline),
                modifier = Modifier.clickable(onClick = actions::chooseGimbal).padding(vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun StatusDot(color: Color, pulsing: Boolean) {
    val alpha = if (pulsing) {
        val t = rememberInfiniteTransition(label = "dot")
        t.animateFloat(1f, 0.25f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a").value
    } else 1f
    Box(Modifier.size(9.dp).clip(CircleShape).background(color.copy(alpha = alpha * color.alpha)))
}

@Composable
private fun RecTimer(seconds: Long, modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "rec")
    val a by t.animateFloat(1f, 0.2f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "a")
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(Tk.Tally.copy(alpha = a)))
        Spacer(Modifier.width(8.dp))
        Text(formatDuration(seconds), style = hud(20.sp, FontWeight.SemiBold))
    }
}

/** Coins du viseur et croix centrale. Les coins passent au rouge pendant l'enregistrement. */
@Composable
private fun ViewfinderMarks(modifier: Modifier, recording: Boolean) {
    Canvas(modifier) {
        val arm = 22.dp.toPx()
        val w = 1.5.dp.toPx()
        val c = if (recording) Tk.Tally else Tk.SandDim
        fun corner(x: Float, y: Float, dx: Float, dy: Float) {
            drawLine(c, Offset(x, y), Offset(x + dx * arm, y), w, StrokeCap.Round)
            drawLine(c, Offset(x, y), Offset(x, y + dy * arm), w, StrokeCap.Round)
        }
        corner(0f, 0f, 1f, 1f)
        corner(size.width, 0f, -1f, 1f)
        corner(0f, size.height, 1f, -1f)
        corner(size.width, size.height, -1f, -1f)
        val cx = size.width / 2
        val cy = size.height / 2
        val k = 7.dp.toPx()
        drawLine(Tk.SandFaint, Offset(cx - k, cy), Offset(cx + k, cy), 1.dp.toPx())
        drawLine(Tk.SandFaint, Offset(cx, cy - k), Offset(cx, cy + k), 1.dp.toPx())
    }
}

// ---------- Cadran d'attitude (joystick) ----------

/**
 * La lunette graduée tourne avec le pan réel de la nacelle (le repère ambre = position de repos),
 * la ligne d'horizon suit le tilt, et le pouce pilote la nacelle.
 */
@Composable
fun AttitudeDial(
    relPan: Float?,
    relTilt: Float?,
    enabled: Boolean,
    onMove: (Float, Float) -> Unit,
    modifier: Modifier,
) {
    var knob by remember { mutableStateOf(Offset.Zero) }
    var pressed by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val pan by animateFloatAsState(relPan ?: 0f, tween(220), label = "pan")
    val tilt by animateFloatAsState(relTilt ?: 0f, tween(220), label = "tilt")

    LaunchedEffect(pressed) {
        while (pressed) {
            onMove(knob.x, knob.y)
            delay(70)
        }
        onMove(0f, 0f)
    }

    Canvas(
        modifier
            .semantics { contentDescription = "Joystick de la nacelle" }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun update(p: Offset) {
                        val r = size.width / 2f
                        var v = (p - Offset(r, size.height / 2f)) / (r * 0.62f)
                        val d = v.getDistance()
                        if (d > 1f) v /= d
                        knob = v
                    }
                    down.consume()
                    update(down.position)
                    pressed = true
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull() ?: break
                        if (!ch.pressed) break
                        update(ch.position)
                        ch.consume()
                    }
                    pressed = false
                    knob = Offset.Zero
                }
            }
    ) {
        drawDial(pan, tilt, relPan != null, knob, pressed, enabled)
    }
}

fun DrawScope.drawDial(
    pan: Float,
    tilt: Float,
    hasAngles: Boolean,
    knob: Offset,
    pressed: Boolean,
    enabled: Boolean,
    tickColor: Color = Tk.Sand,
) {
    val r = size.minDimension / 2f
    val c = Offset(size.width / 2f, size.height / 2f)
    val alpha = if (enabled) 1f else 0.45f
    drawCircle(Color(0x59000000), r - 1f, c)

    // Lunette : un trait tous les 10°, un plus long tous les 30°, tournée de -pan
    val outer = r - 3.dp.toPx()
    for (deg in 0 until 360 step 10) {
        val major = deg % 30 == 0
        val a = Math.toRadians((deg - pan).toDouble() - 90.0)
        val len = (if (major) 10.dp else 5.dp).toPx()
        val p1 = c + Offset(cos(a).toFloat(), sin(a).toFloat()) * outer
        val p2 = c + Offset(cos(a).toFloat(), sin(a).toFloat()) * (outer - len)
        drawLine(
            tickColor.copy(alpha = (if (major) 0.9f else 0.45f) * alpha), p1, p2,
            (if (major) 1.6.dp else 1.dp).toPx(), StrokeCap.Round,
        )
    }
    // Repère de la position de repos
    if (hasAngles) {
        val a = Math.toRadians((-pan).toDouble() - 90.0)
        drawCircle(Tk.Amber.copy(alpha = alpha), 3.5.dp.toPx(), c + Offset(cos(a).toFloat(), sin(a).toFloat()) * (outer - 17.dp.toPx()))
    }
    // Index fixe : la direction actuelle de la caméra
    val tri = Path().apply {
        val y0 = c.y - r + 15.dp.toPx()
        moveTo(c.x, y0)
        lineTo(c.x - 5.dp.toPx(), y0 + 8.dp.toPx())
        lineTo(c.x + 5.dp.toPx(), y0 + 8.dp.toPx())
        close()
    }
    drawPath(tri, tickColor.copy(alpha = alpha))

    // Zone du pouce et horizon
    val inner = r * 0.62f
    drawCircle(tickColor.copy(alpha = 0.25f * alpha), inner, c, style = Stroke(1.dp.toPx()))
    val y = (tilt / 45f).coerceIn(-1f, 1f) * inner * 0.75f
    val hw = sqrt((inner * inner - y * y).coerceAtLeast(0f)) * 0.82f
    val gap = 14.dp.toPx()
    val hc = tickColor.copy(alpha = 0.75f * alpha)
    drawLine(hc, Offset(c.x - hw, c.y + y), Offset(c.x - gap, c.y + y), 1.5.dp.toPx(), StrokeCap.Round)
    drawLine(hc, Offset(c.x + gap, c.y + y), Offset(c.x + hw, c.y + y), 1.5.dp.toPx(), StrokeCap.Round)

    val kp = c + knob * (inner * 0.86f)
    if (pressed) {
        drawLine(Tk.Amber.copy(alpha = 0.7f), c, kp, 2.dp.toPx(), StrokeCap.Round)
        drawCircle(Tk.Amber, 17.dp.toPx(), kp)
        drawCircle(Color(0x33000000), 6.dp.toPx(), kp)
    } else {
        drawCircle(tickColor.copy(alpha = alpha), 15.dp.toPx(), kp, style = Stroke(1.8.dp.toPx()))
        drawCircle(tickColor.copy(alpha = alpha), 2.5.dp.toPx(), kp)
    }
}

// ---------- Boutons ----------

@Composable
fun ShutterButton(recording: Boolean, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val src = remember { MutableInteractionSource() }
    val isPressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.93f else 1f, tween(90), label = "s")
    val inner by animateDpAsState(if (recording) 28.dp else 58.dp, tween(220), label = "i")
    val corner by animateDpAsState(if (recording) 7.dp else 29.dp, tween(220), label = "c")
    Box(
        Modifier.size(78.dp).scale(scale)
            .semantics { contentDescription = if (recording) "Arrêter l'enregistrement" else "Filmer" }
            .clip(CircleShape)
            .clickable(interactionSource = src, indication = null) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Color(0x40000000), size.minDimension / 2f)
            drawCircle(Tk.Sand, size.minDimension / 2f - 2.dp.toPx(), style = Stroke(3.dp.toPx()))
        }
        Box(Modifier.size(inner).clip(RoundedCornerShape(corner)).background(Tk.Tally))
    }
}

@Composable
fun RailButton(
    onClick: () -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    enabled: Boolean = true,
    active: Boolean = false,
    content: @Composable (Color) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val src = remember { MutableInteractionSource() }
    val isPressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.9f else 1f, tween(90), label = "s")
    val tint = when {
        !enabled -> Tk.SandFaint
        active -> Tk.Amber
        else -> Tk.Sand
    }
    Box(
        modifier.size(size).scale(scale)
            .semantics { contentDescription = description }
            .clip(CircleShape)
            .background(Tk.Scrim)
            .border(1.dp, if (active) Tk.Amber else Tk.SandFaint, CircleShape)
            .clickable(enabled = enabled, interactionSource = src, indication = null) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(size * 0.46f), contentAlignment = Alignment.Center) {
            content(tint)
        }
    }
}

@Composable
fun GlyphButton(
    onClick: () -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    enabled: Boolean = true,
    active: Boolean = false,
    glyph: DrawScope.(Color) -> Unit,
) = RailButton(onClick, description, modifier, size, enabled, active) { tint ->
    Canvas(Modifier.fillMaxSize()) { glyph(tint) }
}

// ---------- Pictogrammes au trait (grille 24×24) ----------

private fun DrawScope.u(v: Float) = v / 24f * size.minDimension
private fun DrawScope.pt(x: Float, y: Float) = Offset(u(x), u(y))
private fun DrawScope.stroke() = Stroke(u(1.7f), cap = StrokeCap.Round)

fun DrawScope.glyphRecenter(c: Color) {
    drawCircle(c, u(6.5f), pt(12f, 12f), style = stroke())
    drawCircle(c, u(1.6f), pt(12f, 12f))
    val w = u(1.7f)
    drawLine(c, pt(12f, 1.5f), pt(12f, 4.5f), w, StrokeCap.Round)
    drawLine(c, pt(12f, 19.5f), pt(12f, 22.5f), w, StrokeCap.Round)
    drawLine(c, pt(1.5f, 12f), pt(4.5f, 12f), w, StrokeCap.Round)
    drawLine(c, pt(19.5f, 12f), pt(22.5f, 12f), w, StrokeCap.Round)
}

fun DrawScope.glyphFlip(c: Color) {
    val s = stroke()
    drawArc(c, 200f, 140f, false, pt(4f, 4f), Size(u(16f), u(16f)), style = s)
    drawArc(c, 20f, 140f, false, pt(4f, 4f), Size(u(16f), u(16f)), style = s)
    val w = u(1.7f)
    // pointes de flèches
    drawLine(c, pt(19.5f, 9.2f), pt(19.6f, 5.6f), w, StrokeCap.Round)
    drawLine(c, pt(19.5f, 9.2f), pt(16.2f, 8.4f), w, StrokeCap.Round)
    drawLine(c, pt(4.5f, 14.8f), pt(4.4f, 18.4f), w, StrokeCap.Round)
    drawLine(c, pt(4.5f, 14.8f), pt(7.8f, 15.6f), w, StrokeCap.Round)
}

fun DrawScope.glyphSliders(c: Color) {
    val w = u(1.7f)
    listOf(6f to 8f, 12f to 15f, 18f to 10f).forEach { (y, x) ->
        drawLine(c, pt(3f, y), pt(21f, y), w * 0.8f, StrokeCap.Round)
        drawCircle(Color.Black, u(2.6f), pt(x, y))
        drawCircle(c, u(2.6f), pt(x, y), style = stroke())
    }
}

fun DrawScope.glyphWatch(c: Color) {
    val s = stroke()
    drawRoundRect(c, pt(6f, 6.5f), Size(u(12f), u(11f)), androidx.compose.ui.geometry.CornerRadius(u(3f)), style = s)
    val w = u(1.7f)
    drawLine(c, pt(9f, 6.5f), pt(9.5f, 2f), w, StrokeCap.Round)
    drawLine(c, pt(15f, 6.5f), pt(14.5f, 2f), w, StrokeCap.Round)
    drawLine(c, pt(9f, 17.5f), pt(9.5f, 22f), w, StrokeCap.Round)
    drawLine(c, pt(15f, 17.5f), pt(14.5f, 22f), w, StrokeCap.Round)
}

/** Petit utilitaire pour les tests : vecteur pouce depuis un angle (en degrés) et une longueur. */
fun knobAt(deg: Float, len: Float) = Offset(
    cos(Math.toRadians(deg.toDouble())).toFloat() * len,
    sin(Math.toRadians(deg.toDouble())).toFloat() * len,
)
