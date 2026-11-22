package dev.silo.app.data

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

enum class PrivacyRiskLevel { LOW, MEDIUM, HIGH }

data class InstalledApp(
    val uid: Int,
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val hasLauncher: Boolean,
    val hasInternetPermission: Boolean,
    val isProtected: Boolean,
    val riskLevel: PrivacyRiskLevel = PrivacyRiskLevel.LOW,
    val sensitivePermissionsCount: Int = 0,
    val detectedTrackers: List<String> = emptyList(),
)

object AppInventory {
    private val iconCache = ConcurrentHashMap<String, ImageBitmap>()

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

    private val sensitivePermissions = setOf(
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_SMS,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_EXTERNAL_STORAGE,
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_VIDEO",
        "android.permission.MANAGE_EXTERNAL_STORAGE",
        "android.permission.QUERY_ALL_PACKAGES",
        "android.permission.SYSTEM_ALERT_WINDOW",
        "android.permission.READ_PHONE_STATE",
    )

    private val knownTrackerPrefixes = listOf(
        "com.facebook.ads" to "Meta Ads",
        "com.facebook.appevents" to "Meta Analytics",
        "com.google.android.gms.ads" to "Google AdMob",
        "com.google.firebase.analytics" to "Firebase Analytics",
        "com.appsflyer" to "AppsFlyer",
        "com.adjust.sdk" to "Adjust",
        "com.ironsource" to "ironSource",
        "com.applovin" to "AppLovin",
        "com.bytedance.sdk" to "Pangle/ByteDance",
        "io.branch" to "Branch.io",
        "com.mixpanel" to "Mixpanel",
        "com.amplitude" to "Amplitude",
        "com.onesignal" to "OneSignal",
        "com.braze" to "Braze",
        "com.yandex.metrica" to "AppMetrica",
        "com.unity3d.ads" to "Unity Ads",
        "com.vungle" to "Vungle/Liftoff",
        "com.inmobi" to "InMobi",
    )

    fun isProtectedUid(uid: Int): Boolean {
        val appId = uid % 100_000
        return appId < 10_000 || appId >= 90_000
    }

    /**
     * Pure offline privacy risk classifier (testable on JVM without Android framework).
     */
    fun evaluateOfflineRisk(
        permissions: Collection<String>,
        componentNames: Collection<String>,
    ): Triple<PrivacyRiskLevel, Int, List<String>> {
        val sensCount = permissions.count { sensitivePermissions.contains(it) }
        val trackers = LinkedHashSet<String>()
        for (comp in componentNames) {
            for ((prefix, label) in knownTrackerPrefixes) {
                if (comp.startsWith(prefix, ignoreCase = true)) {
                    trackers.add(label)
                }
            }
        }
        val risk = when {
            trackers.size >= 3 || (trackers.isNotEmpty() && sensCount >= 5) || sensCount >= 7 ->
                PrivacyRiskLevel.HIGH
            trackers.isNotEmpty() || sensCount >= 3 ->
                PrivacyRiskLevel.MEDIUM
            else -> PrivacyRiskLevel.LOW
        }
        return Triple(risk, sensCount, trackers.toList())
    }

    suspend fun loadInstalledApps(context: Context): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val flags = PackageManager.GET_PERMISSIONS or
            PackageManager.GET_RECEIVERS or
            PackageManager.GET_SERVICES
        val packages = pm.getInstalledPackages(flags)
        packages.mapNotNull { pkgInfo ->
            val appInfo = pkgInfo.applicationInfo ?: return@mapNotNull null
            val uid = appInfo.uid
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val hasLauncher = pm.getLaunchIntentForPackage(pkgInfo.packageName) != null
            val perms = pkgInfo.requestedPermissions?.toList().orEmpty()
            val hasNet = perms.contains(Manifest.permission.INTERNET)
            val components = buildList {
                pkgInfo.receivers?.forEach { add(it.name) }
                pkgInfo.services?.forEach { add(it.name) }
            }
            val (risk, sensCount, trackers) = evaluateOfflineRisk(perms, components)
            val label = runCatching { pm.getApplicationLabel(appInfo).toString() }
                .getOrDefault(pkgInfo.packageName)
            val protected = isProtectedUid(uid) || protectedPackages.contains(pkgInfo.packageName)
            InstalledApp(
                uid = uid,
                packageName = pkgInfo.packageName,
                label = label,
                isSystem = isSystem,
                hasLauncher = hasLauncher,
                hasInternetPermission = hasNet,
                isProtected = protected,
                riskLevel = risk,
                sensitivePermissionsCount = sensCount,
                detectedTrackers = trackers,
            )
        }.sortedWith(
            compareBy<InstalledApp>(
                { it.isProtected },
                { !it.hasLauncher },
                { it.isSystem },
                { it.label.lowercase() },
            )
        )
    }

    suspend fun loadAppIcon(context: Context, packageName: String): ImageBitmap? {
        iconCache[packageName]?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val drawable = context.packageManager.getApplicationIcon(packageName)
                val bmp = drawableToBitmap(drawable, 96).asImageBitmap()
                iconCache[packageName] = bmp
                bmp
            }.getOrNull()
        }
    }

    private fun drawableToBitmap(drawable: Drawable, sizePx: Int): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return Bitmap.createScaledBitmap(drawable.bitmap, sizePx, sizePx, true)
        }
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }
}
