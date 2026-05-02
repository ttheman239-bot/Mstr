package com.mstr.btccompare.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

data class PricePoint(val timestampSec: Long, val value: Double)

data class CompareSeries(
    val btcAtUsClose: List<PricePoint>,
    val btcAtUsOpen: List<PricePoint>,
    val btcOpenMinusClose: List<PricePoint>,
    val mstrClose: List<PricePoint>,
    val ratio: List<PricePoint>,
    val latestBtcUsClose: Double,
    val latestBtcUsOpen: Double,
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
        val btcDeferred = async(Dispatchers.IO) { fetchBtcPerDay(periodDays) }
        val mstrDeferred = async(Dispatchers.IO) { fetchMstrCloseSeries(periodDays) }
        val btc = btcDeferred.await()
        val mstr = mstrDeferred.await()

        val mstrByDay = mstr.associateBy { dayKey(it.timestampSec) }

        val btcAtUsClose = ArrayList<PricePoint>(btc.size)
        val btcAtUsOpen = ArrayList<PricePoint>(btc.size)
        val ratio = ArrayList<PricePoint>(btc.size)
        for (d in btc) {
            val mstrClose = mstrByDay[d.date] ?: continue
            val tsClose = d.date.atTime(16, 0).atZone(nyZone).toEpochSecond()
            val tsOpen = d.date.atTime(9, 30).atZone(nyZone).toEpochSecond()
            d.atUsClose?.let { btcAtUsClose.add(PricePoint(tsClose, it)) }
            d.atUsOpen?.let { btcAtUsOpen.add(PricePoint(tsOpen, it)) }
            d.atUsClose?.let { ratio.add(PricePoint(tsClose, it / mstrClose.value)) }
        }

        val btcOpenByDay = btcAtUsOpen.associateBy { dayKey(it.timestampSec) }
        val btcOpenMinusClose = btcAtUsClose.mapNotNull { closePt ->
            val openVal = btcOpenByDay[dayKey(closePt.timestampSec)]?.value
                ?: return@mapNotNull null
            PricePoint(closePt.timestampSec, closePt.value - openVal)
        }

        CompareSeries(
            btcAtUsClose = btcAtUsClose,
            btcAtUsOpen = btcAtUsOpen,
            btcOpenMinusClose = btcOpenMinusClose,
            mstrClose = mstr.filter { it.timestampSec >= (btcAtUsClose.firstOrNull()?.timestampSec ?: 0L) },
            ratio = ratio,
            latestBtcUsClose = btcAtUsClose.lastOrNull()?.value ?: 0.0,
            latestBtcUsOpen = btcAtUsOpen.lastOrNull()?.value ?: 0.0,
            latestMstr = mstr.lastOrNull()?.value ?: 0.0,
            periodDays = periodDays
        )
    }

    private fun dayKey(epochSec: Long): LocalDate =
        ZonedDateTime.ofInstant(Instant.ofEpochSecond(epochSec), nyZone).toLocalDate()

    private data class BtcDayValues(
        val date: LocalDate,
        val atUsOpen: Double?,
        val atUsClose: Double?
    )

    private fun fetchBtcPerDay(periodDays: Int): List<BtcDayValues> {
        // Yahoo limits 1h interval to ~730 days.
        val effDays = periodDays.coerceAtMost(720)
        val end = System.currentTimeMillis() / 1000L
        val start = end - effDays.toLong() * 24L * 3600L - 7L * 24L * 3600L
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/BTC-USD" +
            "?period1=$start&period2=$end&interval=1h&events=history"
        val s = fetchYahooSeries(url)

        data class Acc(var open: Double? = null, var close: Double? = null)
        val byDay = LinkedHashMap<LocalDate, Acc>()
        for (i in s.timestamp.indices) {
            val o = s.open[i] ?: continue
            val c = s.close[i] ?: continue
            val zdt = ZonedDateTime.ofInstant(Instant.ofEpochSecond(s.timestamp[i]), nyZone)
            val dow = zdt.dayOfWeek.value
            if (dow == 6 || dow == 7) continue
            val date = zdt.toLocalDate()
            val hour = zdt.hour
            val acc = byDay.getOrPut(date) { Acc() }
            // Hour 9 bar covers 09:00-10:00 ET, includes the 9:30 NYSE open
            if (hour == 9 && acc.open == null) acc.open = o
            // Hour 15 bar covers 15:00-16:00 ET, ends at the 16:00 NYSE close
            if (hour == 15) acc.close = c
        }
        return byDay.entries
            .map { (date, a) -> BtcDayValues(date, atUsOpen = a.open, atUsClose = a.close) }
            .sortedBy { it.date }
            .takeLast(periodDays)
    }

    private fun fetchMstrCloseSeries(days: Int): List<PricePoint> {
        val end = System.currentTimeMillis() / 1000L
        val start = end - days.toLong() * 24L * 3600L - 7L * 24L * 3600L
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/MSTR" +
            "?period1=$start&period2=$end&interval=1d&events=history"
        val s = fetchYahooSeries(url)
        val out = ArrayList<PricePoint>(s.timestamp.size)
        for (i in s.timestamp.indices) {
            val c = s.close[i] ?: continue
            val zdt = ZonedDateTime.ofInstant(Instant.ofEpochSecond(s.timestamp[i]), nyZone)
            val ts = zdt.toLocalDate().atTime(16, 0).atZone(nyZone).toEpochSecond()
            out.add(PricePoint(ts, c))
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
