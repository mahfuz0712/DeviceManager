package com.device.mux.manager

import android.graphics.Color as AColor
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT)
        )
        setContent { App() }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (KeyTest.active && event.keyCode != KeyEvent.KEYCODE_BACK) {
            KeyTest.onKey(event)
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}

private val TABS = listOf("HOME", "POWER", "NET", "TOOLS", "SYS")

@Composable
fun App() {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var tab by remember { mutableIntStateOf(0) }
    var tool by remember { mutableStateOf(Tool.MENU) }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { Perms.tick++ }
    SideEffect { Perms.launcher = { permLauncher.launch(it) } }

    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) Perms.tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(Unit) {
        Monitor.run(ctx) { owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
    }
    LaunchedEffect(Unit) {
        if (isOnline(ctx)) update = checkUpdate(ctx).second
    }
    BackHandler(enabled = tab == 3 && tool != Tool.MENU) { tool = Tool.MENU }

    update?.let { u ->
        UpdateDialog(
            u, appVersion(ctx),
            onUpdate = { openUrl(ctx, u.apkUrl); update = null },
            onCancel = { update = null }
        )
    }

    HudBackground {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Header()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    0 -> HomeScreen()
                    1 -> BatteryScreen()
                    2 -> NetScreen()
                    3 -> ToolsScreen(tool) { tool = it }
                    else -> SysScreen { update = it }
                }
            }
            NavBar(tab) { tab = it }
        }
        Tests.full?.let { kind -> FullScreenTest(kind) { Tests.full = null } }
    }
}

@Composable
private fun Header() {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            T("DEVICE MANAGER", 20f, cMain, true, Modifier.weight(1f))
            val temp = Monitor.cpuTemp
            T(
                "[ CPU ${(Monitor.cpuLoad * 100).toInt()}%  ${if (temp.isNaN()) "--" else f0(temp)}° ]",
                11f, cSub, align = TextAlign.End
            )
        }
        T("// MUX SYSTEM CORE", 11f, cSub)
        Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(1.dp).background(cDim))
    }
}

@Composable
private fun NavBar(selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Color(0xFF050B10)).padding(top = 10.dp, bottom = 6.dp)) {
        TABS.forEachIndexed { i, name ->
            Column(
                Modifier.weight(1f).clickable { onSelect(i) }.padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                T(name, 13f, if (i == selected) cMain else cSub, i == selected)
                Box(
                    Modifier.padding(top = 4.dp).width(28.dp).height(2.dp)
                        .background(if (i == selected) cMain else Color.Transparent)
                )
            }
        }
    }
}
