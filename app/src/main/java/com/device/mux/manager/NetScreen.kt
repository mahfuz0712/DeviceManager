package com.device.mux.manager

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.net.NetworkInterface

private fun registerSys(ctx: Context, r: BroadcastReceiver, f: IntentFilter) {
    if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(r, f, Context.RECEIVER_EXPORTED) else ctx.registerReceiver(r, f)
}

private fun openSettings(ctx: Context, action: String) {
    try { ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
}

@Composable
fun NetScreen() {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(2000); tick++ } }
    Page {
        T("NETWORK AND CONNECTIVITY", 16f, cMain, true)
        TwoCol(
            { OverviewPanel(tick); TrafficPanel(); WifiPanel(tick) },
            { MobilePanel(); BluetoothPanel(); GpsPanel(tick) }
        )
    }
}

// ---------------------------------------------------------------------- overview

private fun ifaces(): List<NetworkInterface> = try {
    java.util.Collections.list(NetworkInterface.getNetworkInterfaces()).filter { it.isUp && !it.isLoopback }
} catch (e: Exception) { emptyList() }

@Composable
private fun OverviewPanel(tick: Int) {
    val ctx = LocalContext.current
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val caps = cm.getNetworkCapabilities(cm.activeNetwork)
    val ifs = remember(tick) { ifaces() }
    Panel("CONNECTION") {
        val type = when {
            caps == null -> "OFFLINE"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WI-FI"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "MOBILE DATA"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "OTHER"
        }
        KV("ACTIVE LINK", type, if (caps == null) cBad else cGood)
        if (caps != null) {
            KV("INTERNET", if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) "VALIDATED" else "NOT VALIDATED",
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) cGood else cWarn)
            KV("LINK DOWN / UP", "${caps.linkDownstreamBandwidthKbps / 1000} / ${caps.linkUpstreamBandwidthKbps / 1000} Mbps")
            KV("METERED", if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) "NO" else "YES")
        }
        for (ni in ifs) {
            val ips = java.util.Collections.list(ni.inetAddresses).mapNotNull { it.hostAddress?.substringBefore('%') }
            if (ips.isNotEmpty()) KV("IP (${ni.name})", ips.joinToString("\n"))
        }
        for (ni in ifs) {
            val mac = try { ni.hardwareAddress?.joinToString(":") { "%02X".format(it) } } catch (e: Exception) { null }
            if (mac != null) KV("MAC (${ni.name})", mac)
        }
        T("Android 10+ hides the real hardware MAC from apps, so a fake or empty value is normal.", 9f, cSub, lines = 3, mod = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun TrafficPanel() {
    Panel("LIVE TRAFFIC") {
        KV("DOWNLOAD", "${f1(Monitor.rxKBs)} KB/s", cGood)
        KV("UPLOAD", "${f1(Monitor.txKBs)} KB/s", cWarn)
        LineGraph(listOf(Series(Monitor.rxHist, cGood, "DOWN"), Series(Monitor.txHist, cWarn, "UP")), 0f, null, " KB/s")
    }
}

// ---------------------------------------------------------------------- wifi

private fun wifiChannel(f: Int): Int = when {
    f == 2484 -> 14
    f in 2412..2472 -> (f - 2407) / 5
    f in 5000..5900 -> (f - 5000) / 5
    f >= 5955 -> (f - 5950) / 5
    else -> 0
}

private fun secText(c: String): String = when {
    c.contains("SAE") || c.contains("WPA3") -> "WPA3"
    c.contains("WPA2") -> "WPA2"
    c.contains("WPA") -> "WPA"
    c.contains("WEP") -> "WEP"
    else -> "OPEN"
}

@Suppress("DEPRECATION")
@Composable
private fun WifiPanel(tick: Int) {
    val ctx = LocalContext.current
    val wm = remember { ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager }
    val ptick = Perms.tick
    val hasLoc = remember(ptick) { Perms.has(ctx, Manifest.permission.ACCESS_FINE_LOCATION) }
    var results by remember { mutableStateOf<List<ScanResult>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }

    DisposableEffect(hasLoc) {
        val rec = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                scanning = false
                try { results = wm.scanResults ?: emptyList() } catch (e: SecurityException) { }
            }
        }
        registerSys(ctx, rec, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        if (hasLoc) try { results = wm.scanResults ?: emptyList() } catch (e: SecurityException) { }
        onDispose { try { ctx.unregisterReceiver(rec) } catch (e: Exception) { } }
    }

    val info = remember(tick, ptick) { try { wm.connectionInfo } catch (e: Exception) { null } }
    val ssid = info?.ssid?.trim('"') ?: ""
    val connected = info != null && info.networkId != -1
    Panel("WI-FI") {
        KV("ADAPTER", if (wm.isWifiEnabled) "ON" else "OFF", if (wm.isWifiEnabled) cGood else cBad)
        KV("5 GHZ SUPPORT", if (wm.is5GHzBandSupported) "YES" else "NO")
        if (Build.VERSION.SDK_INT >= 30) KV("6 GHZ SUPPORT", if (wm.is6GHzBandSupported) "YES" else "NO")
        val p = Monitor.props
        val hit = p.entries.firstOrNull { (k, v) ->
            v.isNotBlank() && (k.contains("wlan") || k.contains("wifi")) &&
                (k.contains("chip") || k.contains("hw") || k.contains("module") || k.contains("driver"))
        }
        KV("WIFI CHIP", hit?.let { "${it.value} (${it.key})" } ?: "NOT EXPOSED BY VENDOR (SoC: ${Monitor.cpuStatic?.name ?: "?"})")
        if (connected && info != null) {
            KV("CONNECTED TO", if (ssid.isEmpty() || ssid == "<unknown ssid>") "HIDDEN (needs location permission and GPS on)" else ssid, cGood)
            KV("SIGNAL", "${info.rssi} dBm  (level ${WifiManager.calculateSignalLevel(info.rssi, 5)}/4)")
            KV("LINK SPEED", "${info.linkSpeed} Mbps")
            KV("FREQUENCY", "${info.frequency} MHz  CH ${wifiChannel(info.frequency)}")
            if (Build.VERSION.SDK_INT >= 30) {
                KV("STANDARD", when (info.wifiStandard) { 4 -> "Wi-Fi 4 (n)"; 5 -> "Wi-Fi 5 (ac)"; 6 -> "Wi-Fi 6 (ax)"; 8 -> "Wi-Fi 7 (be)"; 1 -> "Legacy"; else -> "Unknown" })
            }
            KV("BSSID", info.bssid ?: "N/A")
            val ip = info.ipAddress
            KV("WIFI IP", "${ip and 0xff}.${(ip shr 8) and 0xff}.${(ip shr 16) and 0xff}.${(ip shr 24) and 0xff}")
        } else {
            KV("CONNECTION", "NOT CONNECTED", cWarn)
        }
        if (!hasLoc) {
            T("Location permission is needed to read the network name and scan nearby networks.", 10f, cWarn, lines = 3, mod = Modifier.padding(top = 6.dp))
            Chip("GRANT LOCATION", false, Modifier.fillMaxWidth().padding(top = 6.dp)) { Perms.request(*Perms.location) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Chip(if (scanning) "SCANNING..." else "SCAN NEARBY NETWORKS", false, Modifier.weight(1f)) {
                if (!scanning) {
                    scanning = true
                    val ok = try { wm.startScan() } catch (e: Exception) { false }
                    if (!ok) scanning = false
                    try { results = wm.scanResults ?: emptyList() } catch (e: SecurityException) { }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Chip("TURN ON LOCATION", false, Modifier.weight(1f)) { openSettings(ctx, Settings.ACTION_LOCATION_SOURCE_SETTINGS) }
        }
        val sorted = results.sortedByDescending { it.level }
        if (sorted.isNotEmpty()) T("NEARBY (${sorted.size})", 11f, cSub, true, Modifier.padding(top = 10.dp, bottom = 4.dp))
        for (r in sorted.take(25)) {
            val isConn = connected && info != null && r.BSSID == info.bssid
            Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                T(r.SSID.ifEmpty { "<hidden network>" }, 12f, if (isConn) cGood else cText, isConn)
                T(
                    "${r.level} dBm  ${r.frequency} MHz CH${wifiChannel(r.frequency)}  ${secText(r.capabilities)}  " +
                        if (isConn) "CONNECTED" else "NOT CONNECTED",
                    9f, if (isConn) cGood else cSub, lines = 2
                )
            }
        }
    }
}

// ---------------------------------------------------------------------- mobile / sim

private class SimSnap(
    val subId: Int, val slot: Int, val carrier: String, val mccMnc: String,
    val netType: Int, val roaming: Boolean, val level: Int, val dbm: Int, val isData: Boolean
)

private fun netTypeName(t: Int): String = when (t) {
    1 -> "GPRS (2G)"; 2 -> "EDGE (2G)"; 3 -> "UMTS (3G)"; 8, 9, 10 -> "HSPA (3G)"; 15 -> "HSPA+ (3G)"
    13 -> "LTE (4G)"; 19 -> "LTE-CA (4G+)"; 20 -> "5G NR"; 16 -> "GSM (2G)"; 17 -> "TD-SCDMA (3G)"
    4, 5, 6, 7, 12, 14 -> "CDMA (3G)"; 18 -> "IWLAN"; else -> "UNKNOWN"
}

@RequiresApi(31)
private class DisplayCb(val set: (Int) -> Unit) : TelephonyCallback(), TelephonyCallback.DisplayInfoListener {
    override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
        set(telephonyDisplayInfo.overrideNetworkType)
    }
}

private fun readSims(ctx: Context): List<SimSnap> {
    val sm = ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
    val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    val subs = try { sm.activeSubscriptionInfoList ?: emptyList() } catch (e: SecurityException) { emptyList() }
    val dataId = try { SubscriptionManager.getDefaultDataSubscriptionId() } catch (e: Exception) { -1 }
    return subs.map { s ->
        val t2 = tm.createForSubscriptionId(s.subscriptionId)
        var level = -1
        var dbm = Int.MIN_VALUE
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                val ss = t2.signalStrength
                if (ss != null) {
                    level = ss.level
                    if (Build.VERSION.SDK_INT >= 29) dbm = ss.cellSignalStrengths.firstOrNull()?.dbm ?: Int.MIN_VALUE
                }
            } catch (e: Exception) { }
        }
        val type = try { t2.dataNetworkType } catch (e: SecurityException) { 0 }
        @Suppress("DEPRECATION")
        val mm = if (Build.VERSION.SDK_INT >= 29) "${s.mccString}-${s.mncString}" else "${s.mcc}-${s.mnc}"
        SimSnap(
            s.subscriptionId, s.simSlotIndex + 1, s.carrierName?.toString() ?: "?", mm, type,
            try { t2.isNetworkRoaming } catch (e: Exception) { false }, level, dbm, s.subscriptionId == dataId
        )
    }
}

@Composable
private fun MobilePanel() {
    val ctx = LocalContext.current
    val ptick = Perms.tick
    val has = remember(ptick) { Perms.hasAll(ctx, Perms.phone) }
    var sims by remember { mutableStateOf<List<SimSnap>>(emptyList()) }
    var nrOverride by remember { mutableIntStateOf(0) }
    val hist = remember { mutableStateMapOf<Int, SnapshotStateList<Float>>() }

    LaunchedEffect(has) {
        while (has) {
            val list = try { readSims(ctx) } catch (e: Exception) { emptyList() }
            sims = list
            for (s in list) {
                val v = if (s.dbm != Int.MIN_VALUE && s.dbm < 0) s.dbm.toFloat() else if (s.level >= 0) -110f + s.level * 15f else Float.NaN
                if (v.isFinite()) {
                    val l = hist.getOrPut(s.subId) { mutableStateListOf() }
                    l.add(v)
                    if (l.size > 90) l.removeAt(0)
                }
            }
            delay(2000)
        }
    }
    DisposableEffect(has) {
        var cb: Any? = null
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        if (has && Build.VERSION.SDK_INT >= 31) {
            try {
                val c = DisplayCb { nrOverride = it }
                tm.registerTelephonyCallback(ctx.mainExecutor, c)
                cb = c
            } catch (e: Exception) { }
        }
        onDispose {
            if (Build.VERSION.SDK_INT >= 31) {
                (cb as? TelephonyCallback)?.let { try { tm.unregisterTelephonyCallback(it) } catch (e: Exception) { } }
            }
        }
    }

    Panel("MOBILE NETWORK AND SIM") {
        if (!has) {
            T("Phone permission is needed to read SIM and signal info.", 10f, cWarn, lines = 2)
            Chip("GRANT PHONE PERMISSION", false, Modifier.fillMaxWidth().padding(top = 6.dp)) { Perms.request(*Perms.phone) }
        } else if (sims.isEmpty()) {
            T("NO ACTIVE SIM FOUND", 12f, cSub)
        }
        for (s in sims) {
            val nr = s.netType == 20 || (s.isData && nrOverride >= 3)
            val fiveG = when {
                s.netType == 20 -> "5G NR (STANDALONE)"
                s.isData && nrOverride == 4 -> "5G+ (NR ADVANCED)"
                s.isData && nrOverride == 3 -> "5G NSA (via LTE anchor)"
                else -> "NOT ON 5G"
            }
            T("SIM ${s.slot}${if (s.isData) "  [DATA]" else ""}", 12f, cMain, true, Modifier.padding(top = 8.dp, bottom = 2.dp))
            KV("CARRIER", s.carrier)
            KV("MCC-MNC", s.mccMnc)
            KV("NETWORK", netTypeName(s.netType))
            KV("5G STATUS", fiveG, if (nr) cGood else cSub)
            KV("ROAMING", if (s.roaming) "YES" else "NO")
            KV("SIGNAL", (if (s.dbm != Int.MIN_VALUE) "${s.dbm} dBm  " else "") + (if (s.level >= 0) "level ${s.level}/4" else "N/A"))
            val l = hist[s.subId]
            if (l != null) LineGraph(listOf(Series(l, if (s.slot == 1) cMain else cWarn)), -120f, -50f, " dBm", 70.dp)
        }
        val p = Monitor.props
        T("MODEM", 11f, cSub, true, Modifier.padding(top = 12.dp, bottom = 2.dp))
        KV("BASEBAND", p["gsm.version.baseband"]?.ifBlank { null } ?: p["ro.baseband"]?.ifBlank { null } ?: "N/A")
        KV("RIL", p["gsm.version.ril-impl"]?.ifBlank { null } ?: "N/A")
        KV("PLATFORM / SoC", Monitor.cpuStatic?.name ?: "?")
        T("Android does not expose the exact modem chip name. Most phones use a modem inside the SoC; the baseband string above usually identifies it.", 9f, cSub, lines = 4)
    }
}

// ---------------------------------------------------------------------- bluetooth

private class BtFound(val name: String, val addr: String, val rssi: Int)

private fun btConnected(d: BluetoothDevice): Boolean = try {
    d.javaClass.getMethod("isConnected").invoke(d) as Boolean
} catch (e: Throwable) { false }

@Suppress("DEPRECATION", "MissingPermission")
@Composable
private fun BluetoothPanel() {
    val ctx = LocalContext.current
    val ptick = Perms.tick
    val has = remember(ptick) { Perms.hasAll(ctx, Perms.bluetooth) }
    val ad: BluetoothAdapter? = remember { (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter }
    val found = remember { mutableStateListOf<BtFound>() }
    var scanning by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val rec = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        val rssi = i.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                        if (d != null && found.none { it.addr == d.address }) {
                            val n = try { d.name } catch (e: SecurityException) { null }
                            found.add(BtFound(n ?: "<unnamed>", d.address, rssi))
                        }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> scanning = false
                }
            }
        }
        val f = IntentFilter()
        f.addAction(BluetoothDevice.ACTION_FOUND)
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        registerSys(ctx, rec, f)
        onDispose {
            try { ctx.unregisterReceiver(rec) } catch (e: Exception) { }
            try { if (has) ad?.cancelDiscovery() } catch (e: Exception) { }
        }
    }

    Panel("BLUETOOTH") {
        if (ad == null) {
            T("NO BLUETOOTH ADAPTER", 12f, cBad)
            return@Panel
        }
        KV("ADAPTER", if (ad.isEnabled) "ON" else "OFF", if (ad.isEnabled) cGood else cBad)
        KV("BLE SUPPORT", if (ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) "YES" else "NO")
        if (!has) {
            T("Nearby devices permission is needed.", 10f, cWarn)
            Chip("GRANT BLUETOOTH PERMISSION", false, Modifier.fillMaxWidth().padding(top = 6.dp)) { Perms.request(*Perms.bluetooth, *Perms.location) }
            return@Panel
        }
        KV("AUDIO (A2DP) LINK", if (try { ad.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED } catch (e: Exception) { false }) "CONNECTED" else "NONE")
        val paired = try { ad.bondedDevices?.toList() ?: emptyList() } catch (e: SecurityException) { emptyList() }
        T("PAIRED (${paired.size})", 11f, cSub, true, Modifier.padding(top = 8.dp, bottom = 2.dp))
        for (d in paired) {
            val con = btConnected(d)
            Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                T(d.name ?: "<unnamed>", 12f, if (con) cGood else cText, con)
                T("${d.address}  ${if (con) "CONNECTED" else "NOT CONNECTED"}", 9f, if (con) cGood else cSub)
            }
        }
        Chip(if (scanning) "SCANNING..." else "SCAN NEARBY DEVICES", false, Modifier.fillMaxWidth().padding(top = 8.dp)) {
            if (!scanning) {
                found.clear()
                scanning = try { ad.startDiscovery() } catch (e: Exception) { false }
            }
        }
        if (found.isNotEmpty()) T("NEARBY (${found.size})", 11f, cSub, true, Modifier.padding(top = 8.dp, bottom = 2.dp))
        for (d in found) {
            Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                T(d.name, 12f, cText)
                T("${d.addr}  ${if (d.rssi != Short.MIN_VALUE.toInt()) "${d.rssi} dBm" else ""}", 9f, cSub)
            }
        }
    }
}

// ---------------------------------------------------------------------- gps

private val constNames = listOf("UNKNOWN", "GPS", "SBAS", "GLONASS", "QZSS", "BEIDOU", "GALILEO", "IRNSS")
private val constColors = listOf(Color.Gray, Color(0xFF00E5FF), Color(0xFFB388FF), Color(0xFFFF3D3D), Color(0xFFFF80AB), Color(0xFFFFB300), Color(0xFF00FF66), Color(0xFF82B1FF))

@Suppress("DEPRECATION", "MissingPermission")
@Composable
private fun GpsPanel(tick: Int) {
    val ctx = LocalContext.current
    val ptick = Perms.tick
    val has = remember(ptick) { Perms.has(ctx, Manifest.permission.ACCESS_FINE_LOCATION) }
    val lm = remember { ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager }
    var visible by remember { mutableIntStateOf(0) }
    var used by remember { mutableIntStateOf(0) }
    var types by remember { mutableStateOf(List(8) { 0 }) }
    var fix by remember { mutableStateOf<Location?>(null) }

    DisposableEffect(has) {
        val gnss = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                val n = status.satelliteCount
                var u = 0
                val t = IntArray(8)
                for (i in 0 until n) {
                    if (status.usedInFix(i)) u++
                    t[status.getConstellationType(i).coerceIn(0, 7)]++
                }
                visible = n
                used = u
                types = t.toList()
            }
        }
        val ll = object : LocationListener {
            override fun onLocationChanged(l: Location) { fix = l }
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        if (has) {
            try { lm.registerGnssStatusCallback(gnss, Handler(Looper.getMainLooper())) } catch (e: Exception) { }
            try { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, ll) } catch (e: Exception) { }
        }
        onDispose {
            try { lm.unregisterGnssStatusCallback(gnss) } catch (e: Exception) { }
            try { lm.removeUpdates(ll) } catch (e: Exception) { }
        }
    }

    Panel("GPS / GNSS") {
        val on = try { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) } catch (e: Exception) { false }
        KV("GPS", if (on) "ENABLED" else "DISABLED", if (on) cGood else cBad)
        if (!has) {
            T("Location permission is needed for satellite data.", 10f, cWarn)
            Chip("GRANT LOCATION", false, Modifier.fillMaxWidth().padding(top = 6.dp)) { Perms.request(*Perms.location) }
            return@Panel
        }
        if (!on) Chip("OPEN LOCATION SETTINGS", false, Modifier.fillMaxWidth().padding(vertical = 6.dp)) { openSettings(ctx, Settings.ACTION_LOCATION_SOURCE_SETTINGS) }
        KV("SATELLITES IN VIEW", "$visible")
        KV("USED IN FIX", "$used", if (used >= 4) cGood else cWarn)
        val f = fix
        if (f != null) {
            KV("ACCURACY", "${f1(f.accuracy)} m")
            KV("LATITUDE", String.format(java.util.Locale.US, "%.6f", f.latitude))
            KV("LONGITUDE", String.format(java.util.Locale.US, "%.6f", f.longitude))
            if (f.hasAltitude()) KV("ALTITUDE", "${f1(f.altitude.toFloat())} m")
            if (f.hasSpeed()) KV("SPEED", "${f1(f.speed * 3.6f)} km/h")
        } else {
            KV("FIX", if (on) "SEARCHING (go outdoors)" else "NO FIX", cWarn)
        }
        val slices = types.mapIndexedNotNull { i, c -> if (c > 0) Slice(constNames[i], c.toFloat(), constColors[i], "$c") else null }
        if (slices.isNotEmpty()) {
            T("CONSTELLATIONS", 11f, cSub, true, Modifier.padding(top = 8.dp, bottom = 4.dp))
            PieWithLegend(slices, "$visible")
        }
    }
}
