package com.device.mux.manager

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.net.TrafficStats
import android.os.StatFs
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

class CoreStat(val idx: Int, val online: Boolean, val freqKhz: Long, val maxKhz: Long, val load: Float, val tempC: Float?)
class Zone(val name: String, val tempC: Float)
class CpuStatic(val name: String, val hardware: String, val abi: String, val cores: Int, val governor: String, val clusters: String)
class RamSnap(
    val total: Long, val avail: Long, val threshold: Long, val low: Boolean,
    val cached: Long, val swapTotal: Long, val swapFree: Long
)
class StorSnap(val label: String, val total: Long, val free: Long)
class BattSnap(
    val level: Int, val status: Int, val plugged: Int, val health: Int, val tempC: Float,
    val voltageV: Float, val currentMa: Float, val chargeMah: Float, val tech: String,
    val present: Boolean, val cycles: Int, val remainMs: Long
) {
    val charging: Boolean get() = plugged != 0 && status != BatteryManager.BATTERY_STATUS_DISCHARGING &&
        status != BatteryManager.BATTERY_STATUS_NOT_CHARGING
    val full: Boolean get() = status == BatteryManager.BATTERY_STATUS_FULL
}

/** Live system data, sampled once per second while the app is on screen. */
object Monitor {
    var cpuStatic by mutableStateOf<CpuStatic?>(null)
    var cores by mutableStateOf<List<CoreStat>>(emptyList())
    var cpuLoad by mutableFloatStateOf(0f)
    var cpuTemp by mutableFloatStateOf(Float.NaN)
    var cpuTempSource by mutableStateOf("")
    var loadSource by mutableStateOf("")
    var zones by mutableStateOf<List<Zone>>(emptyList())
    var ram by mutableStateOf<RamSnap?>(null)
    var storage by mutableStateOf<List<StorSnap>>(emptyList())
    var batt by mutableStateOf<BattSnap?>(null)
    var designMah by mutableFloatStateOf(-1f)
    var props by mutableStateOf<Map<String, String>>(emptyMap())
    var rxKBs by mutableFloatStateOf(0f)
    var txKBs by mutableFloatStateOf(0f)

    val cpuHist = mutableStateListOf<Float>()
    val cpuTempHist = mutableStateListOf<Float>()
    val ramHist = mutableStateListOf<Float>()
    val battLevelHist = mutableStateListOf<Float>()
    val battTempHist = mutableStateListOf<Float>()
    val battVoltHist = mutableStateListOf<Float>()
    val battCurHist = mutableStateListOf<Float>()
    val battPowerHist = mutableStateListOf<Float>()
    val rxHist = mutableStateListOf<Float>()
    val txHist = mutableStateListOf<Float>()

    private const val CPU = "/sys/devices/system/cpu"
    private var coreCount = 0
    private var maxFreq = LongArray(0)
    private var lastStat: Map<Int, LongArray> = emptyMap()
    private var zonePaths: List<Pair<String, String>> = emptyList()
    private var lastRx = -1L
    private var lastTx = -1L
    private var lastT = 0L

    private fun push(l: SnapshotStateList<Float>, v: Float, cap: Int = 120) {
        if (!v.isFinite()) return
        l.add(v)
        while (l.size > cap) l.removeAt(0)
    }

    suspend fun run(ctx: Context, active: () -> Boolean) = withContext(Dispatchers.IO) {
        try { loadStatic(ctx) } catch (e: Exception) { }
        var n = 0
        while (isActive) {
            if (active()) {
                try { sampleCpu() } catch (e: Exception) { }
                try { sampleRam(ctx) } catch (e: Exception) { }
                try { sampleBatt(ctx) } catch (e: Exception) { }
                try { sampleTraffic() } catch (e: Exception) { }
                if (n % 5 == 0) try { sampleStorage(ctx) } catch (e: Exception) { }
                n++
            }
            delay(1000)
        }
    }

    // ---------------------------------------------------------------- static

    private fun loadStatic(ctx: Context) {
        val m = HashMap<String, String>()
        try {
            val p = Runtime.getRuntime().exec("getprop")
            p.inputStream.bufferedReader().forEachLine { l ->
                val i = l.indexOf("]: [")
                if (l.startsWith("[") && i > 0) m[l.substring(1, i)] = l.substring(i + 4).removeSuffix("]")
            }
        } catch (e: Exception) { }
        props = m

        var n = Runtime.getRuntime().availableProcessors()
        val possible = readFile("$CPU/possible")
        if (possible != null && possible.contains("-")) {
            possible.substringAfter("-").toIntOrNull()?.let { if (it + 1 > n) n = it + 1 }
        }
        coreCount = n
        maxFreq = LongArray(n) { readFile("$CPU/cpu$it/cpufreq/cpuinfo_max_freq")?.toLongOrNull() ?: 0L }

        var cpuName = ""
        try {
            File("/proc/cpuinfo").forEachLine { l ->
                val k = l.substringBefore(":").trim().lowercase()
                if (cpuName.isEmpty() && (k == "hardware" || k == "model name")) cpuName = l.substringAfter(":").trim()
            }
        } catch (e: Exception) { }
        val soc = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}".trim() else ""
        val name = listOf(cpuName, soc, m["ro.soc.model"] ?: "", Build.HARDWARE).firstOrNull { it.isNotBlank() && it != "unknown" } ?: "UNKNOWN"
        val clusters = maxFreq.filter { it > 0 }.groupBy { it }.toSortedMap()
            .map { "${it.value.size}x${it.key / 1000}MHz" }.joinToString(" + ").ifEmpty { "N/A" }
        cpuStatic = CpuStatic(
            name = name,
            hardware = "${Build.HARDWARE} / ${Build.BOARD}",
            abi = "${System.getProperty("os.arch") ?: "?"} (${Build.SUPPORTED_ABIS.joinToString(", ")})",
            cores = n,
            governor = readFile("$CPU/cpu0/cpufreq/scaling_governor") ?: "N/A",
            clusters = clusters
        )

        val dir = File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }
        zonePaths = (dir ?: emptyArray()).sortedBy { it.name.removePrefix("thermal_zone").toIntOrNull() ?: 0 }
            .map { (readFile("${it.path}/type") ?: it.name) to "${it.path}/temp" }

        designMah = try {
            val c = Class.forName("com.android.internal.os.PowerProfile")
            val o = c.getConstructor(Context::class.java).newInstance(ctx)
            (c.getMethod("getBatteryCapacity").invoke(o) as Double).toFloat()
        } catch (e: Throwable) { -1f }
    }

    // ---------------------------------------------------------------- CPU

    private fun readProcStat(): Map<Int, LongArray>? = try {
        val m = HashMap<Int, LongArray>()
        File("/proc/stat").forEachLine { l ->
            if (l.startsWith("cpu") && l.length > 3 && l[3].isDigit()) {
                val p = l.split(Regex("\\s+"))
                val idx = p[0].substring(3).toInt()
                val v = p.drop(1).mapNotNull { it.toLongOrNull() }
                if (v.size >= 5) m[idx] = longArrayOf(v[3] + v[4], v.take(8).sum())
            }
        }
        if (m.isEmpty()) null else m
    } catch (e: Exception) { null }

    private fun normTemp(v: Float): Float = when {
        v > 1000f -> v / 1000f
        v > 150f -> v / 10f
        else -> v
    }

    private fun tempForCore(i: Int, z: List<Zone>): Float? {
        val re = Regex("(cpu|core|cpuss)[-_]?$i(?![0-9])")
        return z.firstOrNull { re.containsMatchIn(it.name.lowercase()) }?.tempC
    }

    private fun sampleCpu() {
        val n = coreCount
        if (n == 0) return
        val stat = readProcStat()
        val loads = FloatArray(n) { -1f }
        if (stat != null && lastStat.isNotEmpty()) {
            for (i in 0 until n) {
                val a = lastStat[i]
                val b = stat[i]
                if (a != null && b != null) {
                    val dt = b[1] - a[1]
                    val di = b[0] - a[0]
                    if (dt > 0) loads[i] = (1f - di.toFloat() / dt).coerceIn(0f, 1f)
                }
            }
        }
        if (stat != null) lastStat = stat
        val usedStat = loads.any { it >= 0f }
        loadSource = if (usedStat) "/proc/stat (real usage)" else "CPU FREQUENCY (Android blocks /proc/stat)"

        val zs = ArrayList<Zone>()
        for ((type, path) in zonePaths) {
            val t = readFile(path)?.toFloatOrNull()?.let { normTemp(it) } ?: continue
            if (t > -20f && t < 150f) zs.add(Zone(type, t))
        }
        zones = zs

        val list = ArrayList<CoreStat>()
        var sum = 0f
        var cnt = 0
        for (i in 0 until n) {
            val on = readFile("$CPU/cpu$i/online") != "0"
            val f = if (on) readFile("$CPU/cpu$i/cpufreq/scaling_cur_freq")?.toLongOrNull() ?: 0L else 0L
            val mx = maxFreq.getOrElse(i) { 0L }
            var l = loads[i]
            if (l < 0f) l = if (f > 0 && mx > 0) (f.toFloat() / mx).coerceIn(0f, 1f) else 0f
            if (!on) l = 0f
            list.add(CoreStat(i, on, f, mx, l, tempForCore(i, zs)))
            if (on) { sum += l; cnt++ }
        }
        cores = list
        cpuLoad = if (cnt > 0) sum / cnt else 0f
        push(cpuHist, cpuLoad * 100f)

        val cpuZones = zs.filter { z ->
            val nm = z.name.lowercase()
            nm.contains("cpu") || nm.contains("soc") || nm.contains("cluster") || nm.contains("tsens") || nm.contains("mtk")
        }
        val t = cpuZones.maxOfOrNull { it.tempC }
        if (t != null) {
            cpuTemp = t
            cpuTempSource = "THERMAL ZONE"
        } else {
            cpuTemp = batt?.tempC ?: Float.NaN
            cpuTempSource = "BATTERY SENSOR (CPU zone not readable)"
        }
        push(cpuTempHist, cpuTemp)
    }

    // ---------------------------------------------------------------- RAM / storage

    private fun meminfoKb(lines: List<String>, key: String): Long =
        lines.firstOrNull { it.startsWith("$key:") }?.substringAfter(":")?.trim()?.substringBefore(" ")?.toLongOrNull() ?: 0L

    private fun sampleRam(ctx: Context) {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val lines = try { File("/proc/meminfo").readLines() } catch (e: Exception) { emptyList() }
        ram = RamSnap(
            mi.totalMem, mi.availMem, mi.threshold, mi.lowMemory,
            meminfoKb(lines, "Cached") * 1024, meminfoKb(lines, "SwapTotal") * 1024, meminfoKb(lines, "SwapFree") * 1024
        )
        if (mi.totalMem > 0) push(ramHist, 100f * (mi.totalMem - mi.availMem) / mi.totalMem)
    }

    private fun sampleStorage(ctx: Context) {
        val list = ArrayList<StorSnap>()
        fun add(label: String, path: String) {
            try {
                val s = StatFs(path)
                list.add(StorSnap(label, s.totalBytes, s.availableBytes))
            } catch (e: Exception) { }
        }
        add("INTERNAL", Environment.getDataDirectory().path)
        ctx.getExternalFilesDirs(null).drop(1).filterNotNull().forEachIndexed { i, f -> add("EXTERNAL ${i + 1}", f.path) }
        storage = list
    }

    // ---------------------------------------------------------------- battery

    private fun sampleBatt(ctx: Context) {
        val it = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val pct = if (scale > 0) level * 100 / scale else level
        val status = it.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val plugged = it.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val health = it.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)
        val temp = it.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
        val volt = it.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) / 1000f
        val tech = it.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: ""
        val present = it.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true)
        val cycles = it.getIntExtra("android.os.extra.CYCLE_COUNT", -1)

        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val cur = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val cc = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val ma = when {
            cur == Long.MIN_VALUE || cur == 0L -> 0f
            abs(cur) < 20000L -> abs(cur).toFloat()
            else -> abs(cur) / 1000f
        }
        val chargeMah = if (cc > 0 && cc != Long.MIN_VALUE) cc / 1000f else -1f
        val charging = plugged != 0 && status != BatteryManager.BATTERY_STATUS_DISCHARGING &&
            status != BatteryManager.BATTERY_STATUS_NOT_CHARGING
        val remain = when {
            charging && Build.VERSION.SDK_INT >= 28 -> bm.computeChargeTimeRemaining()
            !charging && ma > 20f && chargeMah > 0f -> (chargeMah / ma * 3_600_000f).toLong()
            else -> -1L
        }
        val s = BattSnap(pct, status, plugged, health, temp, volt, ma, chargeMah, tech, present, cycles, remain)
        batt = s
        val signed = if (charging) ma else -ma
        push(battLevelHist, pct.toFloat(), 180)
        push(battTempHist, temp, 180)
        push(battVoltHist, volt, 180)
        push(battCurHist, signed, 180)
        push(battPowerHist, volt * signed / 1000f, 180)
    }

    // ---------------------------------------------------------------- traffic

    private fun sampleTraffic() {
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        val now = System.currentTimeMillis()
        if (rx >= 0 && tx >= 0 && lastRx >= 0 && now > lastT) {
            val dt = (now - lastT) / 1000f
            rxKBs = ((rx - lastRx) / 1024f / dt).coerceAtLeast(0f)
            txKBs = ((tx - lastTx) / 1024f / dt).coerceAtLeast(0f)
            push(rxHist, rxKBs)
            push(txHist, txKBs)
        }
        lastRx = rx
        lastTx = tx
        lastT = now
    }
}
