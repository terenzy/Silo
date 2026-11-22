package dev.silo.app.data

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class InstalledApp(
    val uid: Int,
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val hasInternetPermission: Boolean,
    val isProtected: Boolean,
)

object AppInventory {
    private val protectedPackages = setOf(
        "android",
        "com.android.systemui",
        "com.android.phone",
        "com.android.settings",
        "com.android.networkstack",
        "com.android.networkstack.tethering",
        "com.android.providers.settings",
        "com.android.providers.telephony",
        "com.android.shell",
        "com.android.bluetooth",
        "com.android.se",
        "com.samsung.android.honeyboard",
        "com.google.android.inputmethod.latin",
        "dev.silo.app",
        "com.rifsxd.ksunext",
        "me.weishu.kernelsu",
        "com.sukisu.ultra",
        "com.resukisu.resukisu",
        "me.bmax.apatch",
    )

    fun isProtectedUid(uid: Int): Boolean {
        val appId = uid % 100_000
        return appId < 10_000 || appId >= 90_000
    }

    suspend fun loadInstalledApps(context: Context): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val packages = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        packages.mapNotNull { pkgInfo ->
            val appInfo = pkgInfo.applicationInfo ?: return@mapNotNull null
            val uid = appInfo.uid
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val hasNet = pkgInfo.requestedPermissions?.contains(Manifest.permission.INTERNET) == true
            val label = runCatching { pm.getApplicationLabel(appInfo).toString() }
                .getOrDefault(pkgInfo.packageName)
            val protected = isProtectedUid(uid) || protectedPackages.contains(pkgInfo.packageName)
            InstalledApp(
                uid = uid,
                packageName = pkgInfo.packageName,
                label = label,
                isSystem = isSystem,
                hasInternetPermission = hasNet,
                isProtected = protected,
            )
        }.sortedWith(
            compareBy<InstalledApp>({ it.isProtected }, { it.isSystem }, { it.label.lowercase() })
        )
    }
}
