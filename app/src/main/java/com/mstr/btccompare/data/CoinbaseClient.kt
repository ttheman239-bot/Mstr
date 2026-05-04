package com.mstr.btccompare.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 *  Coinbase exchange BTC-USD source.  Used as a third independent feed
 *  for cross-checking the Binance / CoinGecko price.
 *
 *  Two endpoints:
 *    /v2/prices/BTC-USD/spot           latest spot price (no auth)
 *    /products/BTC-USD/candles         hourly OHLC, max 300 candles/call
 */
class CoinbaseClient(private val client: OkHttpClient) {

    private val nyZone: ZoneId = ZoneId.of("America/New_York")

    /** Latest BTC-USD spot price.  Returns null on any failure. */
    fun fetchSpot(): Double? = runCatching {
        val req = Request.Builder()
            .url("https://api.coinbase.com/v2/prices/BTC-USD/spot")
            .header("Accept", "application/json")
            .header("User-Agent", "MstrBtcAndroid/1.0")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val body = resp.body?.string() ?: return@use null
            val data = JSONObject(body).optJSONObject("data") ?: return@use null
            data.optString("amount").toDoubleOrNull()
        }
    }.getOrNull()

    /**
     *  Hourly BTC-USD bars from Coinbase exchange (max 300 per call).
     *  Returns oldest first.  Each bar has open/high/low/close.
     */
    fun fetchBtcHourly(maxBars: Int = 300): List<MinuteBar> {
        val limit = maxBars.coerceAtMost(300).coerceAtLeast(2)
        val nowSec = System.currentTimeMillis() / 1000L
        val startSec = nowSec - limit.toLong() * 3600L
        val url = "https://api.exchange.coinbase.com/products/BTC-USD/candles" +
            "?granularity=3600" +
            "&start=" + Instant.ofEpochSecond(startSec).toString() +
            "&end=" + Instant.ofEpochSecond(nowSec).toString()
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "MstrBtcAndroid/1.0")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Coinbase HTTP ${resp.code}")
            val body = resp.body?.string() ?: error("Coinbase: empty body")
            val arr = JSONArray(body)
            // Coinbase returns NEWEST first.  Each row: [time, low, high, open, close, volume]
            val out = ArrayList<MinuteBar>(arr.length())
            for (i in 0 until arr.length()) {
                val k = arr.getJSONArray(i)
                val tsSec = k.getLong(0)
                val low = k.getDouble(1)
                val high = k.getDouble(2)
                val open = k.getDouble(3)
                val close = k.getDouble(4)
                val ldt = ZonedDateTime
                    .ofInstant(Instant.ofEpochSecond(tsSec), nyZone)
                    .toLocalDateTime()
                out.add(MinuteBar(ldt, open, high, low, close))
            }
            return out.sortedBy { it.localDateTime }
        }
    }
}
