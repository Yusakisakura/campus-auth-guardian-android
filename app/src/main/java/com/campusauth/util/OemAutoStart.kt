package com.campusauth.util

import android.os.Build

/**
 * Detect OEM-specific background restriction and provide user guidance.
 */
data class OemInfo(
    val name: String,           // e.g. "MIUI"
    val steps: String,          // user-facing instructions
)

fun detectOem(): OemInfo? {
    val manufacturer = Build.MANUFACTURER.lowercase()
    val brand = Build.BRAND.lowercase()

    return when {
        // Xiaomi / MIUI / HyperOS
        manufacturer.contains("xiaomi") || brand.contains("xiaomi") ||
        isClassPresent("miui.os.Build") -> OemInfo(
            name = "MIUI / HyperOS",
            steps = "设置 → 应用 → 应用管理 → 校园网认证守护 → 自启动 → 开启\n" +
                    "同页面：省电策略 → 无限制\n" +
                    "设置 → 电池 → 关闭该应用的电池优化"
        )

        // Huawei / Honor / EMUI / HarmonyOS
        manufacturer.contains("huawei") || brand.contains("huawei") ||
        manufacturer.contains("honor") || brand.contains("honor") ||
        isClassPresent("com.huawei.systemmanager.BuildConfig") -> OemInfo(
            name = "EMUI / HarmonyOS",
            steps = "设置 → 应用和服务 → 应用启动管理 → 校园网认证守护\n" +
                    "关闭「自动管理」→ 手动开启「自启动」「关联启动」「后台活动」全部允许\n" +
                    "设置 → 电池 → 关闭该应用的电池优化"
        )

        // OPPO / OnePlus / Realme / ColorOS
        manufacturer.contains("oppo") || brand.contains("oppo") ||
        manufacturer.contains("oneplus") || brand.contains("oneplus") ||
        manufacturer.contains("realme") || brand.contains("realme") ||
        isClassPresent("com.coloros.os.Build") -> OemInfo(
            name = "ColorOS",
            steps = "设置 → 应用 → 自启动管理 → 校园网认证守护 → 开启\n" +
                    "设置 → 电池 → 更多设置 → 关闭睡眠待机优化\n" +
                    "设置 → 应用 → 应用管理 → 校园网认证守护 → 耗电管理 → 允许后台运行"
        )

        // vivo / OriginOS / FuntouchOS
        manufacturer.contains("vivo") || brand.contains("vivo") ||
        isClassPresent("com.vivo.os.Build") -> OemInfo(
            name = "OriginOS / FuntouchOS",
            steps = "设置 → 应用与权限 → 自启动管理 → 校园网认证守护 → 开启\n" +
                    "设置 → 电池 → 后台耗电管理 → 校园网认证守护 → 允许后台运行\n" +
                    "设置 → 电池 → 关闭该应用的电池优化"
        )

        // Samsung / OneUI
        manufacturer.contains("samsung") || brand.contains("samsung") -> OemInfo(
            name = "OneUI",
            steps = "设置 → 电池 → 后台使用限制 → 从不睡眠的应用中添加校园网认证守护\n" +
                    "设置 → 应用 → 校园网认证守护 → 电池 → 不受限\n" +
                    "设置 → 设备维护 → 电池 → 后台使用限制 → 确保未被限制"
        )

        // Meizu / Flyme
        manufacturer.contains("meizu") || brand.contains("meizu") -> OemInfo(
            name = "Flyme",
            steps = "设置 → 应用管理 → 校园网认证守护 → 权限管理 → 后台弹出界面 / 自启动 → 允许\n" +
                    "设置 → 电量管理 → 超级省电 → 将校园网认证守护加入白名单"
        )

        // ZTE / nubia
        manufacturer.contains("zte") || brand.contains("zte") ||
        manufacturer.contains("nubia") -> OemInfo(
            name = "ZTE / nubia",
            steps = "设置 → 应用管理 → 校园网认证守护 → 自启动 → 开启\n" +
                    "设置 → 电池 → 关闭该应用的电池优化"
        )

        // Lenovo / Motorola
        manufacturer.contains("lenovo") || brand.contains("lenovo") ||
        manufacturer.contains("motorola") || brand.contains("motorola") -> OemInfo(
            name = "Lenovo / Motorola",
            steps = "设置 → 电池 → 后台耗电管理 → 校园网认证守护 → 允许\n" +
                    "设置 → 应用 → 校园网认证守护 → 电池 → 不受限"
        )

        // Generic / stock Android — no special steps needed
        else -> null
    }
}

private fun isClassPresent(className: String): Boolean {
    return try {
        Class.forName(className)
        true
    } catch (_: Exception) {
        false
    }
}
