package com.mstr.btccompare.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 *  BTC price source — CoinGecko free API (no key, no Yahoo).
 *
 *  Endpoint:
 *    /api/v3/coins/bitcoin/market_chart?vs_currency=usd&days=N&interval=hourly
 *
 *  CoinGecko returns close-only prices in UTC ms.  We convert each
 *  timestamp to America/New_York and emit a `MinuteBar` with O=H=L=C
 *  so the existing imbalance engine can consume the same shape it
 *  used to consume from Barchart.
 *
 *  Free-tier hourly granularity is supported up to ~90 days.  Longer
 *  ranges fall back to daily automatically on CoinGecko's side.
 */
class CoinGeckoClient(private val client: OkHttpClient) {

    private val nyZone: ZoneId = ZoneId.of("America/New_York")

    fun fetchBtcHourly(days: Int): List<MinuteBar> {
        val capped = days.coerceAtMost(90).coerceAtLeast(2)
        val url = "https://api.coingecko.com/api/v3/coins/bitcoin/market_chart" +
            "?vs_currency=usd&days=$capped&interval=hourly"
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "MstrBtcAndroid/1.0")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("CoinGecko HTTP ${resp.code}")
            val body = resp.body?.string() ?: error("Empty CoinGecko body")
            val json = JSONObject(body)
            val prices = json.optJSONArray("prices")
                ?: error("CoinGecko: prices array missing")
            val out = ArrayList<MinuteBar>(prices.length())
            for (i in 0 until prices.length()) {
                val pair = prices.getJSONArray(i)
                val tsMs = pair.getLong(0)
                val price = pair.getDouble(1)
                val ldt = ZonedDateTime
                    .ofInstant(Instant.ofEpochMilli(tsMs), nyZone)
                    .toLocalDateTime()
                out.add(MinuteBar(ldt, price, price, price, price))
            }
            if (out.isEmpty()) error("CoinGecko: empty hourly series")
            return out
        }
    }

    /**
     *  Daily-granularity series for longer chart ranges (>90 days).
     *  Single price per day at UTC midnight.
     */
    fun fetchBtcDaily(days: Int): List<MinuteBar> {
        val capped = days.coerceAtLeast(2)
        val url = "https://api.coingecko.com/api/v3/coins/bitcoin/market_chart" +
            "?vs_currency=usd&days=$capped&interval=daily"
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "MstrBtcAndroid/1.0")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("CoinGecko (daily) HTTP ${resp.code}")
            val body = resp.body?.string() ?: error("Empty CoinGecko (daily) body")
            val json = JSONObject(body)
            val prices = json.optJSONArray("prices") ?: return emptyList()
            val out = ArrayList<MinuteBar>(prices.length())
            for (i in 0 until prices.length()) {
                val pair = prices.getJSONArray(i)
                val tsMs = pair.getLong(0)
                val price = pair.getDouble(1)
                val ldt = ZonedDateTime
                    .ofInstant(Instant.ofEpochMilli(tsMs), nyZone)
                    .toLocalDateTime()
                out.add(MinuteBar(ldt, price, price, price, price))
            }
            return out
        }
    }
}
