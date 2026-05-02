package com.mstr.btccompare

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import com.mstr.btccompare.data.PricePoint
import com.mstr.btccompare.data.SignalLine
import com.mstr.btccompare.data.SignalReport
import com.mstr.btccompare.data.SignalStats
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
                        Text(
                            "MSTR/BTC Day-Trade Signal",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "ราคา BTC อิง NYSE 9:30 / 16:00 ET",
                            color = Muted,
                            fontSize = 11.sp
                        )
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

@Composable
private fun PeriodSelector(current: Int, onPick: (Int) -> Unit) {
    val options = listOf(30, 90, 180, 365, 720)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
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
                modifier = Modifier.height(36.dp)
            ) {
                Text(
                    when {
                        d >= 365 -> "${d / 365}Y"
                        d >= 30 -> "${d / 30}M"
                        else -> "${d}D"
                    },
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun LoadingView() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(360.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = BtcOrange)
            Spacer(Modifier.height(12.dp))
            Text("กำลังโหลดราคา...", color = Muted)
        }
    }
}

@Composable
private fun ErrorView(message: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp),
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
    val report = data.signal
    val btcPct = pctChange(data.btcAtUsClose)
    val mstrPct = pctChange(data.mstrClose)

    var diffMode by rememberSaveable { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {

        // ── 1. Trade plan banner (most important — at top)
        TradePlanCard(report.plan, report.combinedScore)

        Spacer(Modifier.height(12.dp))
        // ── 2. Three signals
        SignalsCard(report.signals)

        Spacer(Modifier.height(12.dp))
        // ── 3. Stats / sanity check
        StatsCard(report.stats, data.periodDays)

        Spacer(Modifier.height(16.dp))
        // ── Price summary cards
        SummaryRow(
            btc = data.latestBtcUsClose,
            mstr = data.latestMstr,
            btcPct = btcPct,
            mstrPct = mstrPct
        )

        Spacer(Modifier.height(12.dp))
        DiffToggle(diffMode) { diffMode = it }

        Spacer(Modifier.height(8.dp))

        val btcSeries = if (diffMode) {
            listOf(
                ChartSeries(
                    label = "BTC US-close − US-open",
                    color = BtcOrange,
                    points = data.btcOpenMinusClose
                )
            )
        } else {
            listOf(
                ChartSeries(
                    label = "BTC open (US close)",
                    color = BtcOrange,
                    points = data.btcAtUsClose
                ),
                ChartSeries(
                    label = "BTC close (US open)",
                    color = BtcAmber,
                    points = data.btcAtUsOpen,
                    dashed = true
                )
            )
        }
        val mstrSeries = if (diffMode) {
            ChartSeries(
                label = "MSTR open − close",
                color = MstrBlue,
                points = data.mstrOpenMinusClose,
                rightAxis = true
            )
        } else {
            ChartSeries(
                label = "MSTR",
                color = MstrBlue,
                points = data.mstrClose,
                rightAxis = true
            )
        }

        ChartCard(
            title = if (diffMode) "BTC vs MSTR (open − close)" else "BTC vs MSTR",
            subtitle = if (diffMode)
                "Daily intraday change: BTC = US-close − US-open • MSTR = open − close"
            else
                "BTC: ราคา ณ US 16:00 ET (เข้ม) และ 9:30 ET (ประ) • MSTR: close",
            colorAccent = BtcOrange,
            heightDp = 300
        ) {
            ZoomLineChart(
                series = btcSeries + mstrSeries,
                gridColor = Grid,
                axisColor = Muted,
                tooltipBg = TooltipBg,
                tooltipText = Color.White,
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "BTC: Yahoo (1h, NYSE-aligned) • MSTR: ${data.mstrSource}",
            color = Muted,
            fontSize = 11.sp
        )
    }
}

// ──────────────────────────────────────────────────────────────────────────
//  Card 1 — Trade plan
// ──────────────────────────────────────────────────────────────────────────

@Composable
private fun TradePlanCard(plan: TradePlan, score: Int) {
    val (bg, fg) = when {
        plan.verdict.isBuy && score >= 4 -> UpGreen to Color.Black
        plan.verdict.isBuy -> UpGreen.copy(alpha = 0.85f) to Color.Black
        plan.verdict.isSell && score <= -4 -> DownRed to Color.White
        plan.verdict.isSell -> DownRed.copy(alpha = 0.85f) to Color.White
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
                Text(
                    plan.verdict.label,
                    color = fg,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Text(
                    plan.direction,
                    color = fg.copy(alpha = 0.85f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                "score %+d".format(score),
                color = fg,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            )
        }
        if (plan.entry > 0.0 && plan.stop != 0.0 && plan.target1 != 0.0) {
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
                    plan.shares, plan.entry, plan.notional,
                    plan.riskAmount, plan.accountSize
                ),
                color = fg.copy(alpha = 0.85f),
                fontSize = 11.sp
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
        Text(
            "$%,.2f".format(price),
            color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
        )
    }
}

// ──────────────────────────────────────────────────────────────────────────
//  Card 2 — Signals
// ──────────────────────────────────────────────────────────────────────────

@Composable
private fun SignalsCard(signals: List<SignalLine>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Card)
            .padding(14.dp)
    ) {
        Text(
            "Signals (3 indicators)",
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp
        )
        Spacer(Modifier.height(8.dp))
        signals.forEachIndexed { idx, s ->
            if (idx > 0) Spacer(Modifier.height(8.dp))
            SignalRow(s)
        }
    }
}

@Composable
private fun SignalRow(s: SignalLine) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(s.value, color = Color.White, fontSize = 13.sp)
            Spacer(Modifier.size(8.dp))
            ScorePill(s.score)
        }
        Text(s.explanation, color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun ScorePill(score: Int) {
    val color = when {
        score > 0 -> UpGreen
        score < 0 -> DownRed
        else -> Muted
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.25f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            "%+d".format(score),
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// ──────────────────────────────────────────────────────────────────────────
//  Card 3 — Stats
// ──────────────────────────────────────────────────────────────────────────

@Composable
private fun StatsCard(stats: SignalStats, periodDays: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Card)
            .padding(14.dp)
    ) {
        Text(
            "Stats (${stats.sampleSize} of ${periodDays}d)",
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "ค่าเหล่านี้ใช้คำนวณ signals ด้านบน",
            color = Muted,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(10.dp))

        StatRow("Ratio mean (μ)", "%.6f".format(stats.ratioMean))
        StatRow("Ratio σ", "%.6f".format(stats.ratioStdev))
        StatRow("Ratio Z (now)",
            stats.zScore60?.let { "%+.2f".format(it) } ?: "—")
        Spacer(Modifier.height(6.dp))
        StatRow("RSI(14)",
            stats.rsi14?.let { "%.0f".format(it) } ?: "—")
        Spacer(Modifier.height(6.dp))
        StatRow("Gap β (slope)", "%.2f".format(stats.gapBeta))
        StatRow("Gap α (intercept)", "%.4f".format(stats.gapAlpha))
        StatRow("Gap ρ (correlation)", "%.2f".format(stats.gapCorrelation))
        StatRow(
            "Gap residual σ",
            "%.2f%%".format(stats.gapResidualStd * 100)
        )
        Spacer(Modifier.height(6.dp))
        StatRow("ATR(14) — MSTR", "$%.2f".format(stats.atr14))
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(value, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ──────────────────────────────────────────────────────────────────────────
//  Bottom: price summary + chart
// ──────────────────────────────────────────────────────────────────────────

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
    label: String,
    value: String,
    change: Double,
    accent: Color,
    modifier: Modifier = Modifier
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
            Text(
                "Mode: open − close",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                if (checked)
                    "เส้นเดียว: BTC = US-close − US-open • MSTR = open − close"
                else
                    "แสดง 2 เส้น BTC + MSTR ปกติ",
                color = Muted,
                fontSize = 11.sp
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
    title: String,
    subtitle: String,
    colorAccent: Color,
    heightDp: Int = 280,
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
        Box(modifier = Modifier
            .fillMaxWidth()
            .height(heightDp.dp)) {
            content()
        }
    }
}

private fun pctChange(points: List<PricePoint>): Double {
    if (points.size < 2) return 0.0
    val first = points.first().value
    val last = points.last().value
    if (first <= 0.0) return 0.0
    return (last - first) / first * 100.0
}
