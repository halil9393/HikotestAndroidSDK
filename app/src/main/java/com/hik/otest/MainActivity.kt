package com.hik.otest

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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

private sealed class SdkState {
    object Loading : SdkState()
    object Ready : SdkState()
    data class Error(val message: String) : SdkState()
}

@Composable
private fun HikotestScreen() {
    val context = LocalContext.current

    var sdkState by remember { mutableStateOf<SdkState>(SdkState.Loading) }
    var inputA by remember { mutableStateOf("") }
    var inputB by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        sdkState = SdkState.Loading
        sdkState = try {
            Hikotest.initialize(context)
            SdkState.Ready
        } catch (e: Exception) {
            SdkState.Error(e.message ?: "Bilinmeyen hata")
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Hikotest SDK",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            when (val state = sdkState) {
                SdkState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "WASM indiriliyor / yükleniyor...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                SdkState.Ready -> Text(
                    text = "SDK hazır",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                is SdkState.Error -> Text(
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
                            "Sonuç: ${Hikotest.getSumOf(a, b)}"
                        } catch (e: Exception) {
                            "Hata: ${e.message}"
                        }
                    }
                },
                enabled = sdkState == SdkState.Ready,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("getSumOf(A, B)")
            }

            result?.let { res ->
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = res,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
