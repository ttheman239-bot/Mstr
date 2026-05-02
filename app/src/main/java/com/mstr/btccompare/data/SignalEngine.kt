package com.mstr.btccompare.data

import kotlin.math.abs
import kotlin.math.sqrt

// ════════════════════════════════════════════════════════════════════════════
//  SignalEngine — pure-price MSTR/BTC pair-trade signals
//
//  Inputs: daily BTC@9:30 ET, BTC@16:00 ET, daily MSTR OHLC.
//
//  Three independent signals are summed into a combined score:
//
//   ① Ratio mean-reversion  (60-day Z of MSTR/BTC)
//        Z < -1.5 → +2     |   Z > +1.5 → -2
//        Z < -0.7 → +1     |   Z > +0.7 → -1
//
//   ② Trend / RSI(14) of MSTR close
//        RSI < 30 → +2     |   RSI > 70 → -2
//        RSI < 45 → +1     |   RSI > 55 → -1
//
//   ③ Overnight-gap surprise
//        OLS regression of historical (BTC overnight, MSTR gap):
//            MSTR_gap = β · BTC_overnight + α
//        Today's surprise = actual_gap − predicted_gap
//        > +1σ residual → -1  (gap-up overreaction → fade short)
//        < -1σ residual → +1  (gap-up underreaction → fade long)
//
//  All formulas use textbook definitions; references in comments.
// ════════════════════════════════════════════════════════════════════════════

enum class Verdict(val label: String) {
    StrongBuy("STRONG BUY"),
    Buy("BUY"),
    Hold("HOLD"),
    Sell("SELL"),
    StrongSell("STRONG SELL");

    val isBuy: Boolean get() = this == Buy || this == StrongBuy
    val isSell: Boolean get() = this == Sell || this == StrongSell

    companion object {
        fun fromScore(s: Int): Verdict = when {
            s >= 4 -> StrongBuy
            s >= 1 -> Buy
            s <= -4 -> StrongSell
            s <= -1 -> Sell
            else -> Hold
        }
    }
}

data class MstrBar(
    val timestampSec: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double
)

data class SignalLine(
    val title: String,
    val value: String,
    val score: Int,
    val explanation: String
)

data class TradePlan(
    val verdict: Verdict,
    val direction: String,
    val entry: Double,
    val stop: Double,
    val target1: Double,    // 1 R reward
    val target2: Double,    // 2 R reward
    val shares: Int,
    val notional: Double,
    val riskAmount: Double,
    val accountSize: Double
)

data class SignalStats(
    val sampleSize: Int,
    val ratioMean: Double,
    val ratioStdev: Double,
    val gapBeta: Double,
    val gapAlpha: Double,
    val gapCorrelation: Double,
    val gapResidualStd: Double,
    val atr14: Double,
    val rsi14: Double?,
    val zScore60: Double?
)

data class SignalReport(
    val verdict: Verdict,
    val combinedScore: Int,
    val signals: List<SignalLine>,
    val plan: TradePlan,
    val stats: SignalStats
)

object SignalEngine {

    private const val DEFAULT_ACCOUNT = 10_000.0
    private const val RISK_PCT = 0.02            // 2 % of account per trade
    private const val ATR_STOP_MULT = 1.5

    fun analyze(
        btcAtUsClose: List<PricePoint>,
        btcAtUsOpen: List<PricePoint>,
        mstrBars: List<MstrBar>,
        ratio: List<PricePoint>,
        accountSize: Double = DEFAULT_ACCOUNT
    ): SignalReport {
        val signals = ArrayList<SignalLine>(3)

        // ① Ratio mean-reversion
        val ratioVals = ratio.map { it.value }
        val z = zScore(ratioVals, 60).lastOrNull()
        val window = if (ratioVals.size >= 60) ratioVals.takeLast(60) else ratioVals
        val ratioMean = if (window.isNotEmpty()) window.average() else 0.0
        val ratioSd = stdev(window)
        val (sZ, eZ) = scoreZ(z)
        signals.add(SignalLine(
            "MSTR/BTC ratio (60-day Z)",
            z?.let { "Z = %+.2f".format(it) } ?: "—",
            sZ, eZ
        ))

        // ② RSI
        val mstrCloses = mstrBars.map { it.close }
        val rsi = rsi(mstrCloses, 14).lastOrNull()
        val (sR, eR) = scoreRsi(rsi)
        signals.add(SignalLine(
            "MSTR RSI(14)",
            rsi?.let { "%.0f".format(it) } ?: "—",
            sR, eR
        ))

        // ③ Overnight-gap surprise
        val gap = gapRegression(btcAtUsClose, btcAtUsOpen, mstrBars)
        val (sG, eG) = scoreGap(gap.todaysSurprise, gap.residualStd)
        val gapValue = if (gap.todaysSurprise != null && gap.residualStd > 0)
            "%+.2fσ".format(gap.todaysSurprise / gap.residualStd)
        else "—"
        signals.add(SignalLine(
            "Overnight gap surprise",
            gapValue, sG, eG
        ))

        val combined = sZ + sR + sG
        val verdict = Verdict.fromScore(combined)

        val atr = atr14(mstrBars)
        val price = mstrBars.lastOrNull()?.close ?: 0.0
        val plan = buildPlan(verdict, price, atr, accountSize)

        val stats = SignalStats(
            sampleSize = ratio.size,
            ratioMean = ratioMean,
            ratioStdev = ratioSd,
            gapBeta = gap.beta,
            gapAlpha = gap.alpha,
            gapCorrelation = gap.rho,
            gapResidualStd = gap.residualStd,
            atr14 = atr,
            rsi14 = rsi,
            zScore60 = z
        )
        return SignalReport(verdict, combined, signals, plan, stats)
    }

    // ── Score helpers ────────────────────────────────────────────────────

    private fun scoreZ(z: Double?): Pair<Int, String> {
        if (z == null) return 0 to "ต้องการข้อมูล ≥ 60 วันสำหรับ Z-score"
        return when {
            z < -1.5 -> 2 to "Z = %.2f < -1.5 → MSTR ถูกผิดปกติ → mean-revert long".format(z)
            z < -0.7 -> 1 to "Z = %.2f ต่ำกว่าค่าเฉลี่ย → bias long".format(z)
            z > 1.5  -> -2 to "Z = %.2f > +1.5 → MSTR แพงผิดปกติ → mean-revert short".format(z)
            z > 0.7  -> -1 to "Z = %.2f สูงกว่าค่าเฉลี่ย → bias short".format(z)
            else     -> 0 to "Z = %.2f อยู่ในช่วงปกติ".format(z)
        }
    }

    private fun scoreRsi(r: Double?): Pair<Int, String> {
        if (r == null) return 0 to "ข้อมูลไม่พอ"
        return when {
            r < 30.0 -> 2 to "RSI %.0f < 30 → oversold".format(r)
            r < 45.0 -> 1 to "RSI %.0f ต่ำ → mild bullish".format(r)
            r > 70.0 -> -2 to "RSI %.0f > 70 → overbought".format(r)
            r > 55.0 -> -1 to "RSI %.0f สูง → mild bearish".format(r)
            else     -> 0 to "RSI %.0f neutral".format(r)
        }
    }

    private fun scoreGap(surprise: Double?, std: Double): Pair<Int, String> {
        if (surprise == null || std <= 0.0)
            return 0 to "ยังไม่มี gap วันนี้ หรือข้อมูลไม่พอ"
        val sigmas = surprise / std
        return when {
            sigmas < -1.0 -> 1 to "เปิดต่ำกว่าที่ regression คาดไว้ %.1fσ → bias long fade".format(sigmas)
            sigmas > 1.0  -> -1 to "เปิดสูงกว่าที่ regression คาดไว้ %.1fσ → bias short fade".format(sigmas)
            else          -> 0 to "Gap ใกล้ regression (%.1fσ)".format(sigmas)
        }
    }

    // ── Plan ─────────────────────────────────────────────────────────────

    private fun buildPlan(
        verdict: Verdict, price: Double, atr: Double, account: Double
    ): TradePlan {
        if (price <= 0.0 || atr <= 0.0) {
            return TradePlan(
                verdict, "FLAT — ข้อมูลไม่พอ",
                price, 0.0, 0.0, 0.0, 0, 0.0, 0.0, account
            )
        }
        val stopDist = atr * ATR_STOP_MULT
        val riskAmount = account * RISK_PCT
        val shares = (riskAmount / stopDist).toInt().coerceAtLeast(0)
        val notional = shares * price
        return when {
            verdict.isBuy -> TradePlan(
                verdict, "LONG MSTR",
                entry = price,
                stop = price - stopDist,
                target1 = price + stopDist,
                target2 = price + stopDist * 2,
                shares = shares, notional = notional,
                riskAmount = riskAmount, accountSize = account
            )
            verdict.isSell -> TradePlan(
                verdict, "SHORT MSTR",
                entry = price,
                stop = price + stopDist,
                target1 = price - stopDist,
                target2 = price - stopDist * 2,
                shares = shares, notional = notional,
                riskAmount = riskAmount, accountSize = account
            )
            else -> TradePlan(
                verdict, "FLAT — รอสัญญาณชัดกว่านี้",
                price, 0.0, 0.0, 0.0, 0, 0.0, 0.0, account
            )
        }
    }

    // ── Math primitives ──────────────────────────────────────────────────

    /**
     * Average True Range over the last 14 sessions (Wilder, simple-mean variant).
     * TR[t] = max(H-L, |H − prev_close|, |L − prev_close|)
     */
    private fun atr14(bars: List<MstrBar>): Double {
        if (bars.size < 15) return 0.0
        val trs = ArrayList<Double>(bars.size - 1)
        for (i in 1 until bars.size) {
            val b = bars[i]
            val pc = bars[i - 1].close
            val tr = maxOf(b.high - b.low, abs(b.high - pc), abs(b.low - pc))
            trs.add(tr)
        }
        return trs.takeLast(14).average()
    }

    private data class GapResult(
        val beta: Double,
        val alpha: Double,
        val rho: Double,
        val todaysSurprise: Double?,
        val residualStd: Double
    )

    /**
     * OLS regression of MSTR open-gap on BTC overnight return.
     *   β = cov(x, y) / var(x)
     *   α = ȳ − β · x̄
     *   ρ = cov(x, y) / (σx · σy)        (Pearson)
     *   residual_i = y_i − (β·x_i + α)
     *   residualStd = sqrt(mean(residual²))
     */
    private fun gapRegression(
        btcAtUsClose: List<PricePoint>,
        btcAtUsOpen: List<PricePoint>,
        mstrBars: List<MstrBar>
    ): GapResult {
        val btcCloseByDay = btcAtUsClose.associateBy { dayKey(it.timestampSec) }
        val btcOpenByDay = btcAtUsOpen.associateBy { dayKey(it.timestampSec) }
        val mstrByDay = mstrBars.associateBy { dayKey(it.timestampSec) }
        val days = (btcCloseByDay.keys
            intersect btcOpenByDay.keys
            intersect mstrByDay.keys).sorted()
        if (days.size < 11) return GapResult(0.0, 0.0, 0.0, null, 0.0)

        val xs = ArrayList<Double>(days.size)
        val ys = ArrayList<Double>(days.size)
        var lastXY: Pair<Double, Double>? = null
        for (i in 1 until days.size) {
            val today = days[i]; val yest = days[i - 1]
            val btcCY = btcCloseByDay[yest]?.value ?: continue
            val btcOT = btcOpenByDay[today]?.value ?: continue
            val mY = mstrByDay[yest] ?: continue
            val mT = mstrByDay[today] ?: continue
            if (btcCY <= 0.0 || mY.close <= 0.0) continue
            val btcOv = btcOT / btcCY - 1.0
            val gap = mT.open / mY.close - 1.0
            xs.add(btcOv); ys.add(gap)
            lastXY = btcOv to gap
        }
        if (xs.size < 10) return GapResult(0.0, 0.0, 0.0, null, 0.0)

        val n = xs.size.toDouble()
        val mx = xs.average(); val my = ys.average()
        val cov = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / n
        val varX = xs.sumOf { (it - mx) * (it - mx) } / n
        val varY = ys.sumOf { (it - my) * (it - my) } / n
        val sx = sqrt(varX); val sy = sqrt(varY)
        val rho = if (sx > 0 && sy > 0) cov / (sx * sy) else 0.0
        val beta = if (varX > 0) cov / varX else 0.0
        val alpha = my - beta * mx

        val residualMS = xs.indices.sumOf {
            val r = ys[it] - (beta * xs[it] + alpha)
            r * r
        } / n
        val residualStd = sqrt(residualMS)
        val todaysSurprise = lastXY?.let { (x, y) -> y - (beta * x + alpha) }

        return GapResult(beta, alpha, rho, todaysSurprise, residualStd)
    }

    /**
     * Wilder RSI(14):
     *   first avg-gain  = sum(gains, 1..period) / period
     *   first avg-loss  = sum(losses, 1..period) / period
     *   smoothed avg-gain[t] = (avg-gain[t-1] * (period-1) + gain[t]) / period
     *   RSI = 100 − 100 / (1 + avg-gain / avg-loss)
     */
    fun rsi(values: List<Double>, period: Int = 14): List<Double?> {
        val out = MutableList<Double?>(values.size) { null }
        if (values.size <= period) return out
        var gainSum = 0.0; var lossSum = 0.0
        for (i in 1..period) {
            val ch = values[i] - values[i - 1]
            if (ch >= 0) gainSum += ch else lossSum -= ch
        }
        var avgGain = gainSum / period
        var avgLoss = lossSum / period
        out[period] = rsiFrom(avgGain, avgLoss)
        for (i in (period + 1) until values.size) {
            val ch = values[i] - values[i - 1]
            val g = if (ch > 0) ch else 0.0
            val l = if (ch < 0) -ch else 0.0
            avgGain = (avgGain * (period - 1) + g) / period
            avgLoss = (avgLoss * (period - 1) + l) / period
            out[i] = rsiFrom(avgGain, avgLoss)
        }
        return out
    }

    private fun rsiFrom(g: Double, l: Double): Double =
        if (l == 0.0) 100.0 else 100.0 - 100.0 / (1.0 + g / l)

    /**
     * Rolling Z-score with window = period.
     *   z[t] = (x[t] − μ_window) / σ_window
     */
    fun zScore(values: List<Double>, period: Int = 60): List<Double?> {
        val out = MutableList<Double?>(values.size) { null }
        if (values.size < period) return out
        for (i in (period - 1) until values.size) {
            val w = values.subList(i - period + 1, i + 1)
            val m = w.average()
            val sd = sqrt(w.sumOf { (it - m) * (it - m) } / period)
            out[i] = if (sd > 0) (values[i] - m) / sd else 0.0
        }
        return out
    }

    private fun stdev(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val m = values.average()
        return sqrt(values.sumOf { (it - m) * (it - m) } / values.size)
    }

    private fun dayKey(ts: Long): Long = ts / 86400L
}

val EmptySignalReport = SignalReport(
    verdict = Verdict.Hold,
    combinedScore = 0,
    signals = emptyList(),
    plan = TradePlan(Verdict.Hold, "FLAT", 0.0, 0.0, 0.0, 0.0, 0, 0.0, 0.0, 0.0),
    stats = SignalStats(0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, null, null)
)
