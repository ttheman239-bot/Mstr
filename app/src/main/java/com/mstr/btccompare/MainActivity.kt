package com.mstr.btccompare

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mstr.btccompare.data.ImbalanceComponent
import com.mstr.btccompare.data.ImbalanceReport
import com.mstr.btccompare.data.ImbalanceStats
import com.mstr.btccompare.data.MarketWindow
import com.mstr.btccompare.data.PricePoint
import com.mstr.btccompare.data.TradePlan
import com.mstr.btccompare.data.Verdict
import com.mstr.btccompare.ui.ChartSeries
import com.mstr.btccompare.ui.MainViewModel
import com.mstr.btccompare.ui.UiState
import com.mstr.btccompare.ui.ZoomLineChart

private val Bg = Color(0xFF0B0F19)
private val Card = Color(0xFF111827)
private val BtcOrange = Color(0xFFF7931A)
private val BtcAmber = Color(0xFFFFC68A)
private val MstrBlue = Color(0xFF60A5FA)
private val UpGreen = Color(0xFF22C55E)
private val DownRed = Color(0xFFEF4444)
private val Muted = Color(0xFF94A3B8)
private val Grid = Color(0xFF1F2937)
private val TooltipBg = Color(0xCC1F2937)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Bg, surface = Bg)) {
                Surface(color = Bg, modifier = Modifier.fillMaxSize()) {
                    AppScreen()
                }
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val period by vm.period.collectAsState()

    Scaffold(
        containerColor = Bg,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("MSTR ↔ BTC Imbalance",
                            color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text("วิเคราะห์ช่วง MSTR ปิด แต่ BTC ยังเดิน",
                            color = Muted, fontSize = 11.sp)
                    }
                },
                actions = {
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Bg,
                    titleContentColor = Color.White
                )
            )
        }
    ) { padding: PaddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            PeriodSelector(period) { vm.setPeriod(it) }
            Spacer(Modifier.height(12.dp))
            when (val s = state) {
                is UiState.Loading -> LoadingView()
                is UiState.Error -> ErrorView(s.message) { vm.refresh() }
                is UiState.Ready -> ReadyView(s)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PeriodSelector(current: Int, onPick: (Int) -> Unit) {
    val labels = mapOf(
        1 to "1d", 2 to "2d", 3 to "3d", 4 to "4d",
        5 to "5d", 6 to "6d", 7 to "7d",
        30 to "1M", 90 to "3M", 180 to "6M",
        365 to "1Y", 720 to "2Y"
    )
    val options = listOf(1, 2, 3, 4, 5, 6, 7, 30, 90, 180, 365, 720)
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        options.forEach { d ->
            val selected = d == current
            Button(
                onClick = { onPick(d) },
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selected) BtcOrange else Card,
                    contentColor = if (selected) Color.Black else Color.White
                ),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                modifier = Modifier.height(34.dp)
            ) {
                Text(labels[d] ?: "${d}D", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun LoadingView() {
    Box(
        modifier = Modifier.fillMaxWidth().height(360.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = BtcOrange)
            Spacer(Modifier.height(12.dp))
            Text("กำลังโหลดราคาจาก Barchart...", color = Muted)
        }
    }
}

@Composable
private fun ErrorView(message: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().height(300.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("โหลดข้อมูลไม่สำเร็จ", color = Color.White, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(message, color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRetry) { Text("ลองใหม่") }
        }
    }
}

@Composable
private fun ReadyView(state: UiState.Ready) {
    val data = state.data
    val report = data.imbalance
    var diffMode by rememberSaveable { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {

        if (report != null) {
            ImbalanceCard(report)
            Spacer(Modifier.height(12.dp))
            ComponentsCard(report.components)
            Spacer(Modifier.height(12.dp))
            StatsCard(report.stats, data.periodDays)
        } else {
            EmptyImbalanceNote()
        }

        Spacer(Modifier.height(12.dp))
        BtcSourcesCard(
            binance = data.btcLatestBinance,
            coinbase = data.btcLatestCoinbase,
            coinGecko = data.btcLatestCoinGecko,
            sourceUsed = data.btcSourceUsed
        )

        Spacer(Modifier.height(16.dp))

        SummaryRow(
            btc = data.latestBtcUsClose,
            mstr = data.latestMstr,
            btcPct = pctChange(data.btcAtUsClose),
            mstrPct = pctChange(data.mstrClose)
        )
        Spacer(Modifier.height(12.dp))
        DiffToggle(diffMode) { diffMode = it }
        Spacer(Modifier.height(8.dp))

        val isShortPeriod = data.periodDays <= 7
        val btcSeries = when {
            diffMode -> listOf(
                ChartSeries(
                    label = "BTC US-close − US-open",
                    color = BtcOrange,
                    points = data.btcOpenMinusClose
                )
            )
            isShortPeriod -> {
                // Show every hourly close for fine intraday detail.
                val cutoff = System.currentTimeMillis() / 1000L -
                    data.periodDays.toLong() * 24L * 3600L
                val recent = data.btcHourlyLine.filter { it.timestampSec >= cutoff }
                listOf(ChartSeries("BTC hourly", BtcOrange, recent))
            }
            else -> listOf(
                ChartSeries("BTC @ US 16:00", BtcOrange, data.btcAtUsClose),
                ChartSeries("BTC @ US 9:30", BtcAmber, data.btcAtUsOpen, dashed = true)
            )
        }
        val mstrSeries = when {
            diffMode ->
                ChartSeries("MSTR open − close", MstrBlue, data.mstrOpenMinusClose, rightAxis = true)
            isShortPeriod -> {
                val cutoff = System.currentTimeMillis() / 1000L -
                    data.periodDays.toLong() * 24L * 3600L
                ChartSeries("MSTR close",
                    MstrBlue,
                    data.mstrClose.filter { it.timestampSec >= cutoff },
                    rightAxis = true)
            }
            else ->
                ChartSeries("MSTR close", MstrBlue, data.mstrClose, rightAxis = true)
        }

        ChartCard(
            title = when {
                diffMode -> "BTC vs MSTR (open − close)"
                isShortPeriod -> "BTC hourly vs MSTR daily (${data.periodDays}d)"
                else -> "BTC vs MSTR"
            },
            subtitle = when {
                diffMode -> "Daily intraday: BTC = US-close − US-open • MSTR = open − close"
                isShortPeriod -> "BTC ทุกชั่วโมง • MSTR daily close"
                else -> "BTC: ราคา ณ NYSE 16:00 ET (เข้ม) / 9:30 ET (ประ) • MSTR: close"
            },
            colorAccent = BtcOrange,
            heightDp = 300
        ) {
            ZoomLineChart(
                series = btcSeries + mstrSeries,
                gridColor = Grid, axisColor = Muted,
                tooltipBg = TooltipBg, tooltipText = Color.White,
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "ข้อมูล: MSTR จาก Barchart (1d) • BTC จาก Binance klines (1h, NY-aligned)",
            color = Muted, fontSize = 11.sp
        )
    }
}

// ──────────────────────────────────────────────────────────────────────
//  Card 1 — Imbalance headline
// ──────────────────────────────────────────────────────────────────────

@Composable
private fun ImbalanceCard(r: ImbalanceReport) {
    val (bg, fg) = when {
        r.verdict.isBuy && r.combinedScore >= 4 -> UpGreen to Color.Black
        r.verdict.isBuy -> UpGreen.copy(alpha = 0.85f) to Color.Black
        r.verdict.isSell && r.combinedScore <= -4 -> DownRed to Color.White
        r.verdict.isSell -> DownRed.copy(alpha = 0.85f) to Color.White
        else -> Card to Color.White
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(r.verdict.label, color = fg, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(r.direction, color = fg.copy(alpha = 0.85f), fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold)
            }
            Text("score %+d".format(r.combinedScore), color = fg,
                fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
        Spacer(Modifier.height(8.dp))
        Text(r.headline, color = fg.copy(alpha = 0.9f), fontSize = 12.sp)

        val plan = r.plan
        if (plan.entry > 0.0 && plan.stop != 0.0) {
            Spacer(Modifier.height(10.dp))
            Row {
                LevelChip("Entry", plan.entry, fg)
                Spacer(Modifier.size(6.dp))
                LevelChip("Stop", plan.stop, fg)
                Spacer(Modifier.size(6.dp))
                LevelChip("T1 (1R)", plan.target1, fg)
                Spacer(Modifier.size(6.dp))
                LevelChip("T2 (2R)", plan.target2, fg)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "%d shares × $%,.2f = $%,.0f notional • risk $%,.0f (2%% of $%,.0f)".format(
                    plan.shares, plan.entry, plan.notional, plan.riskAmount, plan.accountSize
                ),
                color = fg.copy(alpha = 0.85f), fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun LevelChip(label: String, price: Double, fg: Color) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.25f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(label, color = fg.copy(alpha = 0.85f), fontSize = 10.sp)
        Text("$%,.2f".format(price), color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ──────────────────────────────────────────────────────────────────────
//  Card 2 — three imbalance components
// ──────────────────────────────────────────────────────────────────────

@Composable
private fun ComponentsCard(components: List<ImbalanceComponent>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Card)
            .padding(14.dp)
    ) {
        Text("Imbalance channels", color = Color.White,
            fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        components.forEachIndexed { i, c ->
            if (i > 0) Spacer(Modifier.height(8.dp))
            ComponentRow(c)
        }
    }
}

@Composable
private fun ComponentRow(c: ImbalanceComponent) {
    val titleColor = if (c.active) Color.White else Muted
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(c.title, color = titleColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            ScorePill(c.score, c.active)
        }
        Text(c.displayValue, color = titleColor, fontSize = 12.sp)
        Text(c.explanation, color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun ScorePill(score: Int, active: Boolean) {
    val color = when {
        !active -> Muted
        score > 0 -> UpGreen
        score < 0 -> DownRed
        else -> Muted
    }
    val text = if (!active) "n/a" else "%+d".format(score)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.25f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ──────────────────────────────────────────────────────────────────────
//  Card 3 — sanity stats
// ──────────────────────────────────────────────────────────────────────

@Composable
private fun StatsCard(s: ImbalanceStats, periodDays: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Card)
            .padding(14.dp)
    ) {
        Text("Stats (n=${s.sampleSize} of ${periodDays}d)",
            color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text("ตัวเลขทั้งหมดที่ใช้คำนวณสัญญาณด้านบน",
            color = Muted, fontSize = 11.sp)
        Spacer(Modifier.height(10.dp))

        StatRow("Market window", windowLabel(s.window))
        StatRow("MSTR last close", "$%,.2f".format(s.mstrLastClose))
        StatRow("BTC at MSTR last close", "$%,.0f".format(s.btcAtLastMstrClose))
        StatRow("BTC latest", "$%,.0f (อายุ %d นาที)".format(s.btcLatest, s.btcLatestAgeMin))
        StatRow("Expected next open", "$%,.2f (%+.2f%%)"
            .format(s.expectedNextOpen, s.expectedGapPct * 100))
        Spacer(Modifier.height(6.dp))
        StatRow("Gap regression β", "%.2f".format(s.gapBeta))
        StatRow("Gap regression α", "%.4f".format(s.gapAlpha))
        StatRow("Gap regression ρ", "%.2f".format(s.gapRho))
        StatRow("Gap residual σ", "%.2f%%".format(s.gapResidualStd * 100))
        Spacer(Modifier.height(6.dp))
        StatRow("Intraday β", "%.2f".format(s.intradayBeta))
        StatRow("Intraday α", "%.4f".format(s.intradayAlpha))
        StatRow("Intraday ρ", "%.2f".format(s.intradayRho))
        StatRow("Intraday residual σ", "%.2f%%".format(s.intradayResidualStd * 100))
        Spacer(Modifier.height(6.dp))
        StatRow("ATR(14) MSTR", "$%.2f".format(s.atr14))
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(value, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun windowLabel(w: MarketWindow): String = when (w) {
    MarketWindow.RegularSession -> "เปิด (9:30–16:00 ET)"
    MarketWindow.AfterHours -> "หลังปิด (16:00–24:00 ET)"
    MarketWindow.Closed -> "ปิด (pre-market / สุดสัปดาห์)"
}

@Composable
private fun EmptyImbalanceNote() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Card)
            .padding(14.dp)
    ) {
        Text("ยังไม่มีสัญญาณ", color = Color.White,
            fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text(
            "ข้อมูล BTC hourly ไม่พอ (Binance ไม่ตอบ หรือ network) → " +
                "เครื่องคำนวณ imbalance ทำงานไม่ได้",
            color = Muted, fontSize = 11.sp
        )
    }
}

// ──────────────────────────────────────────────────────────────────────
//  Bottom: price summary + chart
// ──────────────────────────────────────────────────────────────────────

@Composable
private fun SummaryRow(btc: Double, mstr: Double, btcPct: Double, mstrPct: Double) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatCard("BTC", "$%,.0f".format(btc), btcPct, BtcOrange, Modifier.weight(1f))
        StatCard("MSTR", "$%,.2f".format(mstr), mstrPct, MstrBlue, Modifier.weight(1f))
    }
}

@Composable
private fun StatCard(
    label: String, value: String, change: Double, accent: Color, modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Card)
            .padding(12.dp)
    ) {
        Text(label, color = accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(value, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        val arrow = if (change >= 0) "▲" else "▼"
        val col = if (change >= 0) UpGreen else DownRed
        Text("$arrow %+.2f%%".format(change), color = col, fontSize = 11.sp)
    }
}

@Composable
private fun DiffToggle(checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Card)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Mode: open − close", color = Color.White,
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(
                if (checked)
                    "เส้นเดียว: BTC = US-close − US-open • MSTR = open − close"
                else "แสดง 2 เส้น BTC + MSTR ปกติ",
                color = Muted, fontSize = 11.sp
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = BtcOrange,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Grid
            )
        )
    }
}

@Composable
private fun ChartCard(
    title: String, subtitle: String, colorAccent: Color, heightDp: Int = 280,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Card)
            .padding(12.dp)
    ) {
        Text(title, color = colorAccent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text(subtitle, color = Muted, fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))
        Box(modifier = Modifier.fillMaxWidth().height(heightDp.dp)) {
            content()
        }
    }
}

@Composable
private fun BtcSourcesCard(
    binance: Double?,
    coinbase: Double?,
    coinGecko: Double?,
    sourceUsed: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Card)
            .padding(14.dp)
    ) {
        Text("BTC cross-check (latest spot)", color = Color.White,
            fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text(
            "ใช้ $sourceUsed สำหรับข้อมูลย้อนหลัง • ตัวเลข 3 แหล่งควรใกล้กัน (~0.1%)",
            color = Muted, fontSize = 11.sp
        )
        Spacer(Modifier.height(8.dp))
        StatRow("Binance", formatBtc(binance))
        StatRow("Coinbase", formatBtc(coinbase))
        StatRow("CoinGecko", formatBtc(coinGecko))
        val all = listOfNotNull(binance, coinbase, coinGecko)
        if (all.size >= 2) {
            val mn = all.min(); val mx = all.max()
            val spreadPct = if (mn > 0) (mx - mn) / mn * 100.0 else 0.0
            Spacer(Modifier.height(4.dp))
            StatRow("Spread", "%.3f%% (\$%.0f)".format(spreadPct, mx - mn))
        }
    }
}

private fun formatBtc(v: Double?): String =
    if (v == null) "—" else "$%,.0f".format(v)

private fun pctChange(points: List<PricePoint>): Double {
    if (points.size < 2) return 0.0
    val first = points.first().value
    val last = points.last().value
    if (first <= 0.0) return 0.0
    return (last - first) / first * 100.0
}
