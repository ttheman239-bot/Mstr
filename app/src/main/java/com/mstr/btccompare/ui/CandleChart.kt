package com.mstr.btccompare.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import com.mstr.btccompare.data.CandlePoint
import com.mstr.btccompare.data.PricePoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CandleChart(
    candles: List<CandlePoint>,
    upColor: Color,
    downColor: Color,
    gridColor: Color,
    axisColor: Color,
    leftAxisColor: Color,
    overlayLine: List<PricePoint> = emptyList(),
    overlayColor: Color = Color.Transparent,
    yAxisFormatter: (Double) -> String = ::defaultPriceFormat,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val labelPx = with(density) { 11.sp.toPx() }

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (candles.isEmpty()) return@Canvas
            val w = size.width
            val h = size.height
            val padL = 60f
            val padR = 16f
            val padT = 14f
            val padB = 30f
            val plotW = w - padL - padR
            val plotH = h - padT - padB

            val maxV = candles.maxOf { it.high }
            val minV = candles.minOf { it.low }
            val span = (maxV - minV).takeIf { it > 0.0 } ?: 1.0

            val gridLines = 4
            val dashed = PathEffect.dashPathEffect(floatArrayOf(6f, 8f))
            for (i in 0..gridLines) {
                val y = padT + plotH * i / gridLines
                drawLine(
                    color = gridColor,
                    start = Offset(padL, y),
                    end = Offset(padL + plotW, y),
                    strokeWidth = 1f,
                    pathEffect = dashed
                )
            }

            drawLine(axisColor, Offset(padL, padT), Offset(padL, padT + plotH), 2f)
            drawLine(axisColor, Offset(padL, padT + plotH), Offset(padL + plotW, padT + plotH), 2f)

            val n = candles.size
            val slot = plotW / n
            val bodyW = (slot * 0.65f).coerceAtLeast(1f).coerceAtMost(14f)

            fun yFor(v: Double): Float {
                val frac = (v - minV) / span
                return padT + plotH * (1f - frac.toFloat())
            }

            candles.forEachIndexed { i, c ->
                val cx = padL + slot * (i + 0.5f)
                val color = if (c.isUp) upColor else downColor
                val yHigh = yFor(c.high)
                val yLow = yFor(c.low)
                val yOpen = yFor(c.open)
                val yClose = yFor(c.close)
                drawLine(
                    color = color,
                    start = Offset(cx, yHigh),
                    end = Offset(cx, yLow),
                    strokeWidth = 1.5f
                )
                val top = minOf(yOpen, yClose)
                val bot = maxOf(yOpen, yClose)
                val rectH = (bot - top).coerceAtLeast(1.5f)
                drawRect(
                    color = color,
                    topLeft = Offset(cx - bodyW / 2f, top),
                    size = androidx.compose.ui.geometry.Size(bodyW, rectH)
                )
            }

            if (overlayLine.size >= 2 && overlayColor != Color.Transparent) {
                val maxL = overlayLine.maxOf { it.value }
                val minL = overlayLine.minOf { it.value }
                val spanL = (maxL - minL).takeIf { it > 0.0 } ?: 1.0
                val path = androidx.compose.ui.graphics.Path()
                overlayLine.forEachIndexed { i, p ->
                    val x = padL + plotW * i / (overlayLine.size - 1).toFloat()
                    val frac = (p.value - minL) / spanL
                    val y = padT + plotH * (1f - frac.toFloat())
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(
                    path = path,
                    color = overlayColor,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5f)
                )
            }

            // Y axis labels (high/low)
            val leftPaint = android.graphics.Paint().apply {
                isAntiAlias = true
                color = android.graphics.Color.argb(
                    255,
                    (leftAxisColor.red * 255).toInt(),
                    (leftAxisColor.green * 255).toInt(),
                    (leftAxisColor.blue * 255).toInt()
                )
                textSize = labelPx
            }
            drawContext.canvas.nativeCanvas.drawText(
                yAxisFormatter(maxV), 4f, padT + 10f, leftPaint
            )
            drawContext.canvas.nativeCanvas.drawText(
                yAxisFormatter(minV), 4f, padT + plotH, leftPaint
            )
            drawContext.canvas.nativeCanvas.drawText(
                yAxisFormatter((maxV + minV) / 2.0), 4f, padT + plotH / 2f, leftPaint
            )

            // X axis labels
            val axisPaint = android.graphics.Paint().apply {
                isAntiAlias = true
                color = android.graphics.Color.argb(220, 200, 210, 230)
                textSize = labelPx
            }
            val fmt = SimpleDateFormat("MMM d", Locale.US)
            val first = fmt.format(Date(candles.first().timestampSec * 1000L))
            val mid = fmt.format(Date(candles[n / 2].timestampSec * 1000L))
            val last = fmt.format(Date(candles.last().timestampSec * 1000L))
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

private fun defaultPriceFormat(v: Double): String = when {
    v >= 1_000_000 -> "%.2fM".format(v / 1_000_000)
    v >= 1_000 -> "%.1fk".format(v / 1_000)
    v >= 10 -> "%.0f".format(v)
    else -> "%.2f".format(v)
}
