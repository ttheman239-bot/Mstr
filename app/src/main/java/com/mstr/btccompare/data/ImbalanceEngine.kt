package com.mstr.btccompare.data

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.abs
import kotlin.math.sqrt

// ════════════════════════════════════════════════════════════════════════════
//  ImbalanceEngine — day-trade signal that exploits the
//  asymmetry  "BTC trades 24/7, MSTR only during NYSE 9:30–16:00 ET".
//
//  Mispricing accumulates in three windows.  Each window produces an
//  independent score; the sum gives the overall verdict.
//
//   ① PROJECTED GAP  (forward-looking, only useful while MSTR is closed)
//      BTC has moved since MSTR last closed.  How much should MSTR
//      gap on the next open?
//        gap_expected = β · (BTC_now / BTC_at_last_NYSE_close − 1) + α
//        where β, α come from OLS over the past N days:
//            mstr_gap = β · btc_overnight + α
//        sigmas = gap_expected / σ_overnight_residuals
//
//   ② GAP RECONCILIATION  (backward-looking, fades over the day)
//      Did the most recent MSTR open correctly price the *previous*
//      overnight BTC move?
//        residual = mstr_gap_actual − (β · btc_overnight + α)
//        sigmas   = residual / σ_overnight_residuals
//      negative → MSTR underreacted → catch-up long bias
//      positive → MSTR overreacted  → fade short bias
//
//   ③ INTRADAY DRIFT  (only completed once NYSE has closed)
//      During NYSE hours both move together.  Did MSTR track BTC
//      with its usual β?
//        residual_intra = mstr_intraday − (β_i · btc_intraday + α_i)
//        sigmas         = residual_intra / σ_intraday_residuals
//      negative → MSTR lagged BTC during the day → catch-up long bias
//      positive → MSTR overextended            → fade short bias
//
//  All three are summed.  → Verdict.fromScore.  Trade plan uses ATR(14)
//  for stop and Van-Tharp 2 % position sizing.
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

enum class MarketWindow { Closed, RegularSession, AfterHours }

data class ImbalanceComponent(
    val title: String,
    val displayValue: String,
    val sigmaUnits: Double?,
    val score: Int,
    val explanation: String,
    val active: Boolean
)

data class TradePlan(
    val verdict: Verdict,
    val direction: String,
    val entry: Double,
    val stop: Double,
    val target1: Double,
    val target2: Double,
    val shares: Int,
    val notional: Double,
    val riskAmount: Double,
    val accountSize: Double
)

data class ImbalanceStats(
    val sampleSize: Int,
    val gapBeta: Double,
    val gapAlpha: Double,
    val gapRho: Double,
    val gapResidualStd: Double,
    val intradayBeta: Double,
    val intradayAlpha: Double,
    val intradayRho: Double,
    val intradayResidualStd: Double,
    val atr14: Double,
    val mstrLastClose: Double,
    val btcAtLastMstrClose: Double,
    val btcLatest: Double,
    val btcLatestAgeMin: Long,
    val window: MarketWindow,
    val expectedNextOpen: Double,
    val expectedGapPct: Double
)

data class ImbalanceReport(
    val verdict: Verdict,
    val combinedScore: Int,
    val direction: String,
    val components: List<ImbalanceComponent>,
    val plan: TradePlan,
    val stats: ImbalanceStats,
    val headline: String
)

object ImbalanceEngine {

    private const val DEFAULT_ACCOUNT = 10_000.0
    private const val RISK_PCT = 0.02
    private const val ATR_STOP_MULT = 1.5

    fun analyze(
        btcHourly: List<MinuteBar>,
        mstrDaily: List<DayBar>,
        nowEt: LocalDateTime,
        accountSize: Double = DEFAULT_ACCOUNT
    ): ImbalanceReport? {
        if (mstrDaily.size < 30) return null
        if (btcHourly.size < 50) return null

        // ── 1.  Resample BTC hourly bars → per-date snapshots at 9:30 / 16:00 ET ──
        val btcByDate = btcHourly.groupBy { it.localDateTime.toLocalDate() }
        val btcSnapshots = LinkedHashMap<LocalDate, BtcSnapshot>()
        for ((date, barsToday) in btcByDate) {
            val sorted = barsToday.sortedBy { it.localDateTime }
            val nineThirty = nearestBar(sorted, LocalTime.of(9, 30))
            val sixteen = nearestBar(sorted, LocalTime.of(16, 0))
            btcSnapshots[date] = BtcSnapshot(
                atUsOpen = nineThirty?.open,
                atUsClose = sixteen?.close
            )
        }

        // ── 2.  Latest BTC mark (most recent hourly bar of any kind) ──
        val latestBtcBar = btcHourly.last()
        val latestBtcPrice = latestBtcBar.close
        val latestBtcAt = latestBtcBar.localDateTime
        val latestBtcAgeMin = Duration.between(latestBtcAt, nowEt).toMinutes()

        // ── 3.  Pair MSTR daily with BTC snapshots ──
        val mstrByDate = mstrDaily.associateBy { it.date }
        val pairedDates = (btcSnapshots.keys intersect mstrByDate.keys).sorted()
        if (pairedDates.size < 25) return null

        // ── 4.  Build (overnight) regression points ──
        val gapXs = ArrayList<Double>()
        val gapYs = ArrayList<Double>()
        val intraXs = ArrayList<Double>()
        val intraYs = ArrayList<Double>()
        for (i in 1 until pairedDates.size) {
            val prev = pairedDates[i - 1]; val today = pairedDates[i]
            val btcSnapPrev = btcSnapshots[prev]!!
            val btcSnapToday = btcSnapshots[today]!!
            val mstrPrev = mstrByDate[prev]!!
            val mstrToday = mstrByDate[today]!!
            val btcPrevClose = btcSnapPrev.atUsClose
            val btcOpenToday = btcSnapToday.atUsOpen
            val btcCloseToday = btcSnapToday.atUsClose
            if (btcPrevClose != null && btcOpenToday != null && mstrPrev.close > 0) {
                val btcOvernight = btcOpenToday / btcPrevClose - 1.0
                val mstrGap = mstrToday.open / mstrPrev.close - 1.0
                gapXs.add(btcOvernight); gapYs.add(mstrGap)
            }
            if (btcOpenToday != null && btcCloseToday != null && mstrToday.open > 0) {
                val btcIntra = btcCloseToday / btcOpenToday - 1.0
                val mstrIntra = mstrToday.close / mstrToday.open - 1.0
                intraXs.add(btcIntra); intraYs.add(mstrIntra)
            }
        }
        if (gapXs.size < 15 || intraXs.size < 15) return null

        val gap = ols(gapXs, gapYs)
        val intra = ols(intraXs, intraYs)

        // ── 5.  Component ① PROJECTED GAP (forward) ──
        val lastMstr = mstrDaily.last()
        val lastBtcAtMstrClose = btcSnapshots[lastMstr.date]?.atUsClose
        val (compA, expectedGapPct, expectedNextOpen) = projectedGap(
            lastMstr = lastMstr,
            btcAtLastMstrClose = lastBtcAtMstrClose,
            btcNow = latestBtcPrice,
            beta = gap.beta, alpha = gap.alpha,
            sigma = gap.residualStd,
            window = classifyWindow(nowEt)
        )

        // ── 6.  Component ② GAP RECONCILIATION (backward) ──
        val compB = gapReconciliation(
            pairedDates = pairedDates,
            btcSnapshots = btcSnapshots,
            mstrByDate = mstrByDate,
            beta = gap.beta, alpha = gap.alpha, sigma = gap.residualStd
        )

        // ── 7.  Component ③ INTRADAY DRIFT (backward, only after RTH close) ──
        val compC = intradayDrift(
            lastMstr = lastMstr,
            btcSnap = btcSnapshots[lastMstr.date],
            beta = intra.beta, alpha = intra.alpha, sigma = intra.residualStd
        )

        val components = listOf(compA, compB, compC)
        val combined = components.sumOf { it.score }
        val verdict = Verdict.fromScore(combined)

        // ── 8.  ATR(14) and trade plan ──
        val atr = atr14(mstrDaily)
        val direction = when {
            verdict.isBuy -> "LONG MSTR"
            verdict.isSell -> "SHORT MSTR"
            else -> "FLAT — รอสัญญาณชัดกว่านี้"
        }
        val plan = buildPlan(verdict, direction, lastMstr.close, atr, accountSize)

        val window = classifyWindow(nowEt)
        val stats = ImbalanceStats(
            sampleSize = pairedDates.size,
            gapBeta = gap.beta,
            gapAlpha = gap.alpha,
            gapRho = gap.rho,
            gapResidualStd = gap.residualStd,
            intradayBeta = intra.beta,
            intradayAlpha = intra.alpha,
            intradayRho = intra.rho,
            intradayResidualStd = intra.residualStd,
            atr14 = atr,
            mstrLastClose = lastMstr.close,
            btcAtLastMstrClose = lastBtcAtMstrClose ?: 0.0,
            btcLatest = latestBtcPrice,
            btcLatestAgeMin = latestBtcAgeMin,
            window = window,
            expectedNextOpen = expectedNextOpen,
            expectedGapPct = expectedGapPct
        )
        val headline = buildHeadline(window, expectedGapPct, expectedNextOpen, lastMstr.close)
        return ImbalanceReport(verdict, combined, direction, components, plan, stats, headline)
    }

    // ── Components ──────────────────────────────────────────────────────

    private fun projectedGap(
        lastMstr: DayBar,
        btcAtLastMstrClose: Double?,
        btcNow: Double,
        beta: Double, alpha: Double, sigma: Double,
        window: MarketWindow
    ): Triple<ImbalanceComponent, Double, Double> {
        if (btcAtLastMstrClose == null || btcAtLastMstrClose <= 0.0) {
            val c = ImbalanceComponent(
                title = "Projected gap",
                displayValue = "—",
                sigmaUnits = null,
                score = 0,
                explanation = "ไม่มีราคา BTC ตอน MSTR ปิดล่าสุด",
                active = false
            )
            return Triple(c, 0.0, lastMstr.close)
        }
        val btcDrift = btcNow / btcAtLastMstrClose - 1.0
        val expGapPct = beta * btcDrift + alpha
        val expOpen = lastMstr.close * (1.0 + expGapPct)
        val sigmas = if (sigma > 0) expGapPct / sigma else 0.0
        val score = when {
            sigmas > 1.5 -> 2
            sigmas > 0.7 -> 1
            sigmas < -1.5 -> -2
            sigmas < -0.7 -> -1
            else -> 0
        }
        val active = window != MarketWindow.RegularSession
        val display = "BTC %+.2f%% → คาด open %+.2f%% (%+.1fσ)".format(
            btcDrift * 100, expGapPct * 100, sigmas
        )
        val expl = if (active)
            "BTC ขยับ ${"%+.2f".format(btcDrift * 100)}%% ตั้งแต่ MSTR ปิด → คาดเปิด \$${"%.2f".format(expOpen)} (gap ${"%+.2f".format(expGapPct * 100)}%%)"
        else
            "ตลาดเปิดอยู่ — ใช้สำหรับช่วง pre-market / after-hours"
        return Triple(
            ImbalanceComponent("Projected gap", display, sigmas, if (active) score else 0, expl, active),
            expGapPct, expOpen
        )
    }

    private fun gapReconciliation(
        pairedDates: List<LocalDate>,
        btcSnapshots: Map<LocalDate, BtcSnapshot>,
        mstrByDate: Map<LocalDate, DayBar>,
        beta: Double, alpha: Double, sigma: Double
    ): ImbalanceComponent {
        if (pairedDates.size < 2 || sigma <= 0.0) return inactive("Gap reconciliation", "ข้อมูลไม่พอ")
        val today = pairedDates.last(); val prev = pairedDates[pairedDates.size - 2]
        val btcOpenT = btcSnapshots[today]?.atUsOpen
        val btcCloseY = btcSnapshots[prev]?.atUsClose
        val mstrT = mstrByDate[today]; val mstrY = mstrByDate[prev]
        if (btcOpenT == null || btcCloseY == null || mstrT == null || mstrY == null) {
            return inactive("Gap reconciliation", "ข้อมูล alignment ไม่ครบ")
        }
        val btcOv = btcOpenT / btcCloseY - 1.0
        val mstrGap = mstrT.open / mstrY.close - 1.0
        val expected = beta * btcOv + alpha
        val residual = mstrGap - expected
        val sigmas = residual / sigma
        val score = when {
            sigmas < -1.5 -> 2
            sigmas < -0.7 -> 1
            sigmas > 1.5 -> -2
            sigmas > 0.7 -> -1
            else -> 0
        }
        val display = "actual %+.2f%%, exp %+.2f%% (residual %+.1fσ)".format(
            mstrGap * 100, expected * 100, sigmas
        )
        val expl = when {
            sigmas < -0.7 -> "MSTR เปิดต่ำกว่าที่ควร → catch-up long bias"
            sigmas > 0.7 -> "MSTR เปิดสูงกว่าที่ควร → fade short bias"
            else -> "Gap วันนี้ใกล้ regression ปกติ"
        }
        return ImbalanceComponent("Gap reconciliation", display, sigmas, score, expl, active = true)
    }

    private fun intradayDrift(
        lastMstr: DayBar,
        btcSnap: BtcSnapshot?,
        beta: Double, alpha: Double, sigma: Double
    ): ImbalanceComponent {
        if (btcSnap?.atUsOpen == null || btcSnap.atUsClose == null || sigma <= 0.0) {
            return inactive("Intraday drift", "ยังไม่มีข้อมูล intraday วันนี้")
        }
        if (lastMstr.open <= 0.0) return inactive("Intraday drift", "—")
        val btcIntra = btcSnap.atUsClose / btcSnap.atUsOpen - 1.0
        val mstrIntra = lastMstr.close / lastMstr.open - 1.0
        val expected = beta * btcIntra + alpha
        val residual = mstrIntra - expected
        val sigmas = residual / sigma
        val score = when {
            sigmas < -1.5 -> 2
            sigmas < -0.7 -> 1
            sigmas > 1.5 -> -2
            sigmas > 0.7 -> -1
            else -> 0
        }
        val display = "actual %+.2f%%, exp %+.2f%% (residual %+.1fσ)".format(
            mstrIntra * 100, expected * 100, sigmas
        )
        val expl = when {
            sigmas < -0.7 -> "MSTR ทำงานต่ำกว่า BTC วันนี้ → catch-up long bias"
            sigmas > 0.7 -> "MSTR ทำงานเกิน BTC วันนี้ → fade short bias"
            else -> "Intraday tracking ปกติ"
        }
        return ImbalanceComponent("Intraday drift", display, sigmas, score, expl, active = true)
    }

    private fun inactive(title: String, why: String) =
        ImbalanceComponent(title, "—", null, 0, why, active = false)

    // ── Math helpers ────────────────────────────────────────────────────

    private data class BtcSnapshot(val atUsOpen: Double?, val atUsClose: Double?)

    private fun nearestBar(sortedBars: List<MinuteBar>, target: LocalTime): MinuteBar? {
        if (sortedBars.isEmpty()) return null
        val targetMinutes = target.toSecondOfDay() / 60
        var best: MinuteBar? = null
        var bestDist = Long.MAX_VALUE
        for (b in sortedBars) {
            val mins = b.localDateTime.toLocalTime().toSecondOfDay() / 60L
            val d = abs(mins - targetMinutes)
            if (d < bestDist) {
                bestDist = d
                best = b
            }
        }
        // Reject if no bar within 90 min of target (means data gap that day).
        return if (bestDist <= 90) best else null
    }

    private data class OlsResult(
        val beta: Double, val alpha: Double, val rho: Double, val residualStd: Double
    )

    /** Ordinary least squares.  Inputs already cleaned. */
    private fun ols(xs: List<Double>, ys: List<Double>): OlsResult {
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
            val r = ys[it] - (beta * xs[it] + alpha); r * r
        } / n
        return OlsResult(beta, alpha, rho, sqrt(residualMS))
    }

    /** ATR(14) using true range = max(H-L, |H-prev_C|, |L-prev_C|). */
    private fun atr14(bars: List<DayBar>): Double {
        if (bars.size < 15) return 0.0
        val trs = ArrayList<Double>(bars.size - 1)
        for (i in 1 until bars.size) {
            val b = bars[i]; val pc = bars[i - 1].close
            trs.add(maxOf(b.high - b.low, abs(b.high - pc), abs(b.low - pc)))
        }
        return trs.takeLast(14).average()
    }

    private fun classifyWindow(nowEt: LocalDateTime): MarketWindow {
        val t = nowEt.toLocalTime(); val dow = nowEt.dayOfWeek.value
        if (dow == 6 || dow == 7) return MarketWindow.Closed
        return when {
            t.isBefore(LocalTime.of(9, 30)) -> MarketWindow.Closed
            t.isBefore(LocalTime.of(16, 0)) -> MarketWindow.RegularSession
            else -> MarketWindow.AfterHours
        }
    }

    private fun buildPlan(
        verdict: Verdict, direction: String, price: Double, atr: Double, account: Double
    ): TradePlan {
        if (price <= 0.0 || atr <= 0.0) return TradePlan(
            verdict, "FLAT — ข้อมูลไม่พอ", price, 0.0, 0.0, 0.0, 0, 0.0, 0.0, account
        )
        val stopDist = atr * ATR_STOP_MULT
        val risk = account * RISK_PCT
        val shares = (risk / stopDist).toInt().coerceAtLeast(0)
        val notional = shares * price
        return when {
            verdict.isBuy -> TradePlan(
                verdict, direction,
                entry = price,
                stop = price - stopDist,
                target1 = price + stopDist,
                target2 = price + stopDist * 2,
                shares = shares, notional = notional,
                riskAmount = risk, accountSize = account
            )
            verdict.isSell -> TradePlan(
                verdict, direction,
                entry = price,
                stop = price + stopDist,
                target1 = price - stopDist,
                target2 = price - stopDist * 2,
                shares = shares, notional = notional,
                riskAmount = risk, accountSize = account
            )
            else -> TradePlan(
                verdict, direction,
                price, 0.0, 0.0, 0.0, 0, 0.0, 0.0, account
            )
        }
    }

    private fun buildHeadline(
        window: MarketWindow, expectedGapPct: Double, expectedOpen: Double, lastClose: Double
    ): String = when (window) {
        MarketWindow.RegularSession ->
            "ตลาดเปิดอยู่ — ใช้ Intraday drift + Gap reconciliation เป็นหลัก"
        MarketWindow.Closed, MarketWindow.AfterHours ->
            "MSTR ปิดล่าสุด \$${"%.2f".format(lastClose)} • ถ้าเปิดตอนนี้ คาด \$${"%.2f".format(expectedOpen)} (${"%+.2f".format(expectedGapPct * 100)}%%)"
    }
}
