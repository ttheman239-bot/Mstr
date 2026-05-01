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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.mstr.btccompare.ui.CompareChart
import com.mstr.btccompare.ui.MainViewModel
import com.mstr.btccompare.ui.UiState

private val Bg = Color(0xFF0B0F19)
private val Card = Color(0xFF111827)
private val BtcOrange = Color(0xFFF7931A)
private val MstrBlue = Color(0xFF60A5FA)
private val Muted = Color(0xFF94A3B8)
private val Grid = Color(0xFF1F2937)

class MainActivity : ComponentActivity() {

    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
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
                    Text(
                        "BTC vs MSTR",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
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
        ) {
            PeriodSelector(period) { vm.setPeriod(it) }
            Spacer(Modifier.height(12.dp))
            when (val s = state) {
                is UiState.Loading -> LoadingView()
                is UiState.Error -> ErrorView(s.message) { vm.refresh() }
                is UiState.Ready -> ReadyView(s)
            }
        }
    }
}

@Composable
private fun PeriodSelector(current: Int, onPick: (Int) -> Unit) {
    val options = listOf(30, 90, 180, 365, 730)
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
                    if (d >= 365) "${d / 365}Y" else "${d}D",
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun LoadingView() {
    Box(
        modifier = Modifier.fillMaxSize(),
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
        modifier = Modifier.fillMaxSize(),
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
    Column(modifier = Modifier.fillMaxSize()) {
        SummaryRow(data.latestBtc, data.latestMstr, data.ratio.lastOrNull()?.value ?: 0.0)
        Spacer(Modifier.height(8.dp))
        Legend()
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(360.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Card)
                .padding(8.dp)
        ) {
            CompareChart(
                btc = data.btc,
                mstr = data.mstr,
                btcColor = BtcOrange,
                mstrColor = MstrBlue,
                gridColor = Grid,
                axisColor = Muted,
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "BTC/MSTR ratio (USD per share)",
            color = Muted,
            fontSize = 12.sp
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Card)
                .padding(8.dp)
        ) {
            CompareChart(
                btc = data.ratio,
                mstr = emptyList(),
                btcColor = Color(0xFF22C55E),
                mstrColor = Color.Transparent,
                gridColor = Grid,
                axisColor = Muted,
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "ข้อมูล: CoinGecko (BTC) และ Yahoo Finance (MSTR)",
            color = Muted,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun SummaryRow(btc: Double, mstr: Double, ratio: Double) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatCard("BTC", "$%,.0f".format(btc), BtcOrange, modifier = Modifier.weight(1f))
        StatCard("MSTR", "$%,.2f".format(mstr), MstrBlue, modifier = Modifier.weight(1f))
        StatCard("Ratio", "%.0f".format(ratio), Color(0xFF22C55E), modifier = Modifier.weight(1f))
    }
}

@Composable
private fun StatCard(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Card)
            .padding(12.dp)
    ) {
        Text(label, color = accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(value, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Legend() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(BtcOrange)
        Spacer(Modifier.size(6.dp))
        Text("BTC", color = Color.White, fontSize = 12.sp)
        Spacer(Modifier.size(16.dp))
        Dot(MstrBlue)
        Spacer(Modifier.size(6.dp))
        Text("MSTR", color = Color.White, fontSize = 12.sp)
    }
}

@Composable
private fun Dot(color: Color) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color)
    )
}
