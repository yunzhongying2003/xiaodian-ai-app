package com.xiaodian.ai

// 后台保活引导：
// - 电池优化白名单：ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS（需要 manifest 权限
//   REQUEST_IGNORE_BATTERY_OPTIMIZATIONS），失败退到电池优化列表页，再失败打开应用详情页。
// - 国产系统「自启动」：按厂商依次尝试已知页面（小米 / 华为 / 荣耀 / OPPO / 一加 / realme / vivo），
//   全部失败打开应用详情页。厂商页面的类名会随系统版本变化，所以只能逐个 try。
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

object BackgroundGuide {
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return try { pm.isIgnoringBatteryOptimizations(context.packageName) } catch (_: Exception) { false }
    }

    /** 依次尝试的页面：直接弹「允许后台运行」→ 电池优化列表 → 应用详情。调用方逐个 try 启动。 */
    fun batteryIntents(context: Context): List<Intent> = listOf(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        appDetailsIntent(context),
    )

    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    private val autoStartComponents: List<Pair<String, String>> = listOf(
        // 小米 / 红米（MIUI、HyperOS）
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        // 华为
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
        // 荣耀
        "com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        // OPPO / 一加 / realme（ColorOS）
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
        "com.oplus.safecenter" to "com.oplus.safecenter.permission.startup.StartupAppListActivity",
        "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        // vivo / iQOO
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
    )

    /** 打开厂商自启动设置；返回 true 表示打开了厂商页面，false 表示退回了应用详情页。 */
    fun openAutoStart(context: Context): Boolean {
        val brand = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
        // 本机品牌相关的排在前面，其余的也试一遍（贴牌机的包名不一定和品牌一致）。
        val ordered = autoStartComponents.sortedBy { (pkg, _) -> if (matchesBrand(brand, pkg)) 0 else 1 }
        for ((pkg, cls) in ordered) {
            try {
                val i = Intent().setComponent(ComponentName(pkg, cls)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(i)
                return true
            } catch (_: Exception) {
            }
        }
        try {
            context.startActivity(appDetailsIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
        return false
    }

    private fun matchesBrand(brand: String, pkg: String): Boolean = when {
        pkg.contains("miui") -> brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco")
        pkg.contains("hihonor") -> brand.contains("honor")
        pkg.contains("huawei") -> brand.contains("huawei") || brand.contains("honor")
        pkg.contains("coloros") || pkg.contains("oplus") || pkg.contains("oppo") ->
            brand.contains("oppo") || brand.contains("oneplus") || brand.contains("realme")
        pkg.contains("vivo") || pkg.contains("iqoo") -> brand.contains("vivo") || brand.contains("iqoo")
        else -> false
    }
}
