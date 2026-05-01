package com.mstr.btccompare.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mstr.btccompare.data.PricePoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun normalize(points: List<PricePoint>): List<Float> {
    if (points.isEmpty()) return emptyList()
    val min = points.minOf { it.value }
    val max = points.maxOf { it.value }
    val span = (max - min).takeIf { it > 0.0 } ?: 1.0
    return points.map { ((it.value - min) / span).toFloat() }
}

@Composable
fun CompareChart(
    btc: List<PricePoint>,
    mstr: List<PricePoint>,
    btcColor: Color,
    mstrColor: Color,
    gridColor: Color,
    axisColor: Color,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val labelPx = with(density) { 12.sp.toPx() }

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val padL = 56f
            val padR = 56f
            val padT = 16f
            val padB = 36f
            val plotW = w - padL - padR
            val plotH = h - padT - padB

            // Grid
            val gridLines = 5
            val dashed = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
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

            // Axis
            drawLine(axisColor, Offset(padL, padT), Offset(padL, padT + plotH), 2f)
            drawLine(axisColor, Offset(padL, padT + plotH), Offset(padL + plotW, padT + plotH), 2f)

            fun drawSeries(points: List<PricePoint>, color: Color) {
                if (points.size < 2) return
                val ys = normalize(points)
                val path = Path()
                points.forEachIndexed { i, _ ->
                    val x = padL + plotW * i / (points.size - 1).toFloat()
                    val y = padT + plotH * (1f - ys[i])
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path = path, color = color, style = Stroke(width = 4f))
            }

            drawSeries(btc, btcColor)
            drawSeries(mstr, mstrColor)

            // X axis labels (start / mid / end dates)
            val axisPaint = android.graphics.Paint().apply {
                isAntiAlias = true
                this.color = android.graphics.Color.argb(220, 200, 210, 230)
                textSize = labelPx
            }
            val fmt = SimpleDateFormat("MMM d", Locale.US)
            val refList = if (btc.isNotEmpty()) btc else mstr
            if (refList.isNotEmpty()) {
                val first = fmt.format(Date(refList.first().timestampSec * 1000L))
                val mid = fmt.format(Date(refList[refList.size / 2].timestampSec * 1000L))
                val last = fmt.format(Date(refList.last().timestampSec * 1000L))
                drawContext.canvas.nativeCanvas.drawText(
                    first, padL, padT + plotH + 24f, axisPaint
                )
                drawContext.canvas.nativeCanvas.drawText(
                    mid, padL + plotW / 2f - 20f, padT + plotH + 24f, axisPaint
                )
                drawContext.canvas.nativeCanvas.drawText(
                    last, padL + plotW - 50f, padT + plotH + 24f, axisPaint
                )
            }

            // Y axis hint values: BTC left, MSTR right
            if (btc.isNotEmpty()) {
                val maxBtc = btc.maxOf { it.value }
                val minBtc = btc.minOf { it.value }
                val leftPaint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    color = android.graphics.Color.argb(255, 247, 147, 26)
                    textSize = labelPx
                }
                drawContext.canvas.nativeCanvas.drawText(
                    formatPrice(maxBtc), 4f, padT + 10f, leftPaint
                )
                drawContext.canvas.nativeCanvas.drawText(
                    formatPrice(minBtc), 4f, padT + plotH, leftPaint
                )
            }
            if (mstr.isNotEmpty()) {
                val maxMstr = mstr.maxOf { it.value }
                val minMstr = mstr.minOf { it.value }
                val rightPaint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    color = android.graphics.Color.argb(255, 96, 165, 250)
                    textSize = labelPx
                }
                drawContext.canvas.nativeCanvas.drawText(
                    formatPrice(maxMstr), padL + plotW + 4f, padT + 10f, rightPaint
                )
                drawContext.canvas.nativeCanvas.drawText(
                    formatPrice(minMstr), padL + plotW + 4f, padT + plotH, rightPaint
                )
            }
        }
    }
}

private fun formatPrice(v: Double): String {
    return when {
        v >= 1_000_000 -> "%.2fM".format(v / 1_000_000)
        v >= 1_000 -> "%.1fk".format(v / 1_000)
        else -> "%.1f".format(v)
    }
}

