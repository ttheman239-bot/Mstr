package com.mstr.btccompare.data

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 *  Single source of truth for Barchart fetches.  No Yahoo, no third-party.
 *
 *  Auth flow:
 *    1.  GET a public quote page so the response sets `XSRF-TOKEN` (cookie).
 *    2.  Send the (URL-decoded) token back on the time-series API as
 *        `X-XSRF-TOKEN` and provide a matching `Referer`.
 *
 *  Endpoints used:
 *    EOD   /proxies/timeseries/queryeod.ashx
 *          → CSV "SYMBOL,YYYYMMDD,open,high,low,close,volume"
 *    minute /proxies/timeseries/queryminutes.ashx
 *          → CSV "SYMBOL,YYYY-MM-DDTHH:MM:SS,open,high,low,close,volume"
 *
 *  No Yahoo fallback.  If Barchart blocks the request, the caller surfaces
 *  the error and the UI shows it.
 */
class BarchartClient(private val client: OkHttpClient, private val cookieJar: CookieJar) {

    private var primed: Boolean = false

    private fun prime(symbol: String) {
        // Use the lightest dynamic page that still goes through Laravel
        // and sets XSRF-TOKEN.  /login is a small page (~30 KB) and
        // works for both stocks and crypto symbols.  Falls back to the
        // symbol-specific page only if /login doesn't yield a token.
        val candidateUrls = listOf(
            "https://www.barchart.com/login",
            if (symbol.startsWith("^"))
                "https://www.barchart.com/crypto/quotes/${symbol}/overview"
            else
                "https://www.barchart.com/stocks/quotes/${symbol}/price-history/historical"
        )
        var lastError: Throwable? = null
        for (url in candidateUrls) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", BROWSER_UA)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Cache-Control", "no-cache")
                    .build()
                client.newCall(req).execute().use { it.body?.close() }
                // Did we actually get the cookie?
                val host = HttpUrl.Builder().scheme("https").host("www.barchart.com").build()
                val hasToken = cookieJar.loadForRequest(host).any { it.name == "XSRF-TOKEN" }
                if (hasToken) {
                    primed = true
                    return
                }
            } catch (t: Throwable) {
                lastError = t
            }
        }
        if (lastError != null) throw lastError
        error("Barchart prime failed: ไม่ได้รับ XSRF-TOKEN")
    }

    private fun xsrfToken(): String {
        val barchartHost = HttpUrl.Builder().scheme("https").host("www.barchart.com").build()
        val cookies = cookieJar.loadForRequest(barchartHost)
        val raw = cookies.firstOrNull { it.name == "XSRF-TOKEN" }?.value
            ?: error("ไม่พบ XSRF-TOKEN cookie จาก Barchart (อาจถูก Cloudflare บล็อก)")
        return URLDecoder.decode(raw, "UTF-8")
    }

    /** Daily OHLC bars, oldest first. */
    fun fetchEod(symbol: String, maxRecords: Int): List<DayBar> {
        if (!primed) prime(symbol)
        val token = xsrfToken()
        val url = HttpUrl.Builder()
            .scheme("https").host("www.barchart.com")
            .addPathSegments("proxies/timeseries/queryeod.ashx")
            .addQueryParameter("symbol", symbol)
            .addQueryParameter("data", "daily")
            .addQueryParameter("maxrecords", maxRecords.toString())
            .addQueryParameter("volume", "contract")
            .addQueryParameter("order", "asc")
            .addQueryParameter("dividends", "false")
            .addQueryParameter("backadjust", "false")
            .addQueryParameter("daystoexpiration", "1")
            .addQueryParameter("contractroll", "expiration")
            .build()
        val refer = if (symbol.startsWith("^"))
            "https://www.barchart.com/crypto/quotes/$symbol/price-history/historical"
        else
            "https://www.barchart.com/stocks/quotes/$symbol/price-history/historical"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_UA)
            .header("Accept", "*/*")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Referer", refer)
            .header("X-XSRF-TOKEN", token)
            .header("X-Requested-With", "XMLHttpRequest")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Barchart EOD HTTP ${resp.code} ($symbol)")
            val body = resp.body?.string()?.trim().orEmpty()
            if (body.isEmpty()) error("Empty Barchart EOD response ($symbol)")
            val fmt = DateTimeFormatter.ofPattern("yyyyMMdd")
            val out = ArrayList<DayBar>()
            body.lineSequence().forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty()) return@forEach
                val p = line.split(",")
                if (p.size < 7) return@forEach
                val date = runCatching { LocalDate.parse(p[1], fmt) }.getOrElse { return@forEach }
                val open = p[2].toDoubleOrNull() ?: return@forEach
                val high = p[3].toDoubleOrNull() ?: return@forEach
                val low = p[4].toDoubleOrNull() ?: return@forEach
                val close = p[5].toDoubleOrNull() ?: return@forEach
                out.add(DayBar(date, open, high, low, close))
            }
            if (out.isEmpty()) error("ไม่มี bar ที่ parse ได้จาก Barchart EOD ($symbol)")
            return out.sortedBy { it.date }
        }
    }

    /**
     *  Intraday bars at the requested interval (minutes), oldest first.
     *
     *  The barchart proxy is undocumented but currently returns lines of
     *  the form  SYMBOL, ISO_DATETIME, open, high, low, close, volume
     *  in *exchange* timezone (America/New_York).
     */
    fun fetchIntraday(symbol: String, intervalMinutes: Int, maxRecords: Int): List<MinuteBar> {
        if (!primed) prime(symbol)
        val token = xsrfToken()
        val url = HttpUrl.Builder()
            .scheme("https").host("www.barchart.com")
            .addPathSegments("proxies/timeseries/queryminutes.ashx")
            .addQueryParameter("symbol", symbol)
            .addQueryParameter("interval", intervalMinutes.toString())
            .addQueryParameter("maxrecords", maxRecords.toString())
            .addQueryParameter("volume", "contract")
            .addQueryParameter("order", "asc")
            .build()
        val refer = if (symbol.startsWith("^"))
            "https://www.barchart.com/crypto/quotes/$symbol/interactive-chart"
        else
            "https://www.barchart.com/stocks/quotes/$symbol/interactive-chart"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_UA)
            .header("Accept", "*/*")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Referer", refer)
            .header("X-XSRF-TOKEN", token)
            .header("X-Requested-With", "XMLHttpRequest")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Barchart minute HTTP ${resp.code} ($symbol)")
            val body = resp.body?.string()?.trim().orEmpty()
            if (body.isEmpty()) error("Empty Barchart minute response ($symbol)")
            return parseMinuteBody(body)
        }
    }

    private fun parseMinuteBody(body: String): List<MinuteBar> {
        // Try ISO-with-T first, fallback to "yyyy-MM-dd HH:mm:ss".
        val iso = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
        val space = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val out = ArrayList<MinuteBar>()
        body.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            val p = line.split(",")
            if (p.size < 7) return@forEach
            val ts = parseTs(p[1], iso, space) ?: return@forEach
            val open = p[2].toDoubleOrNull() ?: return@forEach
            val high = p[3].toDoubleOrNull() ?: return@forEach
            val low = p[4].toDoubleOrNull() ?: return@forEach
            val close = p[5].toDoubleOrNull() ?: return@forEach
            out.add(MinuteBar(ts, open, high, low, close))
        }
        if (out.isEmpty()) error("ไม่มี bar ที่ parse ได้จาก Barchart minute")
        return out.sortedBy { it.localDateTime }
    }

    private fun parseTs(s: String, vararg fmts: DateTimeFormatter): LocalDateTime? {
        for (f in fmts) {
            runCatching { return LocalDateTime.parse(s, f) }
        }
        return null
    }

    private companion object {
        const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0.0.0 Mobile Safari/537.36"
    }
}

data class DayBar(
    val date: LocalDate,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double
)

data class MinuteBar(
    /** Local New-York wall-clock time as returned by Barchart. */
    val localDateTime: LocalDateTime,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double
)
