package com.mstr.btccompare.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

data class PricePoint(val timestampSec: Long, val value: Double)

data class CompareSeries(
    // Line-chart inputs
    val btcAtUsClose: List<PricePoint>,
    val btcAtUsOpen: List<PricePoint>,
    val btcOpenMinusClose: List<PricePoint>,
    val btcHourlyLine: List<PricePoint>,    // every hourly close — for short-period charts
    val mstrClose: List<PricePoint>,
    val mstrOpen: List<PricePoint>,
    val mstrOpenMinusClose: List<PricePoint>,
    val ratio: List<PricePoint>,

    // Latest snapshots
    val latestBtcUsClose: Double,
    val latestBtcUsOpen: Double,
    val latestMstr: Double,
    val periodDays: Int,

    // Cross-check: latest BTC spot from each independent source
    val btcLatestBinance: Double?,
    val btcLatestCoinbase: Double?,
    val btcLatestCoinGecko: Double?,
    val btcSourceUsed: String,

    // Imbalance signal output (null if engine couldn't run)
    val imbalance: ImbalanceReport?
)

class PriceRepository {

    private val nyZone: ZoneId = ZoneId.of("America/New_York")
    private val cookieJar = SimpleCookieJar()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val barchart = BarchartClient(client, cookieJar)
    private val binance = BinanceClient(client)
    private val coinGecko = CoinGeckoClient(client)
    private val coinbase = CoinbaseClient(client)

    suspend fun load(periodDays: Int = 365): CompareSeries = coroutineScope {
        val mstrJob = async(Dispatchers.IO) {
            // Barchart's prime page can be slow on Asian mobile networks
            // (~MB of HTML through Cloudflare).  Retry once on timeout.
            try {
                barchart.fetchEod("MSTR", periodDays + 30)
            } catch (t: Throwable) {
                if (t is java.net.SocketTimeoutException ||
                    t is java.io.InterruptedIOException) {
                    try { barchart.fetchEod("MSTR", periodDays + 30) }
                    catch (t2: Throwable) {
                        throw RuntimeException("MSTR (Barchart): ${t2.message ?: "ดึงไม่ได้"}")
                    }
                } else {
                    throw RuntimeException("MSTR (Barchart): ${t.message ?: "ดึงไม่ได้"}")
                }
            }
        }
        val btcHourlyJob = async(Dispatchers.IO) {
            // BTC hourly: try three independent sources in order
            //   1. Binance public klines     (full OHLC, ~80d coverage)
            //   2. Coinbase exchange candles (full OHLC, ~12d coverage)
            //   3. CoinGecko market_chart    (close only, ~90d coverage)
            // First successful source wins; the result is tagged so the
            // user can see which source supplied the historical data.
            val attempts = listOf<Pair<String, () -> List<MinuteBar>>>(
                "Binance" to { binance.fetchBtcHourly(periodDays.coerceAtMost(80)) },
                "Coinbase" to { coinbase.fetchBtcHourly(periodDays.coerceAtMost(12) * 24) },
                "CoinGecko" to { coinGecko.fetchBtcHourly(periodDays.coerceAtMost(90)) }
            )
            var picked: Pair<String, List<MinuteBar>>? = null
            for ((name, fetch) in attempts) {
                val res = runCatching { fetch() }.getOrDefault(emptyList())
                if (res.size >= 24) { picked = name to res; break }
            }
            picked ?: ("none" to emptyList())
        }
        val btcDailyJob = async(Dispatchers.IO) {
            if (periodDays > 80) {
                runCatching { binance.fetchBtcDaily(periodDays + 30) }
                    .getOrDefault(emptyList())
            } else emptyList()
        }
        // Latest spot from each source — for cross-check display only.
        val spotBinanceJob = async(Dispatchers.IO) {
            runCatching { binance.fetchBtcHourly(2).lastOrNull()?.close }.getOrNull()
        }
        val spotCoinbaseJob = async(Dispatchers.IO) {
            runCatching { coinbase.fetchSpot() }.getOrNull()
        }
        val spotCoinGeckoJob = async(Dispatchers.IO) {
            runCatching {
                coinGecko.fetchBtcHourly(2).lastOrNull()?.close
            }.getOrNull()
        }

        val mstrDays = mstrJob.await()
        val (btcSourceUsed, btcHourly) = btcHourlyJob.await()
        val btcDailyExtra = btcDailyJob.await()
        val spotBinance = spotBinanceJob.await()
        val spotCoinbase = spotCoinbaseJob.await()
        val spotCoinGecko = spotCoinGeckoJob.await()

        // ── Build per-NYSE-date BTC@9:30 / @16:00 from hourly bars when we
        //    have them; for older dates fall back to daily open / close.
        val btcByDate = LinkedHashMap<LocalDate, BtcDayPoint>()

        // (a) hourly-derived snapshots
        // For each NYSE date pick the bar whose **open time** is closest to
        // the target NY-time.  Then use bar.open (not bar.close) — for an
        // hourly bar starting at 16:00 ET, bar.open = price at 16:00 ET
        // (the actual NYSE close price); bar.close = price at 17:00 ET,
        // an hour AFTER the close, which would be wrong.
        // Tolerance: only accept a bar within ±90 min of the target.
        val tolMin = 90L
        val targetOpenMin = (9 * 60 + 30).toLong()
        val targetCloseMin = (16 * 60).toLong()
        btcHourly.groupBy { it.localDateTime.toLocalDate() }
            .forEach { (date, bars) ->
                val sorted = bars.sortedBy { it.localDateTime }

                fun pickAt(target: Long): Double? {
                    val bar = sorted.minByOrNull {
                        val mins = it.localDateTime.toLocalTime().toSecondOfDay() / 60L
                        kotlin.math.abs(mins - target)
                    } ?: return null
                    val mins = bar.localDateTime.toLocalTime().toSecondOfDay() / 60L
                    if (kotlin.math.abs(mins - target) > tolMin) return null
                    return bar.open    // open = price AT openTime
                }
                btcByDate[date] = BtcDayPoint(
                    atUsOpen = pickAt(targetOpenMin),
                    atUsClose = pickAt(targetCloseMin)
                )
            }

        // (b) for any date we don't have hourly coverage, pad with the
        //     daily CoinGecko bar (UTC midnight close used for both points).
        for (db in btcDailyExtra) {
            val date = db.localDateTime.toLocalDate()
            if (!btcByDate.containsKey(date)) {
                btcByDate[date] = BtcDayPoint(atUsOpen = db.close, atUsClose = db.close)
            }
        }

        val mstrByDate = mstrDays.associateBy { it.date }

        // ── BTC line series — every date where we got a BTC reading,
        //    irrespective of whether MSTR has data for that date too.
        val btcAtUsClose = ArrayList<PricePoint>()
        val btcAtUsOpen = ArrayList<PricePoint>()
        for ((date, b) in btcByDate.toSortedMap()) {
            val tsClose = date.atTime(16, 0).atZone(nyZone).toEpochSecond()
            val tsOpen = date.atTime(9, 30).atZone(nyZone).toEpochSecond()
            b.atUsClose?.let { btcAtUsClose.add(PricePoint(tsClose, it)) }
            b.atUsOpen?.let { btcAtUsOpen.add(PricePoint(tsOpen, it)) }
        }

        // ── MSTR line series — show **every** MSTR trading day,
        //    not just dates where BTC also has aligned data.
        val mstrAligned = mstrDays
        val mstrClose = mstrAligned.map {
            PricePoint(it.date.atTime(16, 0).atZone(nyZone).toEpochSecond(), it.close)
        }
        val mstrOpen = mstrAligned.map {
            PricePoint(it.date.atTime(9, 30).atZone(nyZone).toEpochSecond(), it.open)
        }
        val mstrOpenMinusClose = mstrAligned.map {
            PricePoint(
                it.date.atTime(16, 0).atZone(nyZone).toEpochSecond(),
                it.open - it.close
            )
        }

        // ── BTC open-minus-close (per-day diff) — only days where both
        //    open and close BTC readings exist.
        val btcOpenByDay = btcAtUsOpen.associateBy { dayKey(it.timestampSec) }
        val btcOpenMinusClose = btcAtUsClose.mapNotNull { c ->
            val o = btcOpenByDay[dayKey(c.timestampSec)]?.value ?: return@mapNotNull null
            PricePoint(c.timestampSec, c.value - o)
        }

        // ── Ratio (BTC@US-close / MSTR-close) — only days where both exist.
        val mstrCloseByDay = mstrAligned.associateBy { it.date }
        val ratio = btcAtUsClose.mapNotNull { c ->
            val date = dayKey(c.timestampSec)
            val mstrCloseValue = mstrCloseByDay[date]?.close ?: return@mapNotNull null
            if (mstrCloseValue <= 0.0) return@mapNotNull null
            PricePoint(c.timestampSec, c.value / mstrCloseValue)
        }

        // ── Hourly BTC line for short-period charts (every bar's close)
        val btcHourlyLine = btcHourly.map {
            val ts = it.localDateTime.atZone(nyZone).toEpochSecond()
            PricePoint(ts, it.close)
        }

        // ── Run imbalance engine.  Needs hourly BTC + daily MSTR.
        val nowEt = ZonedDateTime.ofInstant(Instant.now(), nyZone).toLocalDateTime()
        val imbalance = if (btcHourly.size >= 50) {
            ImbalanceEngine.analyze(btcHourly, mstrAligned, nowEt)
        } else null

        CompareSeries(
            btcAtUsClose = btcAtUsClose,
            btcAtUsOpen = btcAtUsOpen,
            btcOpenMinusClose = btcOpenMinusClose,
            btcHourlyLine = btcHourlyLine,
            mstrClose = mstrClose,
            mstrOpen = mstrOpen,
            mstrOpenMinusClose = mstrOpenMinusClose,
            ratio = ratio,
            latestBtcUsClose = btcAtUsClose.lastOrNull()?.value ?: 0.0,
            latestBtcUsOpen = btcAtUsOpen.lastOrNull()?.value ?: 0.0,
            latestMstr = mstrClose.lastOrNull()?.value ?: 0.0,
            periodDays = periodDays,
            btcLatestBinance = spotBinance,
            btcLatestCoinbase = spotCoinbase,
            btcLatestCoinGecko = spotCoinGecko,
            btcSourceUsed = btcSourceUsed,
            imbalance = imbalance
        )
    }

    private fun dayKey(epochSec: Long): LocalDate =
        ZonedDateTime.ofInstant(Instant.ofEpochSecond(epochSec), nyZone).toLocalDate()

    private data class BtcDayPoint(val atUsOpen: Double?, val atUsClose: Double?)
}

internal class SimpleCookieJar : CookieJar {
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
