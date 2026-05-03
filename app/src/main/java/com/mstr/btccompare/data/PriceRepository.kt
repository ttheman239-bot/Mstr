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
    val mstrClose: List<PricePoint>,
    val mstrOpen: List<PricePoint>,
    val mstrOpenMinusClose: List<PricePoint>,
    val ratio: List<PricePoint>,

    // Latest snapshots
    val latestBtcUsClose: Double,
    val latestBtcUsOpen: Double,
    val latestMstr: Double,
    val periodDays: Int,

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
            // Primary: Binance public klines (no rate-limit, full OHLC).
            // Fallback: CoinGecko hourly close-only.  Last-resort: empty.
            try {
                binance.fetchBtcHourly(periodDays.coerceAtMost(80))
            } catch (binErr: Throwable) {
                runCatching {
                    coinGecko.fetchBtcHourly(periodDays.coerceAtMost(90))
                }.getOrDefault(emptyList())
            }
        }
        val btcDailyJob = async(Dispatchers.IO) {
            if (periodDays > 80) {
                runCatching { binance.fetchBtcDaily(periodDays + 30) }
                    .getOrDefault(emptyList())
            } else emptyList()
        }

        val mstrDays = mstrJob.await()
        val btcHourly = btcHourlyJob.await()
        val btcDailyExtra = btcDailyJob.await()

        // ── Build per-NYSE-date BTC@9:30 / @16:00 from hourly bars when we
        //    have them; for older dates fall back to daily open / close.
        val btcByDate = LinkedHashMap<LocalDate, BtcDayPoint>()

        // (a) hourly-derived snapshots
        btcHourly.groupBy { it.localDateTime.toLocalDate() }
            .forEach { (date, bars) ->
                val sorted = bars.sortedBy { it.localDateTime }
                val nearOpen = sorted.minByOrNull {
                    val mins = it.localDateTime.toLocalTime().toSecondOfDay() / 60
                    kotlin.math.abs(mins - (9 * 60 + 30))
                }
                val nearClose = sorted.minByOrNull {
                    val mins = it.localDateTime.toLocalTime().toSecondOfDay() / 60
                    kotlin.math.abs(mins - 16 * 60)
                }
                btcByDate[date] = BtcDayPoint(
                    atUsOpen = nearOpen?.open,
                    atUsClose = nearClose?.close
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

        // ── Cross-align with MSTR trading days
        val mstrByDate = mstrDays.associateBy { it.date }
        val tradingDates = (btcByDate.keys intersect mstrByDate.keys).sorted()

        val btcAtUsClose = ArrayList<PricePoint>()
        val btcAtUsOpen = ArrayList<PricePoint>()
        val ratio = ArrayList<PricePoint>()
        for (date in tradingDates) {
            val b = btcByDate[date] ?: continue
            val tsClose = date.atTime(16, 0).atZone(nyZone).toEpochSecond()
            val tsOpen = date.atTime(9, 30).atZone(nyZone).toEpochSecond()
            b.atUsClose?.let { btcAtUsClose.add(PricePoint(tsClose, it)) }
            b.atUsOpen?.let { btcAtUsOpen.add(PricePoint(tsOpen, it)) }
            val mstrClose = mstrByDate[date]?.close ?: 0.0
            if (mstrClose > 0.0 && b.atUsClose != null) {
                ratio.add(PricePoint(tsClose, b.atUsClose / mstrClose))
            }
        }

        val btcOpenByDay = btcAtUsOpen.associateBy { dayKey(it.timestampSec) }
        val btcOpenMinusClose = btcAtUsClose.mapNotNull { c ->
            val o = btcOpenByDay[dayKey(c.timestampSec)]?.value ?: return@mapNotNull null
            PricePoint(c.timestampSec, c.value - o)
        }

        val mstrAligned = mstrDays.filter { tradingDates.contains(it.date) }
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

        // ── Run imbalance engine.  Needs hourly BTC + daily MSTR.
        val nowEt = ZonedDateTime.ofInstant(Instant.now(), nyZone).toLocalDateTime()
        val imbalance = if (btcHourly.size >= 50) {
            ImbalanceEngine.analyze(btcHourly, mstrAligned, nowEt)
        } else null

        CompareSeries(
            btcAtUsClose = btcAtUsClose,
            btcAtUsOpen = btcAtUsOpen,
            btcOpenMinusClose = btcOpenMinusClose,
            mstrClose = mstrClose,
            mstrOpen = mstrOpen,
            mstrOpenMinusClose = mstrOpenMinusClose,
            ratio = ratio,
            latestBtcUsClose = btcAtUsClose.lastOrNull()?.value ?: 0.0,
            latestBtcUsOpen = btcAtUsOpen.lastOrNull()?.value ?: 0.0,
            latestMstr = mstrClose.lastOrNull()?.value ?: 0.0,
            periodDays = periodDays,
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
