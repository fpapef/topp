package com.fpapef.topp.wear

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.CurvedLayout
import androidx.wear.compose.foundation.CurvedTextStyle
import androidx.wear.compose.foundation.basicCurvedText
import androidx.wear.compose.material.Text
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object Wk {
    val Sand = Color(0xFFEADFC8)
    val SandDim = Color(0x99EADFC8)
    val SandFaint = Color(0x4DEADFC8)
    val Tally = Color(0xFFFF3B30)
    val Kinkeliba = Color(0xFF9CCB72)
    val Amber = Color(0xFFF2A93B)
    val Button = Color(0xFF1A1E24)
}

val Barlow = FontFamily(
    Font(R.font.barlow_regular, FontWeight.Normal),
    Font(R.font.barlow_medium, FontWeight.Medium),
    Font(R.font.barlow_semibold, FontWeight.SemiBold),
)

data class PhoneState(
    val gimbal: Boolean = false,
    val text: String = "",
    val mode: String = "?",
    val recording: Boolean = false,
    val recentering: Boolean = false,
    val pan: Int? = null,
    val tilt: Int? = null,
    /** false = un appui active/coupe les gestes ; true = actifs seulement tant qu'on maintient le bouton. */
    val gestureHold: Boolean = false,
    val gestureSens: Float = 1f,
    val gestureInvX: Boolean = false,
    val gestureInvY: Boolean = false,
    /** Suivi actif sur le téléphone, avec son état en clair et un code (ok, id, cal, search, lost). */
    val following: Boolean = false,
    val followText: String = "",
    val followKind: String = "",
    val needMotion: Boolean = false,
    val needGps: Boolean = false,
)

fun followTint(kind: String): Color = when (kind) {
    "ok" -> Wk.Kinkeliba
    else -> Wk.Amber
}

/** Écran rond : la lunette graduée tourne avec la nacelle, le cadran central est le joystick. */
@Composable
fun WatchScreen(
    state: PhoneState,
    phoneOk: Boolean,
    recSeconds: Long,
    gestureOn: Boolean,
    gestureVec: Offset,
    onJoy: (Float, Float, Boolean) -> Unit,
    onCommand: (String) -> Unit,
    onGesture: (Boolean) -> Unit,
) {
    val ready = phoneOk && state.gimbal
    val pan by animateFloatAsState(state.pan?.toFloat() ?: 0f, tween(250), label = "pan")
    val tilt by animateFloatAsState(state.tilt?.toFloat() ?: 0f, tween(250), label = "tilt")

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        val radius = minOf(maxWidth, maxHeight) / 2

        Canvas(Modifier.fillMaxSize()) {
            drawBezel(
                pan, state.pan != null && ready, ready, phoneOk && state.recording, gestureOn,
                follow = if (phoneOk && state.following) followTint(state.followKind) else null,
            )
        }

        val following = phoneOk && state.following
        val (label, color) = when {
            !phoneOk -> "Ouvre Topp sur le téléphone" to Wk.SandDim
            following && state.followKind == "id" -> state.followText.ifBlank { "Fais coucou" } to Wk.Amber
            state.recording -> "● %02d:%02d".format(recSeconds / 60, recSeconds % 60) to Wk.Tally
            gestureOn -> "Gestes actifs" to Wk.Amber
            following -> state.followText.ifBlank { "Suivi" } to followTint(state.followKind)
            state.recentering -> "Recentrage" to Wk.Amber
            !state.gimbal -> state.text.ifBlank { "Nacelle déconnectée" } to Wk.Amber
            else -> "Nacelle prête" to Wk.Sand
        }
        CurvedLayout(Modifier.fillMaxSize().padding(19.dp), anchor = 270f) {
            basicCurvedText(
                label,
                style = CurvedTextStyle(
                    color = color,
                    fontSize = 14.sp,
                    fontFamily = Barlow,
                    fontWeight = FontWeight.Medium,
                ),
            )
        }

        WatchDial(
            enabled = ready,
            tilt = tilt,
            gestureVec = if (gestureOn) gestureVec else null,
            modifier = Modifier.offset(y = (-8).dp).size(radius * 0.84f),
            onJoy = onJoy,
        )

        val ring = radius - 38.dp
        fun at(deg: Float) = Modifier.offset(onCircle(ring, deg).first, onCircle(ring, deg).second)

        GestureButton(
            on = gestureOn,
            hold = state.gestureHold,
            enabled = ready,
            modifier = at(270f),
            onGesture = onGesture,
        )
        RoundButton(
            description = "Changer de mode",
            enabled = ready,
            size = 38.dp,
            modifier = at(225f),
            onClick = { onCommand("mode") },
        ) { tint ->
            Text(
                if (state.mode == "?") "–" else state.mode,
                style = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = tint),
            )
        }
        WatchShutter(
            recording = state.recording,
            enabled = phoneOk,
            modifier = Modifier.offset(0.dp, ring + 2.dp),
            onClick = { onCommand("rec") },
        )
        RoundButton(
            description = "Recentrer la nacelle",
            enabled = ready,
            active = state.recentering,
            size = 38.dp,
            modifier = at(135f),
            onClick = { onCommand("center") },
        ) { tint -> Canvas(Modifier.size(19.dp)) { glyphRecenter(tint) } }
        FollowButton(
            on = following,
            enabled = ready || following,
            modifier = at(90f),
            onClick = { onCommand(if (following) "t0" else "t1") },
        )
    }
}

/** Bouton Gestes : un appui = marche/arrêt, ou actif tant qu'on le maintient (selon le réglage). */
@Composable
private fun GestureButton(
    on: Boolean,
    hold: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onGesture: (Boolean) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.88f else 1f, tween(90), label = "s")
    val tint = when {
        !enabled -> Wk.SandFaint
        on -> Color.Black
        else -> Wk.Sand
    }
    Box(
        modifier.size(38.dp).scale(scale)
            .semantics { contentDescription = if (on) "Couper les gestes" else "Piloter avec les gestes" }
            .clip(CircleShape)
            .background(if (on) Wk.Amber else Wk.Button)
            .border(1.dp, if (on) Wk.Amber else Wk.SandFaint, CircleShape)
            .pointerInput(enabled, hold, on) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown().consume()
                    pressed = true
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (hold) onGesture(true)
                    do {
                        val ev = awaitPointerEvent()
                        ev.changes.forEach { it.consume() }
                    } while (ev.changes.any { it.pressed })
                    pressed = false
                    if (hold) onGesture(false) else onGesture(!on)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(21.dp)) { glyphMotion(tint) }
    }
}

/** Bouton Suivre : vert plein quand le suivi est actif. */
@Composable
private fun FollowButton(on: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val src = remember { MutableInteractionSource() }
    val isPressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.88f else 1f, tween(90), label = "s")
    val tint = when {
        !enabled -> Wk.SandFaint
        on -> Color.Black
        else -> Wk.Sand
    }
    Box(
        modifier.size(38.dp).scale(scale)
            .semantics { contentDescription = if (on) "Couper le suivi" else "Suivre" }
            .clip(CircleShape)
            .background(if (on) Wk.Kinkeliba else Wk.Button)
            .border(1.dp, if (on) Wk.Kinkeliba else Wk.SandFaint, CircleShape)
            .clickable(enabled = enabled, interactionSource = src, indication = null) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(20.dp)) { glyphFollow(tint) }
    }
}

/** Position sur un cercle, angle compté depuis midi dans le sens des aiguilles. */
private fun onCircle(r: Dp, deg: Float): Pair<Dp, Dp> {
    val a = Math.toRadians(deg.toDouble())
    return r * sin(a).toFloat() to r * (-cos(a)).toFloat()
}

private fun DrawScope.drawBezel(
    pan: Float, showHome: Boolean, ready: Boolean, recording: Boolean, gestures: Boolean, follow: Color? = null,
) {
    val r = size.minDimension / 2f
    val c = Offset(size.width / 2f, size.height / 2f)
    val outer = r - 4.dp.toPx()
    val a0 = if (ready) 1f else 0.45f
    for (deg in 0 until 360 step 10) {
        if (deg == 0) continue
        val major = deg % 30 == 0
        val a = Math.toRadians((deg - pan).toDouble() - 90.0)
        val dir = Offset(cos(a).toFloat(), sin(a).toFloat())
        val len = (if (major) 8.dp else 4.dp).toPx()
        drawLine(
            Wk.Sand.copy(alpha = (if (major) 0.85f else 0.4f) * a0),
            c + dir * outer, c + dir * (outer - len),
            (if (major) 1.5.dp else 1.dp).toPx(), StrokeCap.Round,
        )
    }
    // Repère de repos (ambre) : où se trouve le centre de la nacelle
    if (showHome) {
        val a = Math.toRadians((-pan).toDouble() - 90.0)
        drawCircle(Wk.Amber, 3.dp.toPx(), c + Offset(cos(a).toFloat(), sin(a).toFloat()) * (outer - 4.dp.toPx()))
    }
    // Index fixe à midi
    val tri = Path().apply {
        moveTo(c.x, c.y - outer + 9.dp.toPx())
        lineTo(c.x - 4.dp.toPx(), c.y - outer + 1.dp.toPx())
        lineTo(c.x + 4.dp.toPx(), c.y - outer + 1.dp.toPx())
        close()
    }
    drawPath(tri, Wk.Sand.copy(alpha = a0))
    if (recording) {
        drawCircle(Wk.Tally, r - 1.5.dp.toPx(), c, style = Stroke(3.dp.toPx()))
    } else if (gestures) {
        drawCircle(Wk.Amber, r - 1.5.dp.toPx(), c, style = Stroke(2.dp.toPx()))
    } else if (follow != null) {
        drawCircle(follow, r - 1.5.dp.toPx(), c, style = Stroke(2.dp.toPx()))
    }
}

@Composable
private fun WatchDial(
    enabled: Boolean,
    tilt: Float,
    gestureVec: Offset?,
    modifier: Modifier,
    onJoy: (Float, Float, Boolean) -> Unit,
) {
    var knob by remember { mutableStateOf(Offset.Zero) }
    var pressed by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    Canvas(
        modifier
            .semantics { contentDescription = "Joystick de la nacelle" }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun update(p: Offset) {
                        val r = size.width / 2f
                        var v = (p - Offset(r, size.height / 2f)) / (r * 0.8f)
                        val d = v.getDistance()
                        if (d > 1f) v /= d
                        knob = v
                        onJoy(v.x, v.y, true)
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
                    onJoy(0f, 0f, false)
                }
            }
    ) {
        val r = size.minDimension / 2f
        val c = Offset(size.width / 2f, size.height / 2f)
        val a0 = if (enabled) 1f else 0.4f
        drawCircle(Color(0xFF101317), r, c)
        drawCircle(Wk.Sand.copy(alpha = 0.28f * a0), r - 0.5.dp.toPx(), c, style = Stroke(1.dp.toPx()))
        // Horizon : suit le tilt de la nacelle
        val y = (tilt / 45f).coerceIn(-1f, 1f) * r * 0.7f
        val hw = sqrt((r * r - y * y).coerceAtLeast(0f)) * 0.78f
        val gap = 13.dp.toPx()
        val hc = Wk.Sand.copy(alpha = 0.7f * a0)
        drawLine(hc, Offset(c.x - hw, c.y + y), Offset(c.x - gap, c.y + y), 1.5.dp.toPx(), StrokeCap.Round)
        drawLine(hc, Offset(c.x + gap, c.y + y), Offset(c.x + hw, c.y + y), 1.5.dp.toPx(), StrokeCap.Round)
        val shown = if (!pressed && gestureVec != null) gestureVec else knob
        val kp = c + shown * (r * 0.78f)
        if (pressed || gestureVec != null) {
            drawLine(Wk.Amber.copy(alpha = 0.7f), c, kp, 2.dp.toPx(), StrokeCap.Round)
            drawCircle(Wk.Amber, 15.dp.toPx(), kp)
            drawCircle(Color(0x33000000), 5.dp.toPx(), kp)
        } else {
            drawCircle(Wk.Sand.copy(alpha = a0), 13.dp.toPx(), kp, style = Stroke(1.7.dp.toPx()))
            drawCircle(Wk.Sand.copy(alpha = a0), 2.2.dp.toPx(), kp)
        }
    }
}

@Composable
private fun RoundButton(
    description: String,
    enabled: Boolean,
    size: Dp,
    modifier: Modifier,
    active: Boolean = false,
    onClick: () -> Unit,
    content: @Composable (Color) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val src = remember { MutableInteractionSource() }
    val isPressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.88f else 1f, tween(90), label = "s")
    val tint = when {
        !enabled -> Wk.SandFaint
        active -> Wk.Amber
        else -> Wk.Sand
    }
    Box(
        modifier.size(size).scale(scale)
            .semantics { contentDescription = description }
            .clip(CircleShape)
            .background(Wk.Button)
            .border(1.dp, if (active) Wk.Amber else Wk.SandFaint, CircleShape)
            .clickable(enabled = enabled, interactionSource = src, indication = null) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) { content(tint) }
}

@Composable
private fun WatchShutter(recording: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val src = remember { MutableInteractionSource() }
    val isPressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.9f else 1f, tween(90), label = "s")
    val inner by animateDpAsState(if (recording) 17.dp else 33.dp, tween(200), label = "i")
    val corner by animateDpAsState(if (recording) 4.dp else 17.dp, tween(200), label = "c")
    Box(
        modifier.size(46.dp).scale(scale)
            .semantics { contentDescription = if (recording) "Arrêter l'enregistrement" else "Filmer" }
            .clip(CircleShape)
            .clickable(enabled = enabled, interactionSource = src, indication = null) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Wk.Sand.copy(alpha = if (enabled) 1f else 0.35f), size.minDimension / 2f - 1.5.dp.toPx(), style = Stroke(2.5.dp.toPx()))
        }
        Box(
            Modifier.size(inner).clip(RoundedCornerShape(corner))
                .background(if (enabled) Wk.Tally else Wk.Tally.copy(alpha = 0.35f))
        )
    }
}

/** Silhouette dans un cadre de visée. */
private fun DrawScope.glyphFollow(c: Color) {
    val u = size.minDimension / 24f
    val w = 1.8f * u
    fun corner(x: Float, y: Float, dx: Float, dy: Float) {
        drawLine(c, Offset(x * u, y * u), Offset((x + dx * 4.5f) * u, y * u), w, StrokeCap.Round)
        drawLine(c, Offset(x * u, y * u), Offset(x * u, (y + dy * 4.5f) * u), w, StrokeCap.Round)
    }
    corner(2.5f, 2.5f, 1f, 1f)
    corner(21.5f, 2.5f, -1f, 1f)
    corner(2.5f, 21.5f, 1f, -1f)
    corner(21.5f, 21.5f, -1f, -1f)
    drawCircle(c, 2.6f * u, Offset(12f * u, 9f * u), style = Stroke(w))
    drawArc(
        c, 180f, 180f, false, Offset(6.8f * u, 13.6f * u), androidx.compose.ui.geometry.Size(10.4f * u, 9f * u),
        style = Stroke(w, cap = StrokeCap.Round),
    )
}

/** Montre au centre, ondes de mouvement de chaque côté. */
private fun DrawScope.glyphMotion(c: Color) {
    val u = size.minDimension / 24f
    val w = 1.8f * u
    drawRoundRect(
        c, Offset(8.5f * u, 7f * u), androidx.compose.ui.geometry.Size(7f * u, 10f * u),
        androidx.compose.ui.geometry.CornerRadius(2.2f * u), style = Stroke(w),
    )
    drawArc(c, 135f, 90f, false, Offset(1.5f * u, 6f * u), androidx.compose.ui.geometry.Size(12f * u, 12f * u), style = Stroke(w, cap = StrokeCap.Round))
    drawArc(c, -45f, 90f, false, Offset(10.5f * u, 6f * u), androidx.compose.ui.geometry.Size(12f * u, 12f * u), style = Stroke(w, cap = StrokeCap.Round))
}

private fun DrawScope.glyphRecenter(c: Color) {
    val u = size.minDimension / 24f
    val w = 1.8f * u
    val m = Offset(12f * u, 12f * u)
    drawCircle(c, 6.5f * u, m, style = Stroke(w))
    drawCircle(c, 1.7f * u, m)
    drawLine(c, Offset(12f * u, 1.5f * u), Offset(12f * u, 4.5f * u), w, StrokeCap.Round)
    drawLine(c, Offset(12f * u, 19.5f * u), Offset(12f * u, 22.5f * u), w, StrokeCap.Round)
    drawLine(c, Offset(1.5f * u, 12f * u), Offset(4.5f * u, 12f * u), w, StrokeCap.Round)
    drawLine(c, Offset(19.5f * u, 12f * u), Offset(22.5f * u, 12f * u), w, StrokeCap.Round)
}
