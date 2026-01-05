package me.bmax.apatch.ui.viewmodel

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.IBinder
import android.os.Parcelable
import android.util.Log
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.parcelize.Parcelize
import me.bmax.apatch.APApplication
import me.bmax.apatch.IAPRootService
import me.bmax.apatch.Natives
import me.bmax.apatch.apApp
import me.bmax.apatch.services.RootServices
import me.bmax.apatch.util.APatchCli
import me.bmax.apatch.util.HanziToPinyin
import me.bmax.apatch.util.PkgConfig
import java.text.Collator
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine


class SuperUserViewModel : ViewModel() {
    companion object {
        private const val TAG = "SuperUserViewModel"
        private val appsLock = Any()
        var apps by mutableStateOf<List<AppInfo>>(emptyList())

        fun getAppIconDrawable(context: Context, packageName: String): android.graphics.drawable.Drawable? {
            val appList = synchronized(appsLock) { apps }
            val appDetail = appList.find { it.packageName == packageName }
            return appDetail?.packageInfo?.applicationInfo?.loadIcon(context.packageManager)
        }
    }

    @Parcelize
    data class AppInfo(
        val label: String, val packageInfo: PackageInfo, val config: PkgConfig.Config
    ) : Parcelable {
        val packageName: String
            get() = packageInfo.packageName
        val uid: Int
            get() = packageInfo.applicationInfo!!.uid
    }

    var search by mutableStateOf("")
    var showSystemApps by mutableStateOf(false)
    var isRefreshing by mutableStateOf(false)
        private set

    private val sortedList by derivedStateOf {
        val comparator = compareBy<AppInfo> {
            when {
                it.config.allow != 0 -> 0
                it.config.exclude == 1 -> 1
                else -> 2
            }
        }.then(compareBy(Collator.getInstance(Locale.getDefault()), AppInfo::label))
        apps.sortedWith(comparator).also {
            isRefreshing = false
        }
    }

    val appList by derivedStateOf {
        sortedList.filter {
            it.label.lowercase().contains(search.lowercase()) || it.packageName.lowercase()
                .contains(search.lowercase()) || HanziToPinyin.getInstance()
                .toPinyinString(it.label).contains(search.lowercase())
        }.filter {
            it.uid == 2000 // Always show shell
                    || showSystemApps || it.packageInfo.applicationInfo!!.flags.and(ApplicationInfo.FLAG_SYSTEM) == 0
        }
    }

    private suspend inline fun connectRootService(
        crossinline onDisconnect: () -> Unit = {}
    ): Pair<IBinder, ServiceConnection> = suspendCoroutine { continuation ->
        val connection = object : ServiceConnection {
            override fun onServiceDisconnected(name: ComponentName?) {
                Log.w(TAG, "onServiceDisconnected: $name")
                onDisconnect()
            }

            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                Log.i(TAG, "onServiceConnected: $name")
                if (binder != null) {
                    try {
                        continuation.resume(binder to this)
                    } catch (e: IllegalStateException) {
                        Log.w(TAG, "Service connected but continuation already resumed", e)
                    }
                } else {
                    Log.e(TAG, "Service connected but binder is null")
                }
            }
        }
        val intent = Intent(apApp, RootServices::class.java)

        Log.d(TAG, "Attempting to bind RootService. Shell isRoot: ${APatchCli.SHELL.isRoot}")
        Log.d(TAG, "Shell info: ${APatchCli.SHELL}")

        val task = RootServices.bindOrTask(
            intent,
            Shell.EXECUTOR,
            connection,
        )

        if (task == null) {
            Log.e(TAG, "RootServices.bindOrTask returned null")
            continuation.resumeWithException(IllegalStateException("bindOrTask returned null"))
        } else {
            val shell = APatchCli.SHELL
            Log.d(TAG, "Executing bind task...")
            shell.execTask(task)
        }
    }

    private fun stopRootService() {
        val intent = Intent(apApp, RootServices::class.java)
        RootServices.stop(intent)
    }

    suspend fun fetchAppList() {
        isRefreshing = true

        val allPackages: List<PackageInfo> = withContext(Dispatchers.IO) {
            try {
                // Use withTimeoutOrNull to avoid hanging forever if RootService fails to connect
                val result = withTimeoutOrNull(10000L) {
                    withContext(Dispatchers.Main) {
                        connectRootService {
                            Log.w(TAG, "RootService disconnected")
                        }
                    }
                }

                if (result != null) {
                    val binder = result.first
                    val packages = IAPRootService.Stub.asInterface(binder).getPackages(0)
                    Log.i(TAG, "RootService connected and retrieved ${packages.list.size} packages")
                    withContext(Dispatchers.Main) {
                        stopRootService()
                    }
                    packages.list
                } else {
                    Log.e(TAG, "RootService connection timed out")
                    emptyList()
                }
            } catch (e: Exception) {
                Log.e(TAG, "RootService failed: ${e.message}", e)
                emptyList()
            }
        }

        if (allPackages.isEmpty()) {
            Log.e(TAG, "Failed to get package list")
            isRefreshing = false
            return
        }

        withContext(Dispatchers.IO) {
            val uids = Natives.suUids().toList()
            Log.d(TAG, "all allows: $uids")

            var configs: HashMap<Int, PkgConfig.Config> = HashMap()
            thread {
                Natives.su()
                configs = PkgConfig.readConfigs()
            }.join()

            Log.d(TAG, "all configs: $configs")

            val newApps = allPackages.map {
                val appInfo = it.applicationInfo
                val uid = appInfo!!.uid
                val actProfile = if (uids.contains(uid)) Natives.suProfile(uid) else null
                val config = configs.getOrDefault(
                    uid, PkgConfig.Config(appInfo.packageName, Natives.isUidExcluded(uid), 0, Natives.Profile(uid = uid))
                )
                config.allow = 0

                // from kernel
                if (actProfile != null) {
                    config.allow = 1
                    config.profile = actProfile
                }
                AppInfo(
                    label = appInfo.loadLabel(apApp.packageManager).toString(),
                    packageInfo = it,
                    config = config
                )
            }

            synchronized(appsLock) {
                apps = newApps
            }
        }
    }
}
