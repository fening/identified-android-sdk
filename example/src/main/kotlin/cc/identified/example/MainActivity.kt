package cc.identified.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.identified.sdk.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var token by remember { mutableStateOf("") }
                var baseUrl by remember { mutableStateOf("https://api.identified.cc") }
                var result by remember { mutableStateOf("") }
                val launcher =
                    rememberLauncherForActivityResult(IdentifiedVerification.Contract()) {
                        result = it.toString()
                    }
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.padding(24.dp).statusBarsPadding(),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text("Identified example", style = MaterialTheme.typography.headlineMedium)
                        Text("Create the session on your backend and paste its session token here.")
                        OutlinedTextField(
                            baseUrl,
                            { baseUrl = it },
                            label = { Text("API base URL") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            token,
                            { token = it },
                            label = { Text("Session token") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = {
                                launcher.launch(
                                    IdentifiedConfiguration(token.trim(), baseUrl.trim())
                                )
                            },
                            enabled = token.isNotBlank(),
                        ) {
                            Text("Start verification")
                        }
                        Text(result)
                    }
                }
            }
        }
    }
}
