package com.device.mux.manager

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import java.io.File
import java.util.Locale

object Prefs {
    private lateinit var sp: SharedPreferences
    var accent by mutableIntStateOf(0)

    fun init(ctx: Context) {
        sp = ctx.applicationContext.getSharedPreferences("mux_manager", Context.MODE_PRIVATE)
        accent = sp.getInt("accent", 0)
    }

    fun updateAccent(i: Int) {
        accent = i
        sp.edit().putInt("accent", i).apply()
    }
}

/** Runtime permission helper. Screens read [tick] so they refresh after a grant. */
object Perms {
    var tick by mutableIntStateOf(0)
    var launcher: ((Array<String>) -> Unit)? = null

    fun has(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun request(vararg p: String) { launcher?.invoke(arrayOf(*p)) }

    val location = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    val phone = arrayOf(Manifest.permission.READ_PHONE_STATE)
    val bluetooth: Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN) else emptyArray()

    fun hasAll(ctx: Context, list: Array<String>) = list.all { has(ctx, it) }

    fun allFiles(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else has(ctx, Manifest.permission.READ_EXTERNAL_STORAGE) && (Build.VERSION.SDK_INT >= 30 || has(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE))

    fun requestAllFiles(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                try {
                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e2: Exception) { }
            }
        } else {
            request(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }
}

fun fmtBytes(b: Long): String {
    if (b < 1024) return "$b B"
    val u = arrayOf("KB", "MB", "GB", "TB")
    var v = b / 1024.0
    var i = 0
    while (v >= 1024 && i < u.size - 1) { v /= 1024; i++ }
    return String.format(Locale.US, "%.1f %s", v, u[i])
}

fun f0(v: Float): String = String.format(Locale.US, "%.0f", v)
fun f1(v: Float): String = String.format(Locale.US, "%.1f", v)
fun f2(v: Float): String = String.format(Locale.US, "%.2f", v)
fun fmtPct(f: Float): String = "${(f * 100f).toInt()}%"

fun readFile(path: String): String? = try { File(path).readText().trim() } catch (e: Exception) { null }

fun fmtDuration(ms: Long): String {
    val s = ms / 1000
    val d = s / 86400
    val h = (s % 86400) / 3600
    val m = (s % 3600) / 60
    return if (d > 0) "${d}d ${h}h ${m}m" else "${h}h ${m}m ${s % 60}s"
}