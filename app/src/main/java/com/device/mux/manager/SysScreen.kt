package com.device.mux.manager

import android.Manifest
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun SysScreen(onUpdateFound: (UpdateInfo) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    val ptick = Perms.tick
    Page {
        T("SYSTEM", 16f, cMain, true)
        TwoCol(
            {
                Panel("INTERFACE COLOR") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ACCENTS.forEachIndexed { i, a -> Chip(a.name, Prefs.accent == i, Modifier.weight(1f)) { Prefs.updateAccent(i) } }
                    }
                }
                Panel("PERMISSIONS") {
                    T("Grant only what you want to use. Every feature asks when needed.", 10f, cSub, lines = 2, mod = Modifier.padding(bottom = 8.dp))
                    PermRow("ALL FILES (cleaner, APK files)", remember(ptick) { Perms.allFiles(ctx) }) { Perms.requestAllFiles(ctx) }
                    PermRow("LOCATION (WiFi names, GPS)", remember(ptick) { Perms.hasAll(ctx, Perms.location) }) { Perms.request(*Perms.location) }
                    PermRow("PHONE (SIM, signal, 5G)", remember(ptick) { Perms.hasAll(ctx, Perms.phone) }) { Perms.request(*Perms.phone) }
                    PermRow("NEARBY DEVICES (Bluetooth)", remember(ptick) { Perms.hasAll(ctx, Perms.bluetooth) }) { Perms.request(*Perms.bluetooth) }
                    PermRow("CAMERA (camera test)", remember(ptick) { Perms.has(ctx, Manifest.permission.CAMERA) }) { Perms.request(Manifest.permission.CAMERA) }
                    PermRow("MICROPHONE (mic test)", remember(ptick) { Perms.has(ctx, Manifest.permission.RECORD_AUDIO) }) { Perms.request(Manifest.permission.RECORD_AUDIO) }
                }
            },
            {
                Panel("UPDATES") {
                    KV("INSTALLED VERSION", "v${appVersion(ctx)}")
                    Chip("CHECK FOR UPDATES", false, Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        scope.launch {
                            status = "CHECKING..."
                            if (!isOnline(ctx)) {
                                status = "NO INTERNET CONNECTION"
                                return@launch
                            }
                            val (ok, info) = checkUpdate(ctx)
                            status = when {
                                !ok -> "CHECK FAILED"
                                info == null -> "UP TO DATE"
                                else -> "NEW VERSION AVAILABLE: v${info.version}"
                            }
                            if (info != null) onUpdateFound(info)
                        }
                    }
                    if (status.isNotEmpty()) T(status, 11f, cSub, mod = Modifier.padding(top = 6.dp))
                }
                Panel("ABOUT") {
                    T("DEVICE MANAGER", 18f, cMain, true)
                    T("v${appVersion(ctx)}  com.device.mux.manager", 10f, cSub, mod = Modifier.padding(bottom = 8.dp))
                    T("DEVELOPER", 10f, cSub)
                    T(AppInfo.DEV_NAME, 14f, cText, true, Modifier.padding(bottom = 6.dp))
                    T("EMAIL", 10f, cSub)
                    T(AppInfo.DEV_EMAIL, 13f, cMain, mod = Modifier.clickable { openMail(ctx, AppInfo.DEV_EMAIL) }.padding(bottom = 6.dp))
                    T("GITHUB", 10f, cSub)
                    T("github.com/${AppInfo.GITHUB_USER}", 13f, cMain, mod = Modifier.clickable { openUrl(ctx, "https://github.com/${AppInfo.GITHUB_USER}") }.padding(bottom = 6.dp))
                    T("MORE APPS", 10f, cSub)
                    T("zApps Store", 13f, cMain, mod = Modifier.clickable { openUrl(ctx, AppInfo.MORE_APPS) })
                }
            }
        )
    }
}

@Composable
private fun PermRow(label: String, granted: Boolean, grant: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        T(label, 11f, if (granted) cText else cSub, mod = Modifier.weight(1f), lines = 2)
        if (granted) {
            T("GRANTED", 11f, cGood, true)
        } else {
            Chip("GRANT", false, Modifier.padding(start = 8.dp), grant)
        }
    }
}
