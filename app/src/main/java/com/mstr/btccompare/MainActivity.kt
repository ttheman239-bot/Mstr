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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mstr.btccompare.data.CandlePoint
import com.mstr.btccompare.ui.CandleChart
import com.mstr.btccompare.ui.LineChart
import com.mstr.btccompare.ui.MainViewModel
import com.mstr.btccompare.ui.UiState

private val Bg = Color(0xFF0B0F19)
private val Card = Color(0xFF111827)
private val BtcOrange = Color(0xFFF7931A)
private val MstrBlue = Color(0xFF60A5FA)
private val UpGreen = Color(0xFF22C55E)
private val DownRed = Color(0xFFEF4444)
private val Muted = Color(0xFF94A3B8)
private val Grid = Color(0xFF1F2937)

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
                            "BTC vs MSTR",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "BTC aligned to NYSE 9:30 / 16:00 ET",
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
    val btcPct = pctChange(data.btc)
    val mstrPct = pctChange(data.mstr)

    Column(modifier = Modifier.fillMaxWidth()) {
        SummaryRow(
            btc = data.latestBtc,
            mstr = data.latestMstr,
            ratio = data.ratio.lastOrNull()?.value ?: 0.0,
            btcPct = btcPct,
            mstrPct = mstrPct
        )
        Spacer(Modifier.height(12.dp))

        ChartCard(
            title = "BTC/USD — session candles (NYSE hours)",
            subtitle = "Open = 9:30 ET, Close = 16:00 ET",
            colorAccent = BtcOrange
        ) {
            CandleChart(
                candles = data.btc,
                upColor = UpGreen,
                downColor = DownRed,
                gridColor = Grid,
                axisColor = Muted,
                leftAxisColor = BtcOrange,
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(Modifier.height(12.dp))

        ChartCard(
            title = "MSTR — daily candles",
            subtitle = "NASDAQ regular session",
            colorAccent = MstrBlue
        ) {
            CandleChart(
                candles = data.mstr,
                upColor = UpGreen,
                downColor = DownRed,
                gridColor = Grid,
                axisColor = Muted,
                leftAxisColor = MstrBlue,
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(Modifier.height(12.dp))

        ChartCard(
            title = "BTC / MSTR ratio",
            subtitle = "USD of BTC per share of MSTR (close/close)",
            colorAccent = UpGreen,
            heightDp = 200
        ) {
            LineChart(
                points = data.ratio,
                lineColor = UpGreen,
                fillColor = UpGreen,
                gridColor = Grid,
                axisColor = Muted,
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "ข้อมูล: Yahoo Finance (BTC-USD 1h aligned to NYSE 9:30/16:00 ET, MSTR 1d)",
            color = Muted,
            fontSize = 11.sp
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

@Composable
private fun SummaryRow(
    btc: Double,
    mstr: Double,
    ratio: Double,
    btcPct: Double,
    mstrPct: Double
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatCard(
            label = "BTC",
            value = "$%,.0f".format(btc),
            change = btcPct,
            accent = BtcOrange,
            modifier = Modifier.weight(1f)
        )
        StatCard(
            label = "MSTR",
            value = "$%,.2f".format(mstr),
            change = mstrPct,
            accent = MstrBlue,
            modifier = Modifier.weight(1f)
        )
        StatCard(
            label = "Ratio",
            value = "%,.0f".format(ratio),
            change = null,
            accent = UpGreen,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun StatCard(
    label: String,
    value: String,
    change: Double?,
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
        if (change != null) {
            val arrow = if (change >= 0) "▲" else "▼"
            val col = if (change >= 0) UpGreen else DownRed
            Text(
                "$arrow %+.2f%%".format(change),
                color = col,
                fontSize = 11.sp
            )
        }
    }
}

private fun pctChange(candles: List<CandlePoint>): Double {
    if (candles.size < 2) return 0.0
    val first = candles.first().open
    val last = candles.last().close
    if (first <= 0.0) return 0.0
    return (last - first) / first * 100.0
}
