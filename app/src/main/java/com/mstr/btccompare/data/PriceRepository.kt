package com.mstr.btccompare.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class PricePoint(val timestampSec: Long, val value: Double)

data class CompareSeries(
    val btc: List<PricePoint>,
    val mstr: List<PricePoint>,
    val ratio: List<PricePoint>,
    val latestBtc: Double,
    val latestMstr: Double,
    val periodDays: Int
)

class PriceRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun load(periodDays: Int = 365): CompareSeries = withContext(Dispatchers.IO) {
        val btc = fetchBtc(periodDays)
        val mstr = fetchMstr(periodDays)

        val aligned = alignByDay(btc, mstr)
        val ratio = aligned.map { (ts, pair) ->
            PricePoint(ts, pair.first / pair.second)
        }

        CompareSeries(
            btc = btc,
            mstr = mstr,
            ratio = ratio,
            latestBtc = btc.lastOrNull()?.value ?: 0.0,
            latestMstr = mstr.lastOrNull()?.value ?: 0.0,
            periodDays = periodDays
        )
    }

    private fun fetchBtc(days: Int): List<PricePoint> {
        val url = "https://api.coingecko.com/api/v3/coins/bitcoin/market_chart?vs_currency=usd&days=$days&interval=daily"
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "MstrBtcAndroid/1.0")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("CoinGecko HTTP ${resp.code}")
            val body = resp.body?.string() ?: error("Empty CoinGecko body")
            val json = JSONObject(body)
            val prices: JSONArray = json.getJSONArray("prices")
            val points = ArrayList<PricePoint>(prices.length())
            for (i in 0 until prices.length()) {
                val p = prices.getJSONArray(i)
                val ts = p.getLong(0) / 1000L
                val v = p.getDouble(1)
                points.add(PricePoint(ts, v))
            }
            return points
        }
    }

    private fun fetchMstr(days: Int): List<PricePoint> {
        val end = System.currentTimeMillis() / 1000L
        val start = end - days.toLong() * 24L * 3600L
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/MSTR" +
            "?period1=$start&period2=$end&interval=1d&events=history"
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
            val ts = first.getJSONArray("timestamp")
            val closes = first.getJSONObject("indicators")
                .getJSONArray("quote").getJSONObject(0)
                .getJSONArray("close")
            val points = ArrayList<PricePoint>(ts.length())
            for (i in 0 until ts.length()) {
                if (closes.isNull(i)) continue
                points.add(PricePoint(ts.getLong(i), closes.getDouble(i)))
            }
            return points
        }
    }

    private fun alignByDay(
        a: List<PricePoint>,
        b: List<PricePoint>
    ): List<Pair<Long, Pair<Double, Double>>> {
        val dayOf: (Long) -> Long = { it / 86400L }
        val mapA = a.associateBy { dayOf(it.timestampSec) }
        val mapB = b.associateBy { dayOf(it.timestampSec) }
        val days = (mapA.keys intersect mapB.keys).sorted()
        return days.map { d ->
            val ts = d * 86400L
            ts to (mapA.getValue(d).value to mapB.getValue(d).value)
        }
    }
}
