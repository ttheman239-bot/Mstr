package com.mstr.btccompare.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 *  BTC price source — Binance public klines API.
 *
 *  No API key, no Cloudflare, much higher rate limit than CoinGecko,
 *  and crucially returns full OHLC per bar (not close-only).
 *
 *  Endpoint:
 *    GET https://api.binance.com/api/v3/klines?symbol=BTCUSDT&interval=1h&limit=1000
 *
 *  Response: JSON array of arrays
 *    [ openTimeMs, open, high, low, close, volume, closeTimeMs, ... ]
 *  Numeric fields are returned as strings (Binance convention) so we
 *  parse them via toDouble().
 *
 *  Limits:
 *    interval=1h, limit=1000  → ~41 days of hourly bars
 *    interval=1d, limit=1000  → ~3 years of daily bars
 *  We make at most one extra page-back request to cover ~80 days of
 *  hourly history when the user picks a longer window.
 */
class BinanceClient(private val client: OkHttpClient) {

    private val nyZone: ZoneId = ZoneId.of("America/New_York")

    /**
     *  Fetch hourly bars covering at least `requestedDays` calendar days.
     *  Caps at ~80 days because Binance's max page is 1000 (≈41 days)
     *  and we only ever page back once.
     */
    fun fetchBtcHourly(requestedDays: Int): List<MinuteBar> {
        // First page: most recent 1000 hours (~41 days).
        val first = fetchKlinesRaw(interval = "1h", limit = 1000, endTime = null)
        if (requestedDays <= 41 || first.isEmpty()) {
            return first.map { it.toMinuteBar(nyZone) }
        }
        // Second page: 1000 hours ending where the first one started.
        val firstOpenMs = first.first().openTimeMs
        val second = runCatching {
            fetchKlinesRaw(interval = "1h", limit = 1000, endTime = firstOpenMs - 1)
        }.getOrDefault(emptyList())
        // Drop overlap (shouldn't be any with the endTime cut-off, but be safe)
        return (second + first)
            .distinctBy { it.openTimeMs }
            .sortedBy { it.openTimeMs }
            .map { it.toMinuteBar(nyZone) }
    }

    /** Daily-granularity series, used to extend the chart for >90-day periods. */
    fun fetchBtcDaily(days: Int): List<MinuteBar> {
        val limit = days.coerceAtMost(1000).coerceAtLeast(2)
        return fetchKlinesRaw(interval = "1d", limit = limit, endTime = null)
            .map { it.toMinuteBar(nyZone) }
    }

    // ── plumbing ─────────────────────────────────────────────────────────

    private fun fetchKlinesRaw(interval: String, limit: Int, endTime: Long?): List<RawKline> {
        // Try the official host first, then the public read-only mirror.
        // The mirror is sometimes faster from Asia.
        val hosts = listOf("api.binance.com", "data-api.binance.vision")
        var lastErr: String = ""
        for ((idx, host) in hosts.withIndex()) {
            val builder = StringBuilder("https://").append(host).append("/api/v3/klines")
                .append("?symbol=BTCUSDT&interval=").append(interval)
                .append("&limit=").append(limit)
            if (endTime != null) builder.append("&endTime=").append(endTime)
            val req = Request.Builder()
                .url(builder.toString())
                .header("Accept", "application/json")
                .header("User-Agent", "MstrBtcAndroid/1.0")
                .build()
            try {
                client.newCall(req).execute().use { resp ->
                    if (resp.code == 429 || resp.code in 500..599) {
                        lastErr = "$host HTTP ${resp.code}"
                        return@use
                    }
                    if (!resp.isSuccessful) {
                        lastErr = "$host HTTP ${resp.code}"
                        return@use
                    }
                    val body = resp.body?.string() ?: run {
                        lastErr = "$host empty body"; return@use
                    }
                    return parseKlines(body)
                }
            } catch (t: Throwable) {
                lastErr = "$host ${t.message ?: "error"}"
                if (idx == hosts.lastIndex) throw RuntimeException("Binance: $lastErr", t)
            }
        }
        error("Binance: $lastErr")
    }

    private fun parseKlines(body: String): List<RawKline> {
        val arr = JSONArray(body)
        val out = ArrayList<RawKline>(arr.length())
        for (i in 0 until arr.length()) {
            val k = arr.getJSONArray(i)
            val openTimeMs = k.getLong(0)
            val open = k.getString(1).toDouble()
            val high = k.getString(2).toDouble()
            val low = k.getString(3).toDouble()
            val close = k.getString(4).toDouble()
            out.add(RawKline(openTimeMs, open, high, low, close))
        }
        return out
    }

    private data class RawKline(
        val openTimeMs: Long,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double
    ) {
        fun toMinuteBar(nyZone: ZoneId): MinuteBar {
            val ldt = ZonedDateTime
                .ofInstant(Instant.ofEpochMilli(openTimeMs), nyZone)
                .toLocalDateTime()
            return MinuteBar(ldt, open, high, low, close)
        }
    }
}
