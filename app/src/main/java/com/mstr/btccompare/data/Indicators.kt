package com.mstr.btccompare.data

import kotlin.math.sqrt

object Indicators {

    fun sma(values: List<Double>, period: Int): List<Double?> {
        val out = MutableList<Double?>(values.size) { null }
        if (values.isEmpty() || period <= 0) return out
        var sum = 0.0
        for (i in values.indices) {
            sum += values[i]
            if (i >= period) sum -= values[i - period]
            if (i >= period - 1) out[i] = sum / period
        }
        return out
    }

    fun rsi(values: List<Double>, period: Int = 14): List<Double?> {
        val out = MutableList<Double?>(values.size) { null }
        if (values.size <= period) return out

        var gainSum = 0.0
        var lossSum = 0.0
        for (i in 1..period) {
            val change = values[i] - values[i - 1]
            if (change >= 0) gainSum += change else lossSum -= change
        }
        var avgGain = gainSum / period
        var avgLoss = lossSum / period
        out[period] = computeRsi(avgGain, avgLoss)

        for (i in (period + 1) until values.size) {
            val change = values[i] - values[i - 1]
            val gain = if (change > 0) change else 0.0
            val loss = if (change < 0) -change else 0.0
            avgGain = (avgGain * (period - 1) + gain) / period
            avgLoss = (avgLoss * (period - 1) + loss) / period
            out[i] = computeRsi(avgGain, avgLoss)
        }
        return out
    }

    private fun computeRsi(gain: Double, loss: Double): Double {
        if (loss == 0.0) return 100.0
        val rs = gain / loss
        return 100.0 - 100.0 / (1.0 + rs)
    }

    fun zScore(values: List<Double>, period: Int = 60): List<Double?> {
        val out = MutableList<Double?>(values.size) { null }
        if (values.size < period) return out
        for (i in (period - 1) until values.size) {
            val window = values.subList(i - period + 1, i + 1)
            val mean = window.average()
            val variance = window.sumOf { (it - mean) * (it - mean) } / period
            val sd = sqrt(variance)
            out[i] = if (sd > 0) (values[i] - mean) / sd else 0.0
        }
        return out
    }
}
