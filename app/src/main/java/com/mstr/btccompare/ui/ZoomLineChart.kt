package com.mstr.btccompare.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import com.mstr.btccompare.data.PricePoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

data class ChartSeries(
    val label: String,
    val color: Color,
    val points: List<PricePoint>,
    val dashed: Boolean = false,
    val rightAxis: Boolean = false,
    val fill: Boolean = false
)

data class ChartMarker(
    val timestampSec: Long,
    val value: Double,
    val isBuy: Boolean
)

@Composable
fun ZoomLineChart(
    series: List<ChartSeries>,
    gridColor: Color,
    axisColor: Color,
    tooltipBg: Color,
    tooltipText: Color,
    markers: List<ChartMarker> = emptyList(),
    markerSeriesIndex: Int = 0,
    valueFormatter: (Double) -> String = ::formatPriceValue,
    modifier: Modifier = Modifier
) {
    var scale by remember { mutableStateOf(1f) }
    var centerFrac by remember { mutableStateOf(0.5f) }
    var tapX by remember { mutableStateOf<Float?>(null) }
    var widthPx by remember { mutableStateOf(1f) }

    val density = LocalDensity.current
    val labelPx = with(density) { 11.sp.toPx() }
    val tipTitlePx = with(density) { 12.sp.toPx() }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val newScale = (scale * zoomChange).coerceIn(1f, 12f)
        val w = widthPx.coerceAtLeast(1f)
        centerFrac -= panChange.x / (w * newScale)
        val half = 0.5f / newScale
        centerFrac = centerFrac.coerceIn(half, 1f - half)
        scale = newScale
        // Tap is no longer valid when transforming
        tapX = null
    }

    // Reset zoom on double-tap.
    val tapModifier = Modifier.pointerInput(series) {
        detectTapGestures(
            onDoubleTap = {
                scale = 1f
                centerFrac = 0.5f
                tapX = null
            },
            onTap = { tap -> tapX = tap.x }
        )
    }

    Box(
        modifier = modifier
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .transformable(transformState)
            .then(tapModifier)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (series.isEmpty() || series.all { it.points.size < 2 }) return@Canvas
            val w = size.width
            val h = size.height
            val padL = 64f
            val padR = 64f
            val padT = 14f
            val padB = 30f
            val plotW = w - padL - padR
            val plotH = h - padT - padB

            val all = series.flatMap { it.points }
            if (all.isEmpty()) return@Canvas
            val tsMin = all.minOf { it.timestampSec }
            val tsMax = all.maxOf { it.timestampSec }
            val tsSpan = (tsMax - tsMin).coerceAtLeast(1L)

            // ── Group y-scales by axis (left = !rightAxis, right = rightAxis)
            // so e.g. BTC@open and BTC@close share one scale and visually align.
            val leftPoints = series.filter { !it.rightAxis }.flatMap { it.points }
            val rightPoints = series.filter { it.rightAxis }.flatMap { it.points }
            val leftMin = leftPoints.minOfOrNull { it.value } ?: 0.0
            val leftMax = leftPoints.maxOfOrNull { it.value } ?: 1.0
            val leftSpan = (leftMax - leftMin).takeIf { it > 0.0 } ?: 1.0
            val rightMin = rightPoints.minOfOrNull { it.value } ?: 0.0
            val rightMax = rightPoints.maxOfOrNull { it.value } ?: 1.0
            val rightSpan = (rightMax - rightMin).takeIf { it > 0.0 } ?: 1.0

            // Grid (horizontal lines)
            val dashed = PathEffect.dashPathEffect(floatArrayOf(6f, 8f))
            val gridLines = 4
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

            // For each timestamp, x_screen = padL + plotW * ((frac - centerFrac) * scale + 0.5f)
            fun fracToScreen(frac: Float): Float =
                padL + plotW * ((frac - centerFrac) * scale + 0.5f)

            fun tsToScreen(ts: Long): Float {
                val f = (ts - tsMin).toFloat() / tsSpan.toFloat()
                return fracToScreen(f)
            }

            // Draw each series clipped to plot — shares min/max with its axis group
            series.forEach { s ->
                if (s.points.size < 2) return@forEach
                val mn = if (s.rightAxis) rightMin else leftMin
                val span = if (s.rightAxis) rightSpan else leftSpan

                fun yFor(v: Double): Float {
                    val frac = (v - mn) / span
                    return padT + plotH * (1f - frac.toFloat())
                }

                val path = Path()
                val fillPath = Path()
                var moved = false
                for ((idx, p) in s.points.withIndex()) {
                    val x = tsToScreen(p.timestampSec)
                    if (x < padL - 50f || x > padL + plotW + 50f) {
                        // Outside visible window, but path needs continuation
                    }
                    val y = yFor(p.value)
                    val xClamped = x.coerceIn(padL, padL + plotW)
                    if (!moved) {
                        path.moveTo(x, y)
                        if (s.fill) {
                            fillPath.moveTo(xClamped, padT + plotH)
                            fillPath.lineTo(x, y)
                        }
                        moved = true
                    } else {
                        path.lineTo(x, y)
                        if (s.fill) fillPath.lineTo(x, y)
                    }
                    if (s.fill && idx == s.points.size - 1) {
                        fillPath.lineTo(xClamped, padT + plotH)
                        fillPath.close()
                    }
                }

                if (s.fill) {
                    drawPath(
                        path = fillPath,
                        brush = Brush.verticalGradient(
                            listOf(s.color.copy(alpha = 0.35f), s.color.copy(alpha = 0.04f))
                        )
                    )
                }

                val stroke = if (s.dashed) {
                    Stroke(width = 2.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
                } else {
                    Stroke(width = 3f)
                }
                drawPath(path = path, color = s.color, style = stroke)
            }

            // Buy/sell markers drawn on the same scale as their host series.
            val markerHost = series.getOrNull(markerSeriesIndex)
            if (markers.isNotEmpty() && markerHost != null && markerHost.points.size >= 2) {
                val mn = if (markerHost.rightAxis) rightMin else leftMin
                val span = if (markerHost.rightAxis) rightSpan else leftSpan
                markers.forEach { m ->
                    val x = tsToScreen(m.timestampSec)
                    if (x !in (padL - 8f)..(padL + plotW + 8f)) return@forEach
                    val y = padT + plotH * (1f - ((m.value - mn) / span).toFloat())
                    val triPath = Path()
                    val sz = 7f
                    val gap = 6f
                    val color = if (m.isBuy) Color(0xFF22C55E) else Color(0xFFEF4444)
                    if (m.isBuy) {
                        // Up triangle below the price
                        val baseY = (y + gap + sz * 1.6f).coerceAtMost(padT + plotH - 2f)
                        triPath.moveTo(x, baseY - sz * 1.6f)
                        triPath.lineTo(x - sz, baseY)
                        triPath.lineTo(x + sz, baseY)
                        triPath.close()
                    } else {
                        // Down triangle above the price
                        val baseY = (y - gap - sz * 1.6f).coerceAtLeast(padT + 2f)
                        triPath.moveTo(x, baseY + sz * 1.6f)
                        triPath.lineTo(x - sz, baseY)
                        triPath.lineTo(x + sz, baseY)
                        triPath.close()
                    }
                    drawPath(triPath, color = color)
                    drawPath(
                        triPath,
                        color = Color.White.copy(alpha = 0.9f),
                        style = Stroke(width = 1.2f)
                    )
                }
            }

            // Y-axis labels — labels match the **group** min/max so multiple
            // lines on the same axis share one consistent scale.
            val leftSeries = series.firstOrNull { !it.rightAxis }
            val rightSeries = series.firstOrNull { it.rightAxis }
            if (leftSeries != null && leftPoints.size >= 2) {
                val paint = textPaint(leftSeries.color, labelPx)
                drawContext.canvas.nativeCanvas.drawText(
                    valueFormatter(leftMax), 4f, padT + 10f, paint
                )
                drawContext.canvas.nativeCanvas.drawText(
                    valueFormatter(leftMin), 4f, padT + plotH, paint
                )
                drawContext.canvas.nativeCanvas.drawText(
                    valueFormatter((leftMax + leftMin) / 2.0), 4f, padT + plotH / 2f, paint
                )
            }
            if (rightSeries != null && rightPoints.size >= 2) {
                val paint = textPaint(rightSeries.color, labelPx)
                drawContext.canvas.nativeCanvas.drawText(
                    valueFormatter(rightMax), padL + plotW + 4f, padT + 10f, paint
                )
                drawContext.canvas.nativeCanvas.drawText(
                    valueFormatter(rightMin), padL + plotW + 4f, padT + plotH, paint
                )
                drawContext.canvas.nativeCanvas.drawText(
                    valueFormatter((rightMax + rightMin) / 2.0),
                    padL + plotW + 4f, padT + plotH / 2f, paint
                )
            }

            // X-axis date labels (3 anchor points across visible window)
            val visibleStartFrac = (centerFrac - 0.5f / scale).coerceIn(0f, 1f)
            val visibleEndFrac = (centerFrac + 0.5f / scale).coerceIn(0f, 1f)
            val tsAtStart = tsMin + (visibleStartFrac * tsSpan).toLong()
            val tsAtMid = tsMin + (((visibleStartFrac + visibleEndFrac) / 2f) * tsSpan).toLong()
            val tsAtEnd = tsMin + (visibleEndFrac * tsSpan).toLong()
            val axisPaint = android.graphics.Paint().apply {
                isAntiAlias = true
                color = android.graphics.Color.argb(220, 200, 210, 230)
                textSize = labelPx
            }
            val fmt = SimpleDateFormat("MMM d, yy", Locale.US)
            drawContext.canvas.nativeCanvas.drawText(
                fmt.format(Date(tsAtStart * 1000L)),
                padL, padT + plotH + 22f, axisPaint
            )
            drawContext.canvas.nativeCanvas.drawText(
                fmt.format(Date(tsAtMid * 1000L)),
                padL + plotW / 2f - 35f, padT + plotH + 22f, axisPaint
            )
            drawContext.canvas.nativeCanvas.drawText(
                fmt.format(Date(tsAtEnd * 1000L)),
                padL + plotW - 70f, padT + plotH + 22f, axisPaint
            )

            // Tap → crosshair + tooltip
            val tx = tapX
            if (tx != null && tx in padL..(padL + plotW)) {
                val relX = (tx - padL) / plotW
                val targetFrac = ((relX - 0.5f) / scale + centerFrac).coerceIn(0f, 1f)
                val targetTs = tsMin + (targetFrac * tsSpan).toLong()

                drawLine(
                    color = axisColor.copy(alpha = 0.7f),
                    start = Offset(tx, padT),
                    end = Offset(tx, padT + plotH),
                    strokeWidth = 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
                )

                val rows = ArrayList<Triple<String, Double, Color>>()
                series.forEach { s ->
                    if (s.points.isEmpty()) return@forEach
                    val nearest = s.points.minBy { abs(it.timestampSec - targetTs) }
                    rows.add(Triple(s.label, nearest.value, s.color))
                    val nx = tsToScreen(nearest.timestampSec)
                    // Use the *group* scale so the dot lands exactly on the
                    // line we drew above.
                    val mn = if (s.rightAxis) rightMin else leftMin
                    val span2 = if (s.rightAxis) rightSpan else leftSpan
                    val ny = padT + plotH * (1f - ((nearest.value - mn) / span2).toFloat())
                    drawCircle(
                        color = s.color,
                        radius = 5f,
                        center = Offset(nx, ny)
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 2f,
                        center = Offset(nx, ny)
                    )
                }

                val dateFmt = SimpleDateFormat("MMM d, yyyy", Locale.US)
                val dateStr = dateFmt.format(Date(targetTs * 1000L))
                val tipPad = 8f
                val lineH = tipTitlePx + 6f
                val tipH = lineH * (rows.size + 1) + tipPad * 2f
                val tipW = 175f
                var tipX = tx + 12f
                if (tipX + tipW > padL + plotW) tipX = tx - 12f - tipW
                val tipY = padT + 10f

                drawRoundRect(
                    color = tooltipBg,
                    topLeft = Offset(tipX, tipY),
                    size = Size(tipW, tipH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f)
                )

                val titlePaint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    color = android.graphics.Color.argb(
                        255,
                        (tooltipText.red * 255).toInt(),
                        (tooltipText.green * 255).toInt(),
                        (tooltipText.blue * 255).toInt()
                    )
                    textSize = tipTitlePx
                    isFakeBoldText = true
                }
                drawContext.canvas.nativeCanvas.drawText(
                    dateStr, tipX + tipPad, tipY + tipPad + tipTitlePx, titlePaint
                )
                rows.forEachIndexed { i, (lbl, v, col) ->
                    val rowY = tipY + tipPad + tipTitlePx + lineH * (i + 1)
                    val swatchPaint = android.graphics.Paint().apply {
                        isAntiAlias = true
                        color = android.graphics.Color.argb(
                            255,
                            (col.red * 255).toInt(),
                            (col.green * 255).toInt(),
                            (col.blue * 255).toInt()
                        )
                    }
                    drawContext.canvas.nativeCanvas.drawCircle(
                        tipX + tipPad + 4f, rowY - tipTitlePx / 2f, 4f, swatchPaint
                    )
                    val rowPaint = android.graphics.Paint().apply {
                        isAntiAlias = true
                        color = android.graphics.Color.argb(
                            255,
                            (tooltipText.red * 255).toInt(),
                            (tooltipText.green * 255).toInt(),
                            (tooltipText.blue * 255).toInt()
                        )
                        textSize = tipTitlePx
                    }
                    drawContext.canvas.nativeCanvas.drawText(
                        "$lbl  ${valueFormatter(v)}",
                        tipX + tipPad + 14f, rowY, rowPaint
                    )
                }
            }
        }
    }
}

private fun textPaint(color: Color, sizePx: Float): android.graphics.Paint =
    android.graphics.Paint().apply {
        isAntiAlias = true
        this.color = android.graphics.Color.argb(
            255,
            (color.red * 255).toInt(),
            (color.green * 255).toInt(),
            (color.blue * 255).toInt()
        )
        textSize = sizePx
    }

internal fun formatPriceValue(v: Double): String = when {
    v >= 1_000_000 -> "$%.2fM".format(v / 1_000_000)
    v >= 1_000 -> "$%.1fk".format(v / 1_000)
    v >= 10 -> "$%.0f".format(v)
    v >= 1 -> "$%.2f".format(v)
    else -> "%.4f".format(v)
}
