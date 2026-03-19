package com.carbide.wallet.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carbide.wallet.R
import com.carbide.wallet.data.lnd.LndState
import com.carbide.wallet.ui.theme.Lightning
import com.carbide.wallet.ui.theme.LightningBright
import com.carbide.wallet.ui.theme.Negative
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

private val VgaFont = FontFamily(Font(R.font.vga))

private data class BootLine(
    val text: String,
    val color: Color = Color(0xFFCCCCCC),
)

@Composable
fun LoadingScreen(lndState: LndState, bootLog: String = "") {
    val lines = remember { mutableStateListOf<BootLine>() }
    val lastBootLog = remember { mutableStateListOf<String>() }

    val transition = rememberInfiniteTransition(label = "boot")
    val cursorAlpha by transition.animateFloat(
        initialValue = 1f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(500, easing = LinearEasing), RepeatMode.Reverse),
        label = "cursor",
    )
    val cubeAngle by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(10000, easing = LinearEasing), RepeatMode.Restart),
        label = "cube",
    )

    // Memory test
    val context = androidx.compose.ui.platform.LocalContext.current
    val memoryLine = remember { mutableStateListOf<String>() }
    LaunchedEffect(Unit) {
        if (memoryLine.isEmpty()) {
            val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            val totalKb = (mi.totalMem / 1024).toInt()
            val step = totalKb / 32
            for (kb in step..totalKb step step) {
                memoryLine.clear()
                memoryLine.add("Memory Test : ${kb}K")
                delay(25)
            }
            memoryLine.clear()
            memoryLine.add("Memory Test : ${totalKb}K OK")
        }
    }

    // LND log messages
    LaunchedEffect(bootLog) {
        if (bootLog.isNotBlank() && !lastBootLog.contains(bootLog)) {
            lastBootLog.add(bootLog)
            lines.add(BootLine(bootLog))
        }
    }

    // State transitions
    LaunchedEffect(lndState) {
        when (lndState) {
            is LndState.NotStarted -> {
                lines.clear()
            }
            is LndState.Starting -> {
                if (lines.isEmpty()) {
                    delay(800)
                    lines.add(BootLine("Detecting network ... mainnet"))
                    delay(150)
                    lines.add(BootLine("Detecting backend ... neutrino"))
                    delay(200)
                    lines.add(BootLine(""))
                    lines.add(BootLine("Initializing LND daemon..."))
                }
            }
            is LndState.WaitingToUnlock -> {
                delay(100)
                lines.add(BootLine("Decrypting wallet..."))
            }
            is LndState.NeedsSeed -> {
                lines.add(BootLine("  No wallet found — setup required", Lightning))
            }
            is LndState.WalletUnlocked -> {
                lines.add(BootLine("  Wallet decrypted                      [ OK ]"))
                delay(100)
                lines.add(BootLine(""))
                lines.add(BootLine("Loading wallet data..."))
            }
            is LndState.RpcReady -> {
                lines.add(BootLine("  Wallet loaded                         [ OK ]"))
                delay(100)
                lines.add(BootLine(""))
                lines.add(BootLine("Starting services..."))
            }
            is LndState.Running -> {
                lines.add(BootLine("  All services active                   [ OK ]"))
                delay(200)
                lines.add(BootLine(""))
                lines.add(BootLine("System ready.", Lightning))
            }
            is LndState.Error -> {
                lines.add(BootLine("  ERROR: ${lndState.message}", Negative))
            }
            is LndState.Stopped -> {
                lines.add(BootLine("  Node stopped.", Color(0xFF888888)))
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // Header: spinning cube + title
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 4.dp),
        ) {
            Canvas(modifier = Modifier.size(56.dp)) {
                drawAtom(cubeAngle * 360f)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    "CARBIDE",
                    fontFamily = VgaFont,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Lightning,
                )
                Text(
                    "Lightning Wallet v0.1.0",
                    fontFamily = VgaFont,
                    fontSize = 14.sp,
                    color = Color(0xFF888888),
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        // Memory test
        if (memoryLine.isNotEmpty()) {
            Text(
                memoryLine[0],
                fontFamily = VgaFont,
                fontSize = 16.sp,
                color = Color(0xFFCCCCCC),
                lineHeight = 22.sp,
            )
        }

        Spacer(Modifier.height(4.dp))

        // Boot log
        for (line in lines) {
            Text(
                text = line.text,
                fontFamily = VgaFont,
                fontSize = 16.sp,
                color = line.color,
                lineHeight = 22.sp,
            )
        }

        // Blinking cursor
        if (lndState !is LndState.Running && lndState !is LndState.NeedsSeed) {
            Text(
                "█",
                fontFamily = VgaFont,
                fontSize = 16.sp,
                color = Color(0xFFCCCCCC).copy(alpha = cursorAlpha),
            )
        }
    }
}

private fun DrawScope.drawAtom(angleDeg: Float) {
    val cx = size.width / 2
    val cy = size.height / 2
    val r = size.minDimension * 0.38f

    // Three orbital paths at different tilts
    val orbits = listOf(0f, 60f, 120f)

    for ((i, tilt) in orbits.withIndex()) {
        val tiltRad = Math.toRadians(tilt.toDouble())
        val cosT = cos(tiltRad).toFloat()

        // Draw elliptical orbit path
        val path = androidx.compose.ui.graphics.Path()
        for (deg in 0..360 step 3) {
            val rad = Math.toRadians(deg.toDouble())
            val x = cx + cos(rad).toFloat() * r
            val y = cy + sin(rad).toFloat() * r * 0.35f * cosT
            // Rotate the ellipse by the tilt
            val rx = cx + (x - cx) * cos(tiltRad).toFloat() - (y - cy) * sin(tiltRad).toFloat()
            val ry = cy + (x - cx) * sin(tiltRad).toFloat() + (y - cy) * cos(tiltRad).toFloat()
            if (deg == 0) path.moveTo(rx, ry) else path.lineTo(rx, ry)
        }
        path.close()
        drawPath(path, Lightning.copy(alpha = 0.2f), style = Stroke(width = 1f))

        // Electron on the orbit
        val electronAngle = Math.toRadians((angleDeg + i * 120f).toDouble())
        val ex = cx + cos(electronAngle).toFloat() * r
        val ey = cy + sin(electronAngle).toFloat() * r * 0.35f * cosT
        val erx = cx + (ex - cx) * cos(tiltRad).toFloat() - (ey - cy) * sin(tiltRad).toFloat()
        val ery = cy + (ex - cx) * sin(tiltRad).toFloat() + (ey - cy) * cos(tiltRad).toFloat()

        drawCircle(LightningBright, radius = 3.5f, center = Offset(erx, ery))
    }

    // Center nucleus
    drawCircle(Lightning, radius = 5f, center = Offset(cx, cy))
}

@Suppress("unused")
private fun DrawScope.drawGears(angleDeg: Float) {
    val cx = size.width / 2
    val cy = size.height / 2
    val unit = size.minDimension

    val teeth1 = 10
    val teeth2 = 6
    val outer1 = unit * 0.28f
    val inner1 = unit * 0.19f
    val outer2 = unit * 0.18f
    val inner2 = unit * 0.12f

    // Pitch radius = midpoint between inner and outer
    val pitch1 = (outer1 + inner1) / 2f
    val pitch2 = (outer2 + inner2) / 2f

    // Centers positioned so pitch circles are tangent
    val dist = pitch1 + pitch2
    val angle1Rad = Math.toRadians(-30.0) // angle of the line between centers
    val cx1 = cx - (dist * 0.5f * cos(angle1Rad)).toFloat()
    val cy1 = cy - (dist * 0.5f * sin(angle1Rad)).toFloat()
    val cx2 = cx + (dist * 0.5f * cos(angle1Rad)).toFloat()
    val cy2 = cy + (dist * 0.5f * sin(angle1Rad)).toFloat()

    // Phase offset: half a tooth so teeth interleave into gaps
    val toothAngle2 = 360f / teeth2
    val phaseOffset = toothAngle2 / 2f

    drawGear(
        center = Offset(cx1, cy1),
        outerRadius = outer1,
        innerRadius = inner1,
        holeRadius = unit * 0.06f,
        teeth = teeth1,
        angle = angleDeg,
        color = Lightning,
    )

    drawGear(
        center = Offset(cx2, cy2),
        outerRadius = outer2,
        innerRadius = inner2,
        holeRadius = unit * 0.04f,
        teeth = teeth2,
        angle = -angleDeg * (teeth1.toFloat() / teeth2) + phaseOffset,
        color = LightningBright,
    )
}

private fun DrawScope.drawGear(
    center: Offset,
    outerRadius: Float,
    innerRadius: Float,
    holeRadius: Float,
    teeth: Int,
    angle: Float,
    color: androidx.compose.ui.graphics.Color,
) {
    val path = androidx.compose.ui.graphics.Path()
    val angleRad = Math.toRadians(angle.toDouble())
    val toothArc = (2.0 * Math.PI) / teeth

    // Build gear outline
    for (i in 0 until teeth) {
        val baseAngle = angleRad + i * toothArc

        // Inner start
        val a0 = baseAngle
        val a1 = baseAngle + toothArc * 0.15
        val a2 = baseAngle + toothArc * 0.35
        val a3 = baseAngle + toothArc * 0.65
        val a4 = baseAngle + toothArc * 0.85
        val a5 = baseAngle + toothArc

        fun px(a: Double, r: Float) = center.x + cos(a).toFloat() * r
        fun py(a: Double, r: Float) = center.y + sin(a).toFloat() * r

        if (i == 0) {
            path.moveTo(px(a0, innerRadius), py(a0, innerRadius))
        }
        path.lineTo(px(a1, innerRadius), py(a1, innerRadius))
        path.lineTo(px(a2, outerRadius), py(a2, outerRadius))
        path.lineTo(px(a3, outerRadius), py(a3, outerRadius))
        path.lineTo(px(a4, innerRadius), py(a4, innerRadius))
        path.lineTo(px(a5, innerRadius), py(a5, innerRadius))
    }
    path.close()

    // Draw gear outline
    drawPath(
        path = path,
        color = color.copy(alpha = 0.7f),
        style = Stroke(width = 1.5f),
    )

    // Draw center hole
    drawCircle(
        color = color.copy(alpha = 0.5f),
        radius = holeRadius,
        center = center,
        style = Stroke(width = 1.5f),
    )

    // Draw center dot
    drawCircle(
        color = color,
        radius = 2f,
        center = center,
    )
}
