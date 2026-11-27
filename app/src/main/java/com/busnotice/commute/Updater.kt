package com.busnotice.commute

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 检查构建所在 GitHub 仓库的 Release 有没有新版本（仓库由 CI 注入，本地构建不检查）。网络不通就静默跳过。
 * 默认只看标记为最新的正式版；开启「接收测试版更新」后同时考虑预发布版本，按语义化版本取最高者。
 */
object Updater {
    private val API_BASE get() = "https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases"
    private const val CHECK_GAP = 15 * 60_000L
    private const val PREFS = "updater"
    private const val KEY_PRERELEASE = "prerelease"
    private const val KEY_SKIP_NAME = "skip_name"

    /** 首页点「检查更新」时 +1，弹窗那边强制查一次 */
    val force = androidx.compose.runtime.mutableIntStateOf(0)
    /** 上次检查是否连上了 GitHub */
    @Volatile var lastOk = true

    /**
     * build = versionCode（主*10000+次*100+修订，测试版与同号正式版相同），name = 显示用的版本号（不含 v），
     * version = 用于比较的语义化版本，prerelease = 是否为 GitHub 预发布版本
     */
    data class Release(
        val build: Int,
        val apkUrl: String,
        val notes: String,
        val name: String,
        val version: SemVer,
        val prerelease: Boolean = version.isPrerelease,
    )

    /** v1.2.3 → 10203；带预发布后缀的标签只取主、次、修订号（v1.2.3-beta.1 → 10203） */
    fun codeOf(tag: String): Int? = SemVer.parse(tag)?.code

    /** 是否接收测试版更新（默认关闭） */
    fun includePrerelease(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PRERELEASE, false)

    /** 切换后清除节流时间，下一次检查立即按新设置进行 */
    fun setIncludePrerelease(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_PRERELEASE, on).remove("last").apply()

    /** 请求地址：只看正式版时取 /releases/latest，接收测试版时取最近 10 个 Release */
    fun apiUrl(includePrerelease: Boolean): String =
        if (includePrerelease) "$API_BASE?per_page=10" else "$API_BASE/latest"

    /** 解析单个 Release：跳过草稿、标签不是语义化版本或没有 APK 附件的 */
    fun parseRelease(r: JSONObject): Release? {
        if (r.optBoolean("draft", false)) return null
        val tag = r.optString("tag_name")
        val v = SemVer.parse(tag) ?: return null
        val assets = r.optJSONArray("assets") ?: return null
        var url: String? = null
        for (j in 0 until assets.length()) {
            val a = assets.optJSONObject(j) ?: continue
            if (a.optString("name").endsWith(".apk")) { url = a.optString("browser_download_url"); break }
        }
        if (url.isNullOrEmpty()) return null
        val pre = r.optBoolean("prerelease", false) || v.isPrerelease
        return Release(v.code, url, r.optString("body").take(600), tag.trim().removePrefix("v"), v, pre)
    }

    /** 从 Release 列表中取语义化版本最高的一个；includePrerelease=false 时只考虑正式版 */
    fun pickLatest(list: JSONArray, includePrerelease: Boolean): Release? {
        var best: Release? = null
        for (i in 0 until list.length()) {
            val r = list.optJSONObject(i)?.let(::parseRelease) ?: continue
            if (r.prerelease && !includePrerelease) continue
            if (best == null || r.version > best.version) best = r
        }
        return best
    }

    /**
     * 候选版本是否比当前安装的版本新：当前版本名可解析时按语义化版本比较（测试版低于同号正式版），
     * 否则按 versionCode 比较
     */
    fun isNewer(candidate: Release, currentName: String, currentBuild: Int): Boolean {
        val cur = SemVer.parse(currentName) ?: return candidate.build > currentBuild
        return candidate.version > cur
    }

    fun currentName(ctx: Context): String = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"


    fun currentBuild(ctx: Context): Int {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
    }

    /** 有比当前更新的版本就返回它；force=false 时 15 分钟内只查一次 */
    suspend fun check(ctx: Context, force: Boolean = false): Release? = withContext(Dispatchers.IO) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val includePre = sp.getBoolean(KEY_PRERELEASE, false)
        val now = System.currentTimeMillis()
        if (BuildConfig.UPDATE_REPO.isBlank()) return@withContext null
        if (!force && now - sp.getLong("last", 0) < CHECK_GAP) return@withContext null
        lastOk = false
        runCatching {
            val c = URL(apiUrl(includePre)).openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            val txt = c.inputStream.bufferedReader().use { it.readText() }
            sp.edit().putLong("last", now).apply()
            lastOk = true
            // 只看正式版时为单个对象（GitHub 标记的最新版本），接收测试版时为列表
            val best = if (includePre) pickLatest(JSONArray(txt), includePrerelease = true)
                else parseRelease(JSONObject(txt))?.takeIf { !it.prerelease }
            best?.takeIf {
                isNewer(it, currentName(ctx), currentBuild(ctx)) && (force || it.name != sp.getString(KEY_SKIP_NAME, null))
            }
        }.getOrNull()
    }

    /** 「跳过此版」：按版本名记录，测试版与同号正式版分别记录 */
    fun skip(ctx: Context, r: Release) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SKIP_NAME, r.name).apply()

    /** 下载 APK，onProgress 给 0..1；成功返回文件 */
    suspend fun download(ctx: Context, r: Release, onProgress: (Float) -> Unit): File? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(ctx.cacheDir, "update").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            val f = File(dir, "CommuteCapsule-${r.build}.apk")
            val c = URL(r.apkUrl).openConnection() as HttpURLConnection
            c.connectTimeout = 15000; c.readTimeout = 30000; c.instanceFollowRedirects = true
            val total = c.contentLengthLong
            c.inputStream.use { inp ->
                f.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024); var done = 0L
                    while (true) {
                        val k = inp.read(buf); if (k < 0) break
                        out.write(buf, 0, k); done += k
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
            f
        }.getOrNull()
    }

    /** 拉起系统安装器；没有「安装未知应用」权限时先跳去设置，返回 false */
    fun install(ctx: Context, f: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}
