package com.device.mux.manager

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

enum class JunkMode { JUNK, TEMP }
class JunkItem(val file: File, val size: Long, val kind: String)
class AppRow(val pkg: String, val label: String, val version: String, val system: Boolean, val size: Long, val updated: Long, val src: String)
class ApkRow(val file: File, val label: String, val pkg: String, val version: String, val installed: String?)

object Cleaner {

    private val standardDirs = setOf(
        "download", "dcim", "pictures", "music", "movies", "documents", "alarms",
        "notifications", "ringtones", "podcasts", "android", "recordings", "audiobooks"
    )

    private fun root(): File = Environment.getExternalStorageDirectory()

    private fun skipPaths(): List<String> = listOf(File(root(), "Android/data").path, File(root(), "Android/obb").path)

    fun dirSize(f: File): Long = try {
        if (f.isFile) f.length() else f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    } catch (e: Exception) { 0L }

    // ------------------------------------------------------------------ RAM

    suspend fun ramClean(ctx: Context): Long = withContext(Dispatchers.IO) {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        fun avail(): Long {
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            return mi.availMem
        }
        val before = avail()
        for (ai in ctx.packageManager.getInstalledApplications(0)) {
            if (ai.packageName == ctx.packageName) continue
            if ((ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0) continue
            try { am.killBackgroundProcesses(ai.packageName) } catch (e: Exception) { }
        }
        delay(1500)
        (avail() - before).coerceAtLeast(0L)
    }

    // ------------------------------------------------------------------ junk / temp

    suspend fun scan(ctx: Context, mode: JunkMode): List<JunkItem> = withContext(Dispatchers.IO) {
        val out = ArrayList<JunkItem>()
        val r = root()
        val skip = skipPaths()
        val tempExt = setOf("tmp", "temp", "bak", "old", "chk", "crdownload", "part", "dmp", "thumb")
        val logExt = setOf("log", "logs", "trace")
        val junkDirs = setOf(".thumbnails", "thumbnails", ".cache", "lost.dir")
        var visited = 0

        if (mode == JunkMode.TEMP) {
            listOfNotNull(ctx.cacheDir, ctx.externalCacheDir).forEach { d ->
                val s = dirSize(d)
                if (s > 0) out.add(JunkItem(d, s, "APP CACHE"))
            }
        }
        try {
            val walker = r.walkTopDown().onEnter { d ->
                if (skip.any { d.path.startsWith(it) }) {
                    false
                } else if (mode == JunkMode.JUNK && d != r && d.name.lowercase() in junkDirs) {
                    out.add(JunkItem(d, dirSize(d), "CACHE FOLDER"))
                    false
                } else true
            }.maxDepth(12)
            for (f in walker) {
                if (++visited > 300000) break
                if (f.isDirectory) {
                    if (mode == JunkMode.JUNK && f != r) {
                        val protectedTop = f.parentFile == r && f.name.lowercase() in standardDirs
                        if (!protectedTop && f.list()?.isEmpty() == true) out.add(JunkItem(f, 0L, "EMPTY FOLDER"))
                    }
                    continue
                }
                val n = f.name.lowercase()
                val ext = n.substringAfterLast('.', "")
                val hit = when (mode) {
                    JunkMode.JUNK -> if (ext in logExt || n == "thumbs.db" || n == ".ds_store") "LOG / JUNK FILE" else null
                    JunkMode.TEMP -> if (ext in tempExt || n.startsWith("~") || n.endsWith("~")) "TEMP FILE" else null
                }
                if (hit != null) out.add(JunkItem(f, f.length(), hit))
            }
        } catch (e: Exception) { }
        out
    }

    suspend fun delete(items: List<JunkItem>): Long = withContext(Dispatchers.IO) {
        var freed = 0L
        for (it in items) {
            try {
                if (it.kind == "APP CACHE") {
                    it.file.listFiles()?.forEach { c -> c.deleteRecursively() }
                    freed += it.size
                } else if (it.file.deleteRecursively()) {
                    freed += it.size
                }
            } catch (e: Exception) { }
        }
        freed
    }

    // ------------------------------------------------------------------ apps

    suspend fun loadApps(ctx: Context): List<AppRow> = withContext(Dispatchers.IO) {
        val pm = ctx.packageManager
        pm.getInstalledPackages(0).mapNotNull { pi ->
            val ai = pi.applicationInfo ?: return@mapNotNull null
            AppRow(
                pkg = pi.packageName,
                label = try { pm.getApplicationLabel(ai).toString() } catch (e: Exception) { pi.packageName },
                version = pi.versionName ?: "",
                system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                size = try { File(ai.sourceDir).length() } catch (e: Exception) { 0L },
                updated = pi.lastUpdateTime,
                src = ai.sourceDir ?: ""
            )
        }.sortedBy { it.label.lowercase() }
    }

    suspend fun backupApk(app: AppRow): String = withContext(Dispatchers.IO) {
        try {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DeviceManager")
            dir.mkdirs()
            val dest = File(dir, "${app.pkg}_${app.version}.apk")
            File(app.src).copyTo(dest, overwrite = true)
            "SAVED: Download/DeviceManager/${dest.name}"
        } catch (e: Exception) {
            "BACKUP FAILED (grant file access)"
        }
    }

    // ------------------------------------------------------------------ apk files

    suspend fun scanApks(ctx: Context): List<ApkRow> = withContext(Dispatchers.IO) {
        val pm = ctx.packageManager
        val out = ArrayList<ApkRow>()
        val skip = skipPaths()
        var visited = 0
        try {
            val walker = root().walkTopDown().onEnter { d -> skip.none { d.path.startsWith(it) } }.maxDepth(12)
            for (f in walker) {
                if (++visited > 300000) break
                if (!f.isFile || !f.name.endsWith(".apk", true)) continue
                val pi = try { pm.getPackageArchiveInfo(f.path, 0) } catch (e: Exception) { null }
                val ai = pi?.applicationInfo
                if (pi == null || ai == null) {
                    out.add(ApkRow(f, f.nameWithoutExtension, "?", "?", null))
                    continue
                }
                ai.sourceDir = f.path
                ai.publicSourceDir = f.path
                val label = try { pm.getApplicationLabel(ai).toString() } catch (e: Exception) { f.nameWithoutExtension }
                val inst = try { pm.getPackageInfo(pi.packageName, 0).versionName } catch (e: Exception) { null }
                out.add(ApkRow(f, label, pi.packageName, pi.versionName ?: "?", inst))
            }
        } catch (e: Exception) { }
        out.sortedBy { it.label.lowercase() }
    }

    fun installApk(ctx: Context, f: File) {
        try {
            if (!ctx.packageManager.canRequestPackageInstalls()) {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return
            }
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) { }
    }
}
