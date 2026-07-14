package com.hik.otest

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hik.otest.ui.theme.HikotestAndroidSDKTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HikotestAndroidSDKTheme {
                HikotestScreen()
            }
        }
    }
}

@Composable
private fun HikotestScreen() {
    val initState by Hikotest.initState.collectAsState()
    var inputA by remember { mutableStateOf("") }
    var inputB by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Hikotest SDK",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            when (val state = initState) {
                is HikotestInitState.Idle,
                is HikotestInitState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "WASM indiriliyor / yükleniyor...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                is HikotestInitState.Ready -> Text(
                    text = "SDK hazır",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                is HikotestInitState.Error -> Text(
                    text = "Hata: ${state.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            OutlinedTextField(
                value = inputA,
                onValueChange = { inputA = it },
                label = { Text("Sayı A") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = inputB,
                onValueChange = { inputB = it },
                label = { Text("Sayı B") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = {
                    val a = inputA.toIntOrNull()
                    val b = inputB.toIntOrNull()
                    result = when {
                        a == null || b == null -> "Geçerli tam sayı gir"
                        else -> try {
                            // Canlı paket her fonksiyonu kendi adıyla export eder
                            val sig = HikoSignature(HikoType.INT, HikoType.INT, HikoType.INT)
                            "Sonuç: ${Hikotest.execute("calculateTax", sig, a, b)}"
                        } catch (e: Exception) {
                            "Hata: ${e.message}"
                        }
                    }
                },
                enabled = initState == HikotestInitState.Ready,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("calculateTax(A, B)")
            }

            result?.let { res ->
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = res,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
            LiveFunctionsSection(enabled = initState == HikotestInitState.Ready)
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * Canlı paketteki (release.wasm) fonksiyonları kendi adlarıyla çağırır —
 * her tip için bir örnek: int, boolean, string ve float dönüşleri.
 */
@Composable
private fun LiveFunctionsSection(enabled: Boolean) {
    var rows by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    val intSig = HikoSignature(HikoType.INT, HikoType.INT, HikoType.INT)
    val couponSig = HikoSignature(HikoType.STRING, HikoType.INT, HikoType.BOOLEAN)
    val shippingSig = HikoSignature(HikoType.INT, HikoType.BOOLEAN, HikoType.STRING)
    val discountSig = HikoSignature(HikoType.FLOAT, HikoType.INT, HikoType.FLOAT)
    val loyaltySig = HikoSignature(HikoType.INT, HikoType.BOOLEAN, HikoType.INT)

    Text(
        text = "Canlı Fonksiyonlar (OTA bundle)",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )
    Spacer(modifier = Modifier.height(8.dp))

    Button(
        onClick = {
            val calls: List<Pair<String, () -> Any>> = listOf(
                "calculateTax(100, 18)" to { Hikotest.execute("calculateTax", intSig, 100, 18) },
                "checkCoupon(\"HIKO20\", 250)" to { Hikotest.execute("checkCoupon", couponSig, "HIKO20", 250) },
                "shippingLabel(5, true)" to { Hikotest.execute("shippingLabel", shippingSig, 5, true) },
                "calculateDiscount(1000.0, 3)" to { Hikotest.execute("calculateDiscount", discountSig, 1000.0, 3) },
                "loyaltyPoints(1000, true)" to { Hikotest.execute("loyaltyPoints", loyaltySig, 1000, true) },
            )
            rows = calls.map { (label, call) ->
                label to runCatching { call().toString() }.getOrElse { "Hata: ${it.message}" }
            }
        },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("5 canlı fonksiyonu çalıştır")
    }

    rows.forEach { (label, value) ->
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = label, style = MaterialTheme.typography.bodySmall)
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = if (value.startsWith("Hata")) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary
            )
        }
    }
}
