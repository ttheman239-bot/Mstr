package com.mstr.btccompare.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

data class PricePoint(val timestampSec: Long, val value: Double)

data class CandlePoint(
    val timestampSec: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double
) {
    val isUp: Boolean get() = close >= open
}

data class CompareSeries(
    val btc: List<CandlePoint>,
    val mstr: List<CandlePoint>,
    val ratio: List<PricePoint>,
    val latestBtc: Double,
    val latestMstr: Double,
    val periodDays: Int
)

class PriceRepository {

    private val nyZone: ZoneId = ZoneId.of("America/New_York")

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun load(periodDays: Int = 365): CompareSeries = coroutineScope {
        val btcDeferred = async(Dispatchers.IO) { fetchBtcAlignedCandles(periodDays) }
        val mstrDeferred = async(Dispatchers.IO) { fetchMstrCandles(periodDays) }
        val btc = btcDeferred.await()
        val mstr = mstrDeferred.await()

        val mstrByDay = mstr.associateBy { dayKey(it.timestampSec) }
        val aligned = btc.mapNotNull { b -> mstrByDay[dayKey(b.timestampSec)]?.let { b to it } }
        val ratio = aligned.map { (b, m) ->
            PricePoint(b.timestampSec, b.close / m.close)
        }
        val alignedBtc = aligned.map { it.first }
        val alignedMstr = aligned.map { it.second }

        CompareSeries(
            btc = alignedBtc,
            mstr = alignedMstr,
            ratio = ratio,
            latestBtc = alignedBtc.lastOrNull()?.close ?: 0.0,
            latestMstr = alignedMstr.lastOrNull()?.close ?: 0.0,
            periodDays = periodDays
        )
    }

    private fun dayKey(epochSec: Long): LocalDate =
        ZonedDateTime.ofInstant(java.time.Instant.ofEpochSecond(epochSec), nyZone).toLocalDate()

    private fun fetchBtcAlignedCandles(periodDays: Int): List<CandlePoint> {
        // Yahoo limits 1h interval to ~730 days. Cap and pad start a bit.
        val effDays = periodDays.coerceAtMost(720)
        val end = System.currentTimeMillis() / 1000L
        val start = end - effDays.toLong() * 24L * 3600L - 7L * 24L * 3600L
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/BTC-USD" +
            "?period1=$start&period2=$end&interval=1h&events=history"
        val (timestamps, opens, highs, lows, closes) = fetchYahooSeries(url)

        // Group hourly bars by NY trading date and pick:
        //  - the bar whose ET hour == 9  -> open at US market open (9:30 ET)
        //  - the bar whose ET hour == 15 -> session close hour ending 16:00 ET
        // Track session high/low across 9-15 ET inclusive.
        data class Acc(
            var openVal: Double? = null,
            var closeVal: Double? = null,
            var high: Double = Double.NEGATIVE_INFINITY,
            var low: Double = Double.POSITIVE_INFINITY,
            var closeTs: Long = 0L
        )
        val byDay = LinkedHashMap<LocalDate, Acc>()
        for (i in timestamps.indices) {
            val ts = timestamps[i]
            val o = opens[i]; val h = highs[i]; val l = lows[i]; val c = closes[i]
            if (o == null || h == null || l == null || c == null) continue
            val zdt = ZonedDateTime.ofInstant(java.time.Instant.ofEpochSecond(ts), nyZone)
            val date = zdt.toLocalDate()
            val hour = zdt.hour
            if (hour !in 9..15) continue
            // Skip weekends (NYSE closed)
            val dow = zdt.dayOfWeek.value
            if (dow == 6 || dow == 7) continue
            val acc = byDay.getOrPut(date) { Acc() }
            if (h > acc.high) acc.high = h
            if (l < acc.low) acc.low = l
            if (hour == 9 && acc.openVal == null) acc.openVal = o
            if (hour == 15) {
                acc.closeVal = c
                acc.closeTs = ts
            }
        }
        return byDay.entries.mapNotNull { (date, a) ->
            val openV = a.openVal ?: return@mapNotNull null
            val closeV = a.closeVal ?: return@mapNotNull null
            // Use the day's NY-midday timestamp as the canonical x value.
            val ts = date.atTime(12, 0).atZone(nyZone).toEpochSecond()
            CandlePoint(
                timestampSec = ts,
                open = openV,
                high = if (a.high.isInfinite()) openV else a.high,
                low = if (a.low.isInfinite()) openV else a.low,
                close = closeV
            )
        }.sortedBy { it.timestampSec }
            .takeLast(periodDays)
    }

    private fun fetchMstrCandles(days: Int): List<CandlePoint> {
        val end = System.currentTimeMillis() / 1000L
        val start = end - days.toLong() * 24L * 3600L - 7L * 24L * 3600L
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/MSTR" +
            "?period1=$start&period2=$end&interval=1d&events=history"
        val (timestamps, opens, highs, lows, closes) = fetchYahooSeries(url)
        val out = ArrayList<CandlePoint>(timestamps.size)
        for (i in timestamps.indices) {
            val o = opens[i] ?: continue
            val h = highs[i] ?: continue
            val l = lows[i] ?: continue
            val c = closes[i] ?: continue
            // Normalize timestamp to NY 12:00 of the trading day for clean alignment
            val zdt = ZonedDateTime.ofInstant(java.time.Instant.ofEpochSecond(timestamps[i]), nyZone)
            val ts = zdt.toLocalDate().atTime(12, 0).atZone(nyZone).toEpochSecond()
            out.add(CandlePoint(ts, o, h, l, c))
        }
        return out
    }

    private data class Series(
        val timestamp: List<Long>,
        val open: List<Double?>,
        val high: List<Double?>,
        val low: List<Double?>,
        val close: List<Double?>
    )

    private fun fetchYahooSeries(url: String): Series {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "Mozilla/5.0 (Android) MstrBtc/1.0")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Yahoo HTTP ${resp.code}")
            val body = resp.body?.string() ?: error("Empty Yahoo body")
            val root = JSONObject(body)
            val chart = root.getJSONObject("chart")
            val result = chart.optJSONArray("result")
                ?: error("Yahoo returned no result")
            if (result.length() == 0) error("Empty Yahoo result")
            val first = result.getJSONObject(0)
            val tsArr = first.getJSONArray("timestamp")
            val q = first.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0)
            val openArr = q.getJSONArray("open")
            val highArr = q.getJSONArray("high")
            val lowArr = q.getJSONArray("low")
            val closeArr = q.getJSONArray("close")
            val ts = ArrayList<Long>(tsArr.length())
            val op = ArrayList<Double?>(tsArr.length())
            val hi = ArrayList<Double?>(tsArr.length())
            val lo = ArrayList<Double?>(tsArr.length())
            val cl = ArrayList<Double?>(tsArr.length())
            for (i in 0 until tsArr.length()) {
                ts.add(tsArr.getLong(i))
                op.add(if (openArr.isNull(i)) null else openArr.getDouble(i))
                hi.add(if (highArr.isNull(i)) null else highArr.getDouble(i))
                lo.add(if (lowArr.isNull(i)) null else lowArr.getDouble(i))
                cl.add(if (closeArr.isNull(i)) null else closeArr.getDouble(i))
            }
            return Series(ts, op, hi, lo, cl)
        }
    }
}
