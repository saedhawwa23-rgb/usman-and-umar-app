package com.opendroid.ai.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun NativePhoneToolsCard() {
    val context = LocalContext.current
    var url by remember { mutableStateOf("") }
    var shareText by remember { mutableStateOf("") }
    val launcherApps = remember {
        runCatching {
            val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentActivities(query, 0)
                .distinctBy { it.activityInfo.packageName }
                .sortedBy { it.loadLabel(context.packageManager).toString().lowercase() }
                .take(30)
        }.getOrDefault(emptyList())
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("NATIVE PHONE TOOLS · OFFLINE / ZERO AI COST", fontWeight = FontWeight.Bold)
            Text("These Android intents run on-device and do not call an AI service.")

            Text("OPEN A PHONE APP", fontWeight = FontWeight.SemiBold)
            if (launcherApps.isEmpty()) {
                Text("No launchable apps were returned by Android.")
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().height(140.dp)) {
                    items(launcherApps) { resolveInfo ->
                        val packageName = resolveInfo.activityInfo.packageName
                        OutlinedButton(
                            onClick = {
                                runCatching { context.packageManager.getLaunchIntentForPackage(packageName) }
                                    .getOrNull()?.let { intent ->
                                        runCatching { context.startActivity(intent) }
                                            .onFailure { Toast.makeText(context, "Unable to open that app.", Toast.LENGTH_SHORT).show() }
                                    } ?: Toast.makeText(context, "The app has no launch activity.", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(resolveInfo.loadLabel(context.packageManager).toString())
                        }
                    }
                }
            }

            Text("OPEN A WEB URL", fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("https://example.com") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    val target = runCatching { Uri.parse(url.trim()) }.getOrNull()
                    if (target == null || target.scheme?.lowercase() !in setOf("http", "https") || target.host.isNullOrBlank() || target.userInfo != null) {
                        Toast.makeText(context, "Enter a valid http:// or https:// URL without credentials.", Toast.LENGTH_LONG).show()
                    } else {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, target).addCategory(Intent.CATEGORY_BROWSABLE))
                        }.onFailure { Toast.makeText(context, "No browser can open this URL.", Toast.LENGTH_SHORT).show() }
                    }
                },
                enabled = url.isNotBlank()
            ) { Text("OPEN IN BROWSER") }

            Spacer(Modifier.height(2.dp))
            Text("SHARE TEXT", fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = shareText,
                onValueChange = { shareText = it },
                label = { Text("Text to share") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(
                    onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, shareText)
                        }
                        runCatching { context.startActivity(Intent.createChooser(send, "Share with…")) }
                            .onFailure { Toast.makeText(context, "Unable to open the Sharesheet.", Toast.LENGTH_SHORT).show() }
                    },
                    enabled = shareText.isNotBlank()
                ) { Text("OPEN SHARESHEET") }
            }
        }
    }
}
