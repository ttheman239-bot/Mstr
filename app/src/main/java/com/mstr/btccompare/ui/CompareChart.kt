package com.mstr.btccompare.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import com.mstr.btccompare.data.PricePoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LineChart(
    points: List<PricePoint>,
    lineColor: Color,
    fillColor: Color,
    gridColor: Color,
    axisColor: Color,
    yAxisFormatter: (Double) -> String = ::formatAxis,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val labelPx = with(density) { 11.sp.toPx() }

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (points.size < 2) return@Canvas
            val w = size.width
            val h = size.height
            val padL = 60f
            val padR = 16f
            val padT = 14f
            val padB = 30f
            val plotW = w - padL - padR
            val plotH = h - padT - padB

            val max = points.maxOf { it.value }
            val min = points.minOf { it.value }
            val span = (max - min).takeIf { it > 0.0 } ?: 1.0

            val gridLines = 4
            val dashed = PathEffect.dashPathEffect(floatArrayOf(6f, 8f))
            for (i in 0..gridLines) {
                val y = padT + plotH * i / gridLines
                drawLine(gridColor, Offset(padL, y), Offset(padL + plotW, y), 1f, pathEffect = dashed)
            }

            drawLine(axisColor, Offset(padL, padT), Offset(padL, padT + plotH), 2f)
            drawLine(axisColor, Offset(padL, padT + plotH), Offset(padL + plotW, padT + plotH), 2f)

            val path = Path()
            val fill = Path()
            points.forEachIndexed { i, p ->
                val x = padL + plotW * i / (points.size - 1).toFloat()
                val frac = (p.value - min) / span
                val y = padT + plotH * (1f - frac.toFloat())
                if (i == 0) {
                    path.moveTo(x, y)
                    fill.moveTo(x, padT + plotH)
                    fill.lineTo(x, y)
                } else {
                    path.lineTo(x, y)
                    fill.lineTo(x, y)
                }
                if (i == points.size - 1) {
                    fill.lineTo(x, padT + plotH)
                    fill.close()
                }
            }
            drawPath(
                path = fill,
                brush = Brush.verticalGradient(
                    listOf(fillColor.copy(alpha = 0.4f), fillColor.copy(alpha = 0.05f))
                )
            )
            drawPath(path = path, color = lineColor, style = Stroke(width = 3.5f))

            val leftPaint = android.graphics.Paint().apply {
                isAntiAlias = true
                color = android.graphics.Color.argb(
                    255,
                    (lineColor.red * 255).toInt(),
                    (lineColor.green * 255).toInt(),
                    (lineColor.blue * 255).toInt()
                )
                textSize = labelPx
            }
            drawContext.canvas.nativeCanvas.drawText(yAxisFormatter(max), 4f, padT + 10f, leftPaint)
            drawContext.canvas.nativeCanvas.drawText(yAxisFormatter(min), 4f, padT + plotH, leftPaint)
            drawContext.canvas.nativeCanvas.drawText(
                yAxisFormatter((max + min) / 2.0), 4f, padT + plotH / 2f, leftPaint
            )

            val axisPaint = android.graphics.Paint().apply {
                isAntiAlias = true
                color = android.graphics.Color.argb(220, 200, 210, 230)
                textSize = labelPx
            }
            val fmt = SimpleDateFormat("MMM d", Locale.US)
            val first = fmt.format(Date(points.first().timestampSec * 1000L))
            val mid = fmt.format(Date(points[points.size / 2].timestampSec * 1000L))
            val last = fmt.format(Date(points.last().timestampSec * 1000L))
            drawContext.canvas.nativeCanvas.drawText(first, padL, padT + plotH + 22f, axisPaint)
            drawContext.canvas.nativeCanvas.drawText(
                mid, padL + plotW / 2f - 20f, padT + plotH + 22f, axisPaint
            )
            drawContext.canvas.nativeCanvas.drawText(
                last, padL + plotW - 50f, padT + plotH + 22f, axisPaint
            )
        }
    }
}

private fun formatAxis(v: Double): String = when {
    v >= 1_000_000 -> "%.2fM".format(v / 1_000_000)
    v >= 1_000 -> "%.1fk".format(v / 1_000)
    v >= 10 -> "%.1f".format(v)
    else -> "%.3f".format(v)
}
