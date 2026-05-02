package com.mstr.btccompare.data

import kotlin.math.abs
import kotlin.math.sqrt

// ──────────────────────────────────────────────────────────────────────────
//  mNAV mean-reversion
// ──────────────────────────────────────────────────────────────────────────

data class MNavStats(
    val current: Double,
    val mean: Double,
    val stdev: Double,
    val zScore: Double,
    val rsi: Double?,
    val verdict: Verdict,
    val entryLong: Double,    // mean - 1.5 σ
    val entryShort: Double,   // mean + 1.5 σ
    val message: String
)

data class MNavResult(
    val series: List<PricePoint>,
    val stats: MNavStats
)

object MNavAnalyzer {

    fun compute(
        btcAtUsClose: List<PricePoint>,
        mstrClose: List<PricePoint>,
        btcHoldings: Long = MstrFundamentals.btcHoldings,
        sharesOutstanding: Long = MstrFundamentals.sharesOutstanding
    ): MNavResult {
        // Index MSTR by date for alignment.
        val mstrByTs = mstrClose.associateBy { it.timestampSec }
        val series = btcAtUsClose.mapNotNull { btc ->
            val mstr = mstrByTs[btc.timestampSec] ?: return@mapNotNull null
            val btcValue = btc.value * btcHoldings
            if (btcValue <= 0.0) return@mapNotNull null
            val mcap = mstr.value * sharesOutstanding
            PricePoint(btc.timestampSec, mcap / btcValue)
        }
        return MNavResult(series, statsOf(series))
    }

    private fun statsOf(series: List<PricePoint>): MNavStats {
        if (series.size < 5) {
            return MNavStats(
                current = series.lastOrNull()?.value ?: 0.0,
                mean = 0.0, stdev = 0.0, zScore = 0.0, rsi = null,
                verdict = Verdict.Neutral,
                entryLong = 0.0, entryShort = 0.0,
                message = "ข้อมูลน้อยเกินไปสำหรับ mNAV"
            )
        }
        val values = series.map { it.value }
        val current = values.last()
        val mean = values.average()
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        val sd = sqrt(variance)
        val z = if (sd > 0) (current - mean) / sd else 0.0
        val rsi = Indicators.rsi(values, 14).lastOrNull()

        val absoluteLow = current < 1.5
        val absoluteHigh = current > 3.0
        val rsiOversold = rsi != null && rsi < 30.0
        val rsiOverbought = rsi != null && rsi > 70.0

        val verdict = when {
            absoluteLow && rsiOversold -> Verdict.StrongBuy
            absoluteHigh && rsiOverbought -> Verdict.StrongSell
            z < -1.5 -> Verdict.Buy
            z > 1.5 -> Verdict.Sell
            z < -0.7 -> Verdict.Buy
            z > 0.7 -> Verdict.Sell
            else -> Verdict.Neutral
        }

        val msg = when (verdict) {
            Verdict.StrongBuy ->
                "mNAV %.2f < 1.5 และ RSI %.0f < 30 → MSTR ถูกผิดปกติ (LONG entry)".format(current, rsi ?: 0.0)
            Verdict.Buy ->
                "mNAV %.2f Z=%.2f ต่ำกว่าค่าเฉลี่ย → mean-revert long".format(current, z)
            Verdict.StrongSell ->
                "mNAV %.2f > 3.0 และ RSI %.0f > 70 → MSTR แพงผิดปกติ (SHORT entry)".format(current, rsi ?: 0.0)
            Verdict.Sell ->
                "mNAV %.2f Z=%.2f สูงกว่าค่าเฉลี่ย → mean-revert short".format(current, z)
            else -> "mNAV %.2f อยู่ในช่วงปกติ Z=%.2f".format(current, z)
        }

        return MNavStats(
            current = current,
            mean = mean,
            stdev = sd,
            zScore = z,
            rsi = rsi,
            verdict = verdict,
            entryLong = mean - 1.5 * sd,
            entryShort = mean + 1.5 * sd,
            message = msg
        )
    }
}

// ──────────────────────────────────────────────────────────────────────────
//  Lead-Lag (BTC overnight  →  MSTR open gap  →  EOD)
// ──────────────────────────────────────────────────────────────────────────

data class LeadLagStats(
    val sampleSize: Int,
    val correlation: Double,             // Pearson, BTC overnight ↔ MSTR gap
    val regressionSlope: Double,         // best-fit beta (MSTR gap = β·BTC + α)
    val regressionIntercept: Double,
    val gapFadeRate: Double,             // share of large-gap days that closed back through the open
    val todaysBtcOvernight: Double?,     // pct, e.g. 0.03 = +3 %
    val todaysMstrGap: Double?,
    val expectedMstrGap: Double?,        // β·todaysBtc + α
    val message: String
)

object LeadLagAnalyzer {

    fun compute(
        btcAtUsClose: List<PricePoint>,
        btcAtUsOpen: List<PricePoint>,
        mstrClose: List<PricePoint>,
        mstrOpen: List<PricePoint>
    ): LeadLagStats {
        val btcCloseByDay = btcAtUsClose.associateBy { dayKey(it.timestampSec) }
        val btcOpenByDay = btcAtUsOpen.associateBy { dayKey(it.timestampSec) }
        val mstrCloseByDay = mstrClose.associateBy { dayKey(it.timestampSec) }
        val mstrOpenByDay = mstrOpen.associateBy { dayKey(it.timestampSec) }

        val days = (mstrCloseByDay.keys
            intersect btcCloseByDay.keys
            intersect mstrOpenByDay.keys
            intersect btcOpenByDay.keys)
            .sorted()
        if (days.size < 11) return empty("คู่ข้อมูลน้อยเกินไป (${days.size} วัน)")

        val pairs = ArrayList<Pair<Double, Double>>(days.size)
        var todaysBtcOv: Double? = null
        var todaysMstrGap: Double? = null
        var fadeWins = 0
        var fadeTrials = 0

        for (i in 1 until days.size) {
            val today = days[i]
            val yest = days[i - 1]
            val btcCloseY = btcCloseByDay[yest]?.value ?: continue
            val mstrCloseY = mstrCloseByDay[yest]?.value ?: continue
            val btcOpenT = btcOpenByDay[today]?.value ?: continue
            val mstrOpenT = mstrOpenByDay[today]?.value ?: continue
            val mstrCloseT = mstrCloseByDay[today]?.value ?: continue
            if (btcCloseY <= 0.0 || mstrCloseY <= 0.0) continue

            val btcOvernight = (btcOpenT / btcCloseY) - 1.0
            val mstrGap = (mstrOpenT / mstrCloseY) - 1.0
            pairs.add(btcOvernight to mstrGap)
            todaysBtcOv = btcOvernight
            todaysMstrGap = mstrGap

            if (kotlin.math.abs(btcOvernight) >= 0.015) {
                val gapAbs = mstrOpenT - mstrCloseY
                val intra = mstrCloseT - mstrOpenT
                fadeTrials++
                if (gapAbs * intra < 0) fadeWins++
            }
        }

        if (pairs.size < 10) return empty("คู่ข้อมูลน้อยเกินไป (${pairs.size} วัน)")

        val xs = pairs.map { it.first }
        val ys = pairs.map { it.second }
        val mx = xs.average()
        val my = ys.average()
        val cov = pairs.sumOf { (it.first - mx) * (it.second - my) } / pairs.size
        val sx = sqrt(xs.sumOf { (it - mx) * (it - mx) } / pairs.size)
        val sy = sqrt(ys.sumOf { (it - my) * (it - my) } / pairs.size)
        val correlation = if (sx > 0 && sy > 0) cov / (sx * sy) else 0.0
        val slope = if (sx > 0) cov / (sx * sx) else 0.0
        val intercept = my - slope * mx
        val fadeRate = if (fadeTrials > 0) fadeWins.toDouble() / fadeTrials else 0.0
        val expected = todaysBtcOv?.let { slope * it + intercept }

        return LeadLagStats(
            sampleSize = pairs.size,
            correlation = correlation,
            regressionSlope = slope,
            regressionIntercept = intercept,
            gapFadeRate = fadeRate,
            todaysBtcOvernight = todaysBtcOv,
            todaysMstrGap = todaysMstrGap,
            expectedMstrGap = expected,
            message = buildMessage(correlation, slope, fadeRate, todaysBtcOv, todaysMstrGap, expected)
        )
    }

    private fun dayKey(epochSec: Long): Long = epochSec / 86400L

    private fun buildMessage(
        rho: Double, slope: Double, fade: Double,
        btcOv: Double?, mstrGap: Double?, expected: Double?
    ): String {
        val parts = mutableListOf<String>()
        parts.add("β=%.2f, ρ=%.2f".format(slope, rho))
        if (btcOv != null && expected != null) {
            parts.add("BTC overnight %+.2f%% → MSTR gap คาด %+.2f%%".format(btcOv * 100, expected * 100))
        }
        if (mstrGap != null) parts.add("จริง %+.2f%%".format(mstrGap * 100))
        parts.add("Fade-rate (|BTC|>1.5%%) = %.0f%%".format(fade * 100))
        return parts.joinToString(" • ")
    }

    private fun empty(msg: String) = LeadLagStats(
        sampleSize = 0, correlation = 0.0,
        regressionSlope = 0.0, regressionIntercept = 0.0,
        gapFadeRate = 0.0,
        todaysBtcOvernight = null, todaysMstrGap = null, expectedMstrGap = null,
        message = msg
    )
}

// ──────────────────────────────────────────────────────────────────────────
//  Risk / Position sizing
// ──────────────────────────────────────────────────────────────────────────

data class RiskParams(
    val price: Double,
    val atr: Double,
    val stopDistance: Double,           // 1.5 × ATR
    val accountSize: Double,
    val maxRiskPerTrade: Double,        // dollar risk
    val suggestedShares: Int,
    val notionalAtSize: Double,         // shares × price
    val dailyLossLimit: Double,         // dollar limit
    val message: String
)

object RiskCalculator {

    /** Daily-true-range proxy ATR. We have open + close for MSTR but no high/low. */
    fun compute(
        mstrClose: List<PricePoint>,
        mstrOpen: List<PricePoint>,
        accountSize: Double = MstrFundamentals.defaultAccountSize
    ): RiskParams {
        if (mstrClose.size < 15) {
            return RiskParams(
                price = mstrClose.lastOrNull()?.value ?: 0.0,
                atr = 0.0, stopDistance = 0.0,
                accountSize = accountSize,
                maxRiskPerTrade = accountSize * MstrFundamentals.maxRiskPerTrade,
                suggestedShares = 0,
                notionalAtSize = 0.0,
                dailyLossLimit = accountSize * MstrFundamentals.dailyLossLimit,
                message = "ข้อมูลไม่พอสำหรับ ATR(14)"
            )
        }
        val openByTs = mstrOpen.associateBy { it.timestampSec }
        val ranges = ArrayList<Double>(mstrClose.size)
        for (i in 1 until mstrClose.size) {
            val close = mstrClose[i].value
            val prevClose = mstrClose[i - 1].value
            val openToday = openByTs[mstrClose[i].timestampSec]?.value ?: close
            val tr = maxOf(
                abs(close - openToday),
                abs(close - prevClose),
                abs(openToday - prevClose)
            )
            ranges.add(tr)
        }
        val atr = ranges.takeLast(14).average()
        val stop = atr * 1.5
        val price = mstrClose.last().value
        val maxRisk = accountSize * MstrFundamentals.maxRiskPerTrade
        val shares = if (stop > 0.0) (maxRisk / stop).toInt() else 0
        val notional = shares * price
        val msg = "ATR(14) = $%.2f → stop $%.2f → ที่บัญชี $%.0f ใช้ %d shares ($%.0f notional)"
            .format(atr, stop, accountSize, shares, notional)

        return RiskParams(
            price = price,
            atr = atr,
            stopDistance = stop,
            accountSize = accountSize,
            maxRiskPerTrade = maxRisk,
            suggestedShares = shares,
            notionalAtSize = notional,
            dailyLossLimit = accountSize * MstrFundamentals.dailyLossLimit,
            message = msg
        )
    }
}

// ──────────────────────────────────────────────────────────────────────────
//  Combined trade plan
// ──────────────────────────────────────────────────────────────────────────

data class TradePlan(
    val verdict: Verdict,
    val direction: String,    // LONG MSTR / SHORT MSTR / FLAT
    val entryPrice: Double,
    val stopPrice: Double,
    val target1Price: Double,
    val target2Price: Double,
    val rationale: String
)

data class TradingSystem(
    val mnav: MNavResult,
    val leadLag: LeadLagStats,
    val risk: RiskParams,
    val plan: TradePlan
)

val EmptyTradingSystem = TradingSystem(
    mnav = MNavResult(
        series = emptyList(),
        stats = MNavStats(0.0, 0.0, 0.0, 0.0, null, Verdict.Neutral, 0.0, 0.0, "")
    ),
    leadLag = LeadLagStats(
        sampleSize = 0, correlation = 0.0,
        regressionSlope = 0.0, regressionIntercept = 0.0,
        gapFadeRate = 0.0,
        todaysBtcOvernight = null, todaysMstrGap = null, expectedMstrGap = null,
        message = ""
    ),
    risk = RiskParams(0.0, 0.0, 0.0, 0.0, 0.0, 0, 0.0, 0.0, ""),
    plan = TradePlan(Verdict.Neutral, "FLAT", 0.0, 0.0, 0.0, 0.0, "")
)

object TradingSystemBuilder {

    fun build(
        btcAtUsClose: List<PricePoint>,
        btcAtUsOpen: List<PricePoint>,
        mstrClose: List<PricePoint>,
        mstrOpen: List<PricePoint>,
        analysis: Analysis
    ): TradingSystem {
        val mnav = MNavAnalyzer.compute(btcAtUsClose, mstrClose)
        val leadLag = LeadLagAnalyzer.compute(btcAtUsClose, btcAtUsOpen, mstrClose, mstrOpen)
        val risk = RiskCalculator.compute(mstrClose, mstrOpen)
        val plan = buildPlan(mnav.stats, analysis, risk)
        return TradingSystem(mnav, leadLag, risk, plan)
    }

    private fun buildPlan(
        mnav: MNavStats,
        analysis: Analysis,
        risk: RiskParams
    ): TradePlan {
        val combined = scoreFor(mnav.verdict) + scoreFor(analysis.mstr.verdict) + scoreFor(analysis.ratio.verdict)
        val verdict = Verdict.fromScore(combined)
        val price = risk.price
        val (direction, entry, stop, t1, t2) = when {
            verdict.isBuy -> {
                val entry = price
                val stop = (price - risk.stopDistance).coerceAtLeast(0.0)
                val t1 = price + risk.stopDistance      // 1R
                val t2 = price + risk.stopDistance * 2  // 2R
                Quintet("LONG MSTR", entry, stop, t1, t2)
            }
            verdict.isSell -> {
                val entry = price
                val stop = price + risk.stopDistance
                val t1 = price - risk.stopDistance
                val t2 = price - risk.stopDistance * 2
                Quintet("SHORT MSTR", entry, stop, t1, t2)
            }
            else -> Quintet("FLAT — ไม่มีสัญญาณชัด", price, 0.0, 0.0, 0.0)
        }
        val rationale = listOfNotNull(
            "mNAV: ${mnav.verdict.label}",
            "Trend: ${analysis.mstr.verdict.label}",
            "Ratio: ${analysis.ratio.verdict.label}"
        ).joinToString(" • ") + "  →  combined %+d".format(combined)
        return TradePlan(verdict, direction, entry, stop, t1, t2, rationale)
    }

    private fun scoreFor(v: Verdict): Int = when (v) {
        Verdict.StrongBuy -> 2
        Verdict.Buy -> 1
        Verdict.Sell -> -1
        Verdict.StrongSell -> -2
        else -> 0
    }

    private data class Quintet(
        val direction: String,
        val entry: Double,
        val stop: Double,
        val t1: Double,
        val t2: Double
    )
}
