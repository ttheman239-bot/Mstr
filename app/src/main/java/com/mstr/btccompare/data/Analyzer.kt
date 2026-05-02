package com.mstr.btccompare.data

enum class Verdict(val label: String) {
    StrongBuy("STRONG BUY"),
    Buy("BUY"),
    Neutral("HOLD"),
    Sell("SELL"),
    StrongSell("STRONG SELL");

    val isBuy: Boolean get() = this == Buy || this == StrongBuy
    val isSell: Boolean get() = this == Sell || this == StrongSell

    companion object {
        fun fromScore(s: Int): Verdict = when {
            s >= 3 -> StrongBuy
            s >= 1 -> Buy
            s <= -3 -> StrongSell
            s <= -1 -> Sell
            else -> Neutral
        }
    }
}

data class TickerSignal(
    val price: Double,
    val rsi: Double?,
    val sma20: Double?,
    val sma50: Double?,
    val score: Int,
    val verdict: Verdict,
    val reasons: List<String>
)

data class RatioAnalysis(
    val z: Double?,
    val mean: Double?,
    val verdict: Verdict,
    val reason: String
)

data class TradeMarker(
    val timestampSec: Long,
    val price: Double,
    val isBuy: Boolean
)

data class Analysis(
    val mstr: TickerSignal,
    val btc: TickerSignal,
    val ratio: RatioAnalysis,
    val mstrMarkers: List<TradeMarker>,
    val combinedScore: Int,
    val recommendation: String
)

val EmptyAnalysis = Analysis(
    mstr = TickerSignal(0.0, null, null, null, 0, Verdict.Neutral, emptyList()),
    btc = TickerSignal(0.0, null, null, null, 0, Verdict.Neutral, emptyList()),
    ratio = RatioAnalysis(null, null, Verdict.Neutral, ""),
    mstrMarkers = emptyList(),
    combinedScore = 0,
    recommendation = ""
)

object Analyzer {

    fun analyze(series: CompareSeries): Analysis {
        val mstrPrices = series.mstrClose.map { it.value }
        val btcPrices = series.btcAtUsClose.map { it.value }
        val ratioVals = series.ratio.map { it.value }

        val mstrSig = tickerSignal(mstrPrices)
        val btcSig = tickerSignal(btcPrices)
        val ratioSig = ratioAnalysis(ratioVals)
        val markers = crossoverMarkers(series.mstrClose, mstrPrices)

        val combined = mstrSig.score + when (ratioSig.verdict) {
            Verdict.StrongBuy -> 2
            Verdict.Buy -> 1
            Verdict.Sell -> -1
            Verdict.StrongSell -> -2
            else -> 0
        }
        val recom = when {
            combined >= 4 -> "เข้าซื้อ MSTR — สัญญาณรวมแข็งแกร่งมาก"
            combined >= 2 -> "เข้าซื้อ MSTR — สัญญาณรวมเป็นบวก"
            combined >= 1 -> "เริ่มสะสม MSTR ทีละเล็กน้อย"
            combined <= -4 -> "ขาย/ตัดขาดทุน MSTR — สัญญาณรวมเป็นลบมาก"
            combined <= -2 -> "ลดน้ำหนัก MSTR — สัญญาณรวมเป็นลบ"
            combined <= -1 -> "ระวัง MSTR — สัญญาณอ่อนตัว"
            else -> "ถือ/รอดูก่อน — สัญญาณยังไม่ชัดเจน"
        }
        return Analysis(mstrSig, btcSig, ratioSig, markers, combined, recom)
    }

    private fun tickerSignal(prices: List<Double>): TickerSignal {
        if (prices.isEmpty()) {
            return TickerSignal(0.0, null, null, null, 0, Verdict.Neutral, listOf("ไม่มีข้อมูล"))
        }
        val rsi = Indicators.rsi(prices, 14).lastOrNull()
        val sma20 = Indicators.sma(prices, 20).lastOrNull()
        val sma50 = Indicators.sma(prices, 50).lastOrNull()
        val last = prices.last()

        var score = 0
        val reasons = mutableListOf<String>()

        if (rsi != null) {
            when {
                rsi < 30.0 -> { score += 2; reasons.add("RSI %.0f < 30 → oversold (+2)".format(rsi)) }
                rsi < 45.0 -> { score += 1; reasons.add("RSI %.0f ต่ำ → mild buy (+1)".format(rsi)) }
                rsi > 70.0 -> { score -= 2; reasons.add("RSI %.0f > 70 → overbought (-2)".format(rsi)) }
                rsi > 55.0 -> { score -= 1; reasons.add("RSI %.0f สูง → mild sell (-1)".format(rsi)) }
                else -> reasons.add("RSI %.0f neutral".format(rsi))
            }
        } else reasons.add("RSI ไม่พอข้อมูล")

        if (sma20 != null && sma50 != null) {
            if (sma20 > sma50) {
                score += 1
                reasons.add("SMA20 > SMA50 → uptrend (+1)")
            } else {
                score -= 1
                reasons.add("SMA20 < SMA50 → downtrend (-1)")
            }
        }
        if (sma20 != null) {
            if (last > sma20) {
                score += 1
                reasons.add("ราคา > SMA20 → momentum (+1)")
            } else {
                score -= 1
                reasons.add("ราคา < SMA20 → weak (-1)")
            }
        }
        return TickerSignal(last, rsi, sma20, sma50, score, Verdict.fromScore(score), reasons)
    }

    private fun ratioAnalysis(ratioVals: List<Double>): RatioAnalysis {
        if (ratioVals.size < 60) {
            return RatioAnalysis(null, null, Verdict.Neutral, "ข้อมูล ratio ไม่พอ (ต้อง ≥ 60 วัน)")
        }
        val z = Indicators.zScore(ratioVals, 60).lastOrNull()
            ?: return RatioAnalysis(null, null, Verdict.Neutral, "")
        val mean = ratioVals.takeLast(60).average()
        // High ratio = BTC expensive vs MSTR price → MSTR cheap → buy MSTR
        val verdict = when {
            z > 1.5 -> Verdict.StrongBuy
            z > 0.7 -> Verdict.Buy
            z < -1.5 -> Verdict.StrongSell
            z < -0.7 -> Verdict.Sell
            else -> Verdict.Neutral
        }
        val reason = when {
            z > 1.5 -> "Z=%.2f สูงผิดปกติ → MSTR ถูกเทียบ BTC (mean reverts → buy)".format(z)
            z > 0.7 -> "Z=%.2f สูงกว่าปกติ → MSTR ค่อนข้างถูก".format(z)
            z < -1.5 -> "Z=%.2f ต่ำผิดปกติ → MSTR แพงเทียบ BTC (mean reverts → sell)".format(z)
            z < -0.7 -> "Z=%.2f ต่ำกว่าปกติ → MSTR ค่อนข้างแพง".format(z)
            else -> "Z=%.2f อยู่ในช่วงปกติ".format(z)
        }
        return RatioAnalysis(z, mean, verdict, reason)
    }

    private fun crossoverMarkers(points: List<PricePoint>, prices: List<Double>): List<TradeMarker> {
        if (points.size < 50) return emptyList()
        val sma20 = Indicators.sma(prices, 20)
        val sma50 = Indicators.sma(prices, 50)
        val out = mutableListOf<TradeMarker>()
        for (i in 1 until points.size) {
            val a20 = sma20[i] ?: continue
            val a50 = sma50[i] ?: continue
            val p20 = sma20[i - 1] ?: continue
            val p50 = sma50[i - 1] ?: continue
            if (p20 <= p50 && a20 > a50) {
                out.add(TradeMarker(points[i].timestampSec, prices[i], isBuy = true))
            } else if (p20 >= p50 && a20 < a50) {
                out.add(TradeMarker(points[i].timestampSec, prices[i], isBuy = false))
            }
        }
        return out
    }
}
