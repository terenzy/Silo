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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

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

    /** Maps Android permission names to Silo's 10 honest permission categories. */
    private val permissionToCategory = mapOf(
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
        "android.permission.MANAGE_EXTERNAL_STORAGE" to "media",
        Manifest.permission.READ_SMS to "sms_calllog",
        Manifest.permission.RECEIVE_SMS to "sms_calllog",
        Manifest.permission.READ_CALL_LOG to "sms_calllog",
        Manifest.permission.WRITE_CALL_LOG to "sms_calllog",
        "android.permission.BODY_SENSORS" to "sensors",
        "android.permission.ACTIVITY_RECOGNITION" to "sensors",
        "android.permission.SYSTEM_ALERT_WINDOW" to "background",
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
     * Evaluates risk strictly from CURRENTLY GRANTED sensitive permissions + embedded tracker SDKs,
     * so ungranted manifest permissions never cause false "High Risk" alarms.
     */
    fun evaluateOfflineRisk(
        declaredPermissions: Collection<String>,
        grantedPermissions: Collection<String>,
        componentNames: Collection<String>,
    ): OfflineRiskResult {
        val declaredCats = LinkedHashSet<String>()
        for (p in declaredPermissions) {
            permissionToCategory[p]?.let { declaredCats.add(it) }
        }
        val grantedCats = LinkedHashSet<String>()
        for (p in grantedPermissions) {
            permissionToCategory[p]?.let { grantedCats.add(it) }
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
                pkgInfo.receivers?.forEach { add(it.name) }
                pkgInfo.services?.forEach { add(it.name) }
            }
            val riskResult = evaluateOfflineRisk(declaredList, grantedList, components)
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
                riskLevel = riskResult.level,
                grantedSensitiveCategories = riskResult.grantedCategories,
                declaredSensitiveCategories = riskResult.declaredCategories,
                detectedTrackers = riskResult.detectedTrackers,
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
