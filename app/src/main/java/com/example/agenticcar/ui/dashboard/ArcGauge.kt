package com.example.agenticcar.ui.dashboard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign

/**
 * 270° arc gauge. The animated fraction is read only inside the draw lambda, so
 * the 4 Hz telemetry updates animate in the draw phase without extra recompositions.
 */
@Composable
fun ArcGauge(
    value: Float?,
    maxValue: Float,
    valueText: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    val fraction by animateFloatAsState(
        targetValue = ((value ?: 0f) / maxValue).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 200),
        label = "gauge",
    )
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val valueColor = MaterialTheme.colorScheme.primary
    val endColor = MaterialTheme.colorScheme.tertiary
    val warnColor = MaterialTheme.colorScheme.error

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .semantics { contentDescription = "$label $valueText" }
            .drawWithCache {
                val strokeWidth = size.minDimension * 0.09f
                val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                val inset = strokeWidth / 2f
                val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
                val topLeft = Offset(inset, inset)
                // Drawn rotated so the sweep gradient's 0..0.75 maps onto the 270° dial, primary into tertiary.
                val sweep = Brush.sweepGradient(
                    0f to valueColor,
                    0.75f to endColor,
                    1f to valueColor,
                    center = Offset(size.width / 2, size.height / 2),
                )
                onDrawBehind {
                    drawArc(trackColor, START_ANGLE, SWEEP_ANGLE, false, topLeft, arcSize, style = stroke)
                    rotate(START_ANGLE) {
                        if (fraction > WARN_FRACTION) {
                            drawArc(warnColor, 0f, SWEEP_ANGLE * fraction, false, topLeft, arcSize, style = stroke)
                        } else {
                            drawArc(sweep, 0f, SWEEP_ANGLE * fraction, false, topLeft, arcSize, style = stroke)
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = valueText, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Text(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private const val START_ANGLE = 135f
private const val SWEEP_ANGLE = 270f
private const val WARN_FRACTION = 0.85f
