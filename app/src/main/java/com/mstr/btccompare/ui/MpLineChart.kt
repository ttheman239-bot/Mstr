package com.mstr.btccompare.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 *  AndroidView-wrapped MPAndroidChart line chart.
 *
 *  Each [ChartSeries] becomes its own LineDataSet on either the LEFT
 *  or RIGHT y-axis depending on `rightAxis`.  Pinch-zoom, drag-pan and
 *  highlight-on-tap are provided by the library out of the box.
 */
@Composable
fun MpLineChart(
    series: List<ChartSeries>,
    bgColor: Color,
    gridColor: Color,
    axisColor: Color,
    modifier: Modifier = Modifier,
    showLegend: Boolean = true,
    yLeftFormatter: (Float) -> String = ::defaultYFormat,
    yRightFormatter: (Float) -> String = ::defaultYFormat
) {
    val datePattern = remember { SimpleDateFormat("MMM d", Locale.US) }
    // Capture as Int up-front to avoid name collisions inside .apply
    // blocks (XAxis.gridColor : Int collides with outer gridColor : Color).
    val bgArgb = bgColor.toArgb()
    val gridArgb = gridColor.toArgb()
    val axisArgb = axisColor.toArgb()

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            LineChart(ctx).apply {
                description.isEnabled = false
                setBackgroundColor(bgArgb)
                setDrawGridBackground(false)
                setNoDataText("กำลังโหลด...")
                setNoDataTextColor(axisArgb)

                setTouchEnabled(true)
                isDragEnabled = true
                setScaleEnabled(true)
                setPinchZoom(true)
                isDoubleTapToZoomEnabled = true
                isHighlightPerTapEnabled = true
                isHighlightPerDragEnabled = false

                xAxis.apply {
                    position = XAxis.XAxisPosition.BOTTOM
                    textColor = axisArgb
                    gridColor = gridArgb
                    setDrawAxisLine(false)
                    setLabelCount(5, false)
                    valueFormatter = object : ValueFormatter() {
                        override fun getFormattedValue(value: Float): String =
                            datePattern.format(Date(value.toLong() * 1000L))
                    }
                }

                axisLeft.apply {
                    textColor = axisArgb
                    gridColor = gridArgb
                    setDrawAxisLine(false)
                    valueFormatter = object : ValueFormatter() {
                        override fun getFormattedValue(value: Float) = yLeftFormatter(value)
                    }
                }

                axisRight.apply {
                    isEnabled = true
                    textColor = axisArgb
                    setDrawGridLines(false)
                    setDrawAxisLine(false)
                    valueFormatter = object : ValueFormatter() {
                        override fun getFormattedValue(value: Float) = yRightFormatter(value)
                    }
                }

                legend.apply {
                    isEnabled = showLegend
                    textColor = axisArgb
                    form = Legend.LegendForm.LINE
                    horizontalAlignment = Legend.LegendHorizontalAlignment.CENTER
                    verticalAlignment = Legend.LegendVerticalAlignment.TOP
                    orientation = Legend.LegendOrientation.HORIZONTAL
                    setDrawInside(false)
                }

                setExtraOffsets(8f, 8f, 8f, 8f)
            }
        },
        update = { chart ->
            val datasets = ArrayList<ILineDataSet>(series.size)
            var hasRightAxis = false
            for (s in series) {
                if (s.points.size < 2) continue
                val entries = s.points.map { Entry(it.timestampSec.toFloat(), it.value.toFloat()) }
                val ds = LineDataSet(entries, s.label).apply {
                    color = s.color.toArgb()
                    lineWidth = 2f
                    setDrawCircles(false)
                    setDrawValues(false)
                    setDrawFilled(s.fill)
                    if (s.fill) {
                        fillColor = s.color.toArgb()
                        fillAlpha = 60
                    }
                    if (s.dashed) enableDashedLine(12f, 6f, 0f)
                    axisDependency = if (s.rightAxis) {
                        hasRightAxis = true
                        YAxis.AxisDependency.RIGHT
                    } else {
                        YAxis.AxisDependency.LEFT
                    }
                    highLightColor = axisArgb
                    highlightLineWidth = 1.2f
                    setDrawHighlightIndicators(true)
                    enableDashedHighlightLine(8f, 4f, 0f)
                    mode = LineDataSet.Mode.LINEAR
                }
                datasets.add(ds)
            }
            chart.axisRight.isEnabled = hasRightAxis
            chart.data = if (datasets.isEmpty()) null else LineData(datasets)
            chart.fitScreen()
            chart.invalidate()
        }
    )
}

private fun defaultYFormat(v: Float): String {
    val a = kotlin.math.abs(v)
    return when {
        a >= 1_000_000 -> "%.2fM".format(v / 1_000_000)
        a >= 1_000 -> "%.1fk".format(v / 1_000)
        a >= 10 -> "%.0f".format(v)
        else -> "%.2f".format(v)
    }
}
