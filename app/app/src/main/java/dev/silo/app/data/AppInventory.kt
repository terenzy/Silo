package dev.silo.app.data

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class PrivacyRiskLevel { LOW, MEDIUM, HIGH }

data class OfflineRiskResult(
    val level: PrivacyRiskLevel,
    val grantedCategories: Set<String>,
    val declaredCategories: Set<String>,
    val detectedTrackers: List<String>,
)

data class InstalledApp(
    val uid: Int,
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val hasLauncher: Boolean,
    val hasInternetPermission: Boolean,
    val isProtected: Boolean,
    val riskLevel: PrivacyRiskLevel = PrivacyRiskLevel.LOW,
    val grantedSensitiveCategories: Set<String> = emptySet(),
    val declaredSensitiveCategories: Set<String> = emptySet(),
    val detectedTrackers: List<String> = emptyList(),
)

object AppInventory {
    /**
     * Bounded icon cache (APP-L05). Declared lazily so pure JVM unit tests calling
     * `evaluateOfflineRisk`/`isProtectedUid`/`isProtectedPackage` never instantiate Android's
     * stubbed `LruCache.<init>`.
     */
    private val iconCache: LruCache<String, ImageBitmap> by lazy {
        LruCache<String, ImageBitmap>(150)
    }

    private const val FULL_PACKAGE_FLAGS =
        PackageManager.GET_PERMISSIONS or
            PackageManager.GET_ACTIVITIES or
            PackageManager.GET_RECEIVERS or
            PackageManager.GET_SERVICES or
            PackageManager.GET_PROVIDERS

    private const val LIGHT_PACKAGE_FLAGS =
        PackageManager.GET_PERMISSIONS or
            PackageManager.GET_RECEIVERS or
            PackageManager.GET_SERVICES

    val appComparator: Comparator<InstalledApp> = compareBy<InstalledApp>(
        { it.isProtected },
        { !it.hasLauncher },
        { it.isSystem },
        { it.label.lowercase() },
    )

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

    /** Dangerous runtime permissions revocable via `pm revoke` (aligned with daemon `scopes.rs`). */
    private val runtimePermissionToCategory = mapOf(
        Manifest.permission.CAMERA to "camera",
        Manifest.permission.RECORD_AUDIO to "microphone",
        Manifest.permission.ACCESS_FINE_LOCATION to "location",
        Manifest.permission.ACCESS_COARSE_LOCATION to "location",
        Manifest.permission.ACCESS_BACKGROUND_LOCATION to "background_location",
        Manifest.permission.READ_CONTACTS to "contacts",
        Manifest.permission.WRITE_CONTACTS to "contacts",
        Manifest.permission.READ_EXTERNAL_STORAGE to "media",
        Manifest.permission.WRITE_EXTERNAL_STORAGE to "media",
        "android.permission.READ_MEDIA_IMAGES" to "media",
        "android.permission.READ_MEDIA_VIDEO" to "media",
        "android.permission.READ_MEDIA_AUDIO" to "media",
        "android.permission.READ_MEDIA_VISUAL_USER_SELECTED" to "media",
        Manifest.permission.READ_SMS to "sms_calllog",
        Manifest.permission.RECEIVE_SMS to "sms_calllog",
        Manifest.permission.SEND_SMS to "sms_calllog",
        Manifest.permission.RECEIVE_MMS to "sms_calllog",
        Manifest.permission.READ_CALL_LOG to "sms_calllog",
        Manifest.permission.WRITE_CALL_LOG to "sms_calllog",
        "android.permission.BODY_SENSORS" to "sensors",
        "android.permission.BODY_SENSORS_BACKGROUND" to "sensors",
        "android.permission.ACTIVITY_RECOGNITION" to "sensors",
    )

    /**
     * Special AppOps permissions (NOT revocable via `pm revoke`, APP-H08).
     * Surfaced in `declaredCategories` so the app's relevant control card appears,
     * but EXCLUDED from `grantedCategories` because `REQUESTED_PERMISSION_GRANTED` does not
     * track AppOps `ignore`/`deny` state.
     */
    private val specialAppOpPermissionToCategory = mapOf(
        "android.permission.MANAGE_EXTERNAL_STORAGE" to "media",
        "android.permission.SYSTEM_ALERT_WINDOW" to "background",
        "android.permission.PACKAGE_USAGE_STATS" to "background",
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

    fun isProtectedPackage(packageName: String?): Boolean =
        !packageName.isNullOrBlank() && protectedPackages.contains(packageName.trim())

    /**
     * Pure offline privacy risk classifier (testable on JVM without Android framework).
     * Evaluates risk strictly from CURRENTLY GRANTED runtime sensitive permissions + embedded
     * tracker SDKs, so ungranted manifest permissions and special AppOps never cause false alarms.
     */
    fun evaluateOfflineRisk(
        declaredPermissions: Collection<String>,
        grantedPermissions: Collection<String>,
        componentNames: Collection<String>,
    ): OfflineRiskResult {
        val declaredCats = LinkedHashSet<String>()
        for (p in declaredPermissions) {
            (runtimePermissionToCategory[p] ?: specialAppOpPermissionToCategory[p])?.let { declaredCats.add(it) }
        }
        val grantedCats = LinkedHashSet<String>()
        for (p in grantedPermissions) {
            runtimePermissionToCategory[p]?.let { grantedCats.add(it) }
        }

        val trackers = LinkedHashSet<String>()
        for (comp in componentNames) {
            for ((prefix, label) in knownTrackerPrefixes) {
                if (comp.startsWith(prefix, ignoreCase = true)) {
                    trackers.add(label)
                }
            }
        }

        val activeCount = grantedCats.size
        val risk = when {
            activeCount >= 4 || (activeCount >= 2 && trackers.size >= 2) || trackers.size >= 4 ->
                PrivacyRiskLevel.HIGH
            activeCount >= 2 || trackers.isNotEmpty() ->
                PrivacyRiskLevel.MEDIUM
            else -> PrivacyRiskLevel.LOW
        }
        return OfflineRiskResult(
            level = risk,
            grantedCategories = grantedCats,
            declaredCategories = declaredCats,
            detectedTrackers = trackers.toList(),
        )
    }

    private fun getPackageInfoSafe(pm: PackageManager, packageName: String): PackageInfo? {
        return try {
            pm.getPackageInfo(packageName, FULL_PACKAGE_FLAGS)
        } catch (_: Exception) {
            runCatching { pm.getPackageInfo(packageName, LIGHT_PACKAGE_FLAGS) }.getOrNull()
                ?: runCatching { pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS) }.getOrNull()
        }
    }

    private fun buildInstalledApp(pm: PackageManager, pkgInfo: PackageInfo): InstalledApp? {
        val appInfo = pkgInfo.applicationInfo ?: return null
        val uid = appInfo.uid
        val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val hasLauncher = runCatching { pm.getLaunchIntentForPackage(pkgInfo.packageName) != null }
            .getOrDefault(false)
        val reqPerms = pkgInfo.requestedPermissions ?: emptyArray()
        val reqFlags = pkgInfo.requestedPermissionsFlags ?: IntArray(0)
        val declaredList = reqPerms.toList()
        val grantedList = buildList {
            for (i in reqPerms.indices) {
                val f = if (i < reqFlags.size) reqFlags[i] else 0
                if ((f and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0) {
                    add(reqPerms[i])
                }
            }
        }
        val hasNet = declaredList.contains(Manifest.permission.INTERNET)
        val components = buildList {
            pkgInfo.activities?.forEach { it.name?.let(::add) }
            pkgInfo.receivers?.forEach { it.name?.let(::add) }
            pkgInfo.services?.forEach { it.name?.let(::add) }
            pkgInfo.providers?.forEach { it.name?.let(::add) }
        }
        val riskResult = evaluateOfflineRisk(declaredList, grantedList, components)
        val label = runCatching { pm.getApplicationLabel(appInfo).toString() }
            .getOrDefault(pkgInfo.packageName)
        val protected = isProtectedUid(uid) || isProtectedPackage(pkgInfo.packageName)
        return InstalledApp(
            uid = uid,
            packageName = pkgInfo.packageName,
            label = label,
            isSystem = isSystem,
            hasLauncher = hasLauncher,
            hasInternetPermission = hasNet,
            isProtected = protected,
            riskLevel = riskResult.level,
            grantedSensitiveCategories = riskResult.grantedCategories,
            declaredSensitiveCategories = riskResult.declaredCategories,
            detectedTrackers = riskResult.detectedTrackers,
        )
    }

    /** Fast (~5ms) single-package refresh after per-app permission/guard changes (APP-H04). */
    suspend fun reloadSingleApp(context: Context, packageName: String): InstalledApp? =
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val pkgInfo = getPackageInfoSafe(pm, packageName) ?: return@withContext null
            runCatching { buildInstalledApp(pm, pkgInfo) }.getOrNull()
        }

    suspend fun loadInstalledApps(context: Context): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val packages: List<PackageInfo> = try {
            pm.getInstalledPackages(FULL_PACKAGE_FLAGS)
        } catch (_: Exception) {
            // Binder TransactionTooLargeException / DeadObjectException fallback (APP-M08):
            // enumerate bare package stubs then inspect each package individually.
            try {
                pm.getInstalledPackages(0).mapNotNull { stub ->
                    getPackageInfoSafe(pm, stub.packageName) ?: stub
                }
            } catch (_: Exception) {
                runCatching { pm.getInstalledPackages(LIGHT_PACKAGE_FLAGS) }
                    .getOrElse {
                        runCatching { pm.getInstalledPackages(PackageManager.GET_PERMISSIONS) }
                            .getOrDefault(emptyList())
                    }
            }
        }
        packages.mapNotNull { pkgInfo ->
            runCatching { buildInstalledApp(pm, pkgInfo) }.getOrNull()
        }.sortedWith(appComparator)
    }

    suspend fun loadAppIcon(context: Context, packageName: String): ImageBitmap? {
        iconCache.get(packageName)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val drawable = context.packageManager.getApplicationIcon(packageName)
                val bmp = drawableToBitmap(drawable, 96).asImageBitmap()
                iconCache.put(packageName, bmp)
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

