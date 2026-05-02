package com.mstr.btccompare.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLDecoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
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
    val mstrSource: String,
    val periodDays: Int,
    val analysis: Analysis
)

class PriceRepository {

    private val nyZone: ZoneId = ZoneId.of("America/New_York")

    private val cookieJar = SimpleCookieJar()

    private val client = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun load(periodDays: Int = 365): CompareSeries = coroutineScope {
        val btcDeferred = async(Dispatchers.IO) { fetchBtcPerDay(periodDays) }
        val mstrDeferred = async(Dispatchers.IO) { fetchMstrCloseSeries(periodDays) }
        val btc = btcDeferred.await()
        val mstrPair = mstrDeferred.await()
        val mstr = mstrPair.first
        val mstrSource = mstrPair.second

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

        val mstrAligned = mstr.filter { it.timestampSec >= (btcAtUsClose.firstOrNull()?.timestampSec ?: 0L) }
        val tentative = CompareSeries(
            btcAtUsClose = btcAtUsClose,
            btcAtUsOpen = btcAtUsOpen,
            btcOpenMinusClose = btcOpenMinusClose,
            mstrClose = mstrAligned,
            ratio = ratio,
            latestBtcUsClose = btcAtUsClose.lastOrNull()?.value ?: 0.0,
            latestBtcUsOpen = btcAtUsOpen.lastOrNull()?.value ?: 0.0,
            latestMstr = mstr.lastOrNull()?.value ?: 0.0,
            mstrSource = mstrSource,
            periodDays = periodDays,
            analysis = EmptyAnalysis
        )
        tentative.copy(analysis = Analyzer.analyze(tentative))
    }

    private fun dayKey(epochSec: Long): LocalDate =
        ZonedDateTime.ofInstant(Instant.ofEpochSecond(epochSec), nyZone).toLocalDate()

    private data class BtcDayValues(
        val date: LocalDate,
        val atUsOpen: Double?,
        val atUsClose: Double?
    )

    private fun fetchBtcPerDay(periodDays: Int): List<BtcDayValues> {
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
            if (hour == 9 && acc.open == null) acc.open = o
            if (hour == 15) acc.close = c
        }
        return byDay.entries
            .map { (date, a) -> BtcDayValues(date, atUsOpen = a.open, atUsClose = a.close) }
            .sortedBy { it.date }
            .takeLast(periodDays)
    }

    /** Returns (series, sourceName) and falls back to Yahoo if Barchart fails. */
    private fun fetchMstrCloseSeries(days: Int): Pair<List<PricePoint>, String> {
        return try {
            fetchMstrFromBarchart(days) to "Barchart"
        } catch (t: Throwable) {
            fetchMstrFromYahoo(days) to "Yahoo (fallback)"
        }
    }

    private fun fetchMstrFromBarchart(days: Int): List<PricePoint> {
        // 1. Prime cookies by hitting the public quote page.
        val primeUrl = "https://www.barchart.com/stocks/quotes/MSTR/price-history/historical"
        val primeReq = Request.Builder()
            .url(primeUrl)
            .header("User-Agent", BROWSER_UA)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        client.newCall(primeReq).execute().use { it.body?.close() }

        // 2. Pull XSRF-TOKEN cookie value.
        val barchartHost = HttpUrl.Builder().scheme("https").host("www.barchart.com").build()
        val cookies = cookieJar.loadForRequest(barchartHost)
        val xsrfRaw = cookies.firstOrNull { it.name == "XSRF-TOKEN" }?.value
            ?: error("No XSRF-TOKEN cookie from Barchart")
        val xsrfToken = URLDecoder.decode(xsrfRaw, "UTF-8")

        // 3. Pull EOD time series. maxrecords includes weekends/holidays, so request a bit more.
        val maxRecords = (days + 30).coerceAtMost(2200)
        val apiUrl = "https://www.barchart.com/proxies/timeseries/queryeod.ashx" +
            "?symbol=MSTR&data=daily&maxrecords=$maxRecords" +
            "&volume=contract&order=asc&dividends=false&backadjust=false" +
            "&daystoexpiration=1&contractroll=expiration"
        val req = Request.Builder()
            .url(apiUrl)
            .header("User-Agent", BROWSER_UA)
            .header("Accept", "*/*")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Referer", primeUrl)
            .header("X-XSRF-TOKEN", xsrfToken)
            .header("X-Requested-With", "XMLHttpRequest")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Barchart HTTP ${resp.code}")
            val body = resp.body?.string()?.trim().orEmpty()
            if (body.isEmpty()) error("Empty Barchart body")
            // Format per row: SYMBOL,YYYYMMDD,open,high,low,close,volume
            val fmt = DateTimeFormatter.ofPattern("yyyyMMdd")
            val out = ArrayList<PricePoint>()
            body.lineSequence().forEach { rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty()) return@forEach
                val parts = line.split(",")
                if (parts.size < 7) return@forEach
                val close = parts[5].toDoubleOrNull() ?: return@forEach
                val date = try {
                    LocalDate.parse(parts[1], fmt)
                } catch (e: Exception) {
                    return@forEach
                }
                val ts = date.atTime(16, 0).atZone(nyZone).toEpochSecond()
                out.add(PricePoint(ts, close))
            }
            if (out.isEmpty()) error("No usable Barchart rows")
            return out.sortedBy { it.timestampSec }.takeLast(days)
        }
    }

    private fun fetchMstrFromYahoo(days: Int): List<PricePoint> {
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

    private companion object {
        const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0.0.0 Mobile Safari/537.36"
    }
}

private class SimpleCookieJar : CookieJar {
    private val store = mutableMapOf<String, MutableList<Cookie>>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val list = store.getOrPut(url.host) { mutableListOf() }
        cookies.forEach { incoming ->
            list.removeAll { it.name == incoming.name }
            list.add(incoming)
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        // Return cookies for exact host or parent domain.
        val now = System.currentTimeMillis()
        val out = ArrayList<Cookie>()
        store.forEach { (host, cookies) ->
            if (url.host == host || url.host.endsWith(".$host")) {
                cookies.removeAll { it.expiresAt < now }
                out.addAll(cookies)
            }
        }
        return out
    }
}
