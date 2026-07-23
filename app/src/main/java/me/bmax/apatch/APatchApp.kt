package me.bmax.apatch

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.topjohnwu.superuser.CallbackList
import me.bmax.apatch.ui.CrashHandleActivity
import me.bmax.apatch.util.APatchCli
import me.bmax.apatch.util.PkgConfig
import me.bmax.apatch.util.Version
import me.bmax.apatch.util.getRootShell
import me.bmax.apatch.util.rootShellForResult
import me.bmax.apatch.util.verifyAppSignature
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.system.exitProcess

lateinit var apApp: APApplication

const val TAG = "APatch"

class APApplication : Application(), Thread.UncaughtExceptionHandler {
    lateinit var okhttpClient: OkHttpClient

    init {
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    enum class State {
        UNKNOWN_STATE,

        KERNELPATCH_INSTALLED, KERNELPATCH_NEED_UPDATE, KERNELPATCH_NEED_REBOOT, KERNELPATCH_UNINSTALLING,

        ANDROIDPATCH_NOT_INSTALLED, ANDROIDPATCH_INSTALLED, ANDROIDPATCH_INSTALLING, ANDROIDPATCH_NEED_UPDATE, ANDROIDPATCH_UNINSTALLING,
    }


    companion object {
        const val APD_PATH = "/data/adb/apd"

        @Deprecated("No more KPatch ELF from 0.11.0-dev")
        const val KPATCH_PATH = "/data/adb/kpatch"
        const val SUPERCMD = "/system/bin/truncate"
        const val APATCH_FOLDER = "/data/adb/ap/"
        private const val APATCH_BIN_FOLDER = APATCH_FOLDER + "bin/"
        private const val APATCH_LOG_FOLDER = APATCH_FOLDER + "log/"
        private const val APD_LINK_PATH = APATCH_BIN_FOLDER + "apd"
        const val PACKAGE_CONFIG_FILE = APATCH_FOLDER + "package_config"
        const val SU_PATH_FILE = APATCH_FOLDER + "su_path"
        const val SAFEMODE_FILE = "/dev/.safemode"
        private const val NEED_REBOOT_FILE = "/dev/.need_reboot"
        const val GLOBAL_NAMESPACE_FILE = "/data/adb/.global_namespace_enable"
        const val KPMS_DIR = APATCH_FOLDER + "kpms/"

        @Deprecated("Use 'apd -V'")
        const val APATCH_VERSION_PATH = APATCH_FOLDER + "version"
        private const val MAGISKPOLICY_BIN_PATH = APATCH_BIN_FOLDER + "magiskpolicy"
        private const val BUSYBOX_BIN_PATH = APATCH_BIN_FOLDER + "busybox"
        private const val RESETPROP_BIN_PATH = APATCH_BIN_FOLDER + "resetprop"
        private const val KPTOOLS_BIN_PATH = APATCH_BIN_FOLDER + "kptools"
        const val DEFAULT_SCONTEXT = "u:r:untrusted_app:s0"
        const val MAGISK_SCONTEXT = "u:r:magisk:s0"

        private const val DEFAULT_SU_PATH = "/system/bin/su"
        private const val LEGACY_SU_PATH = "/system/bin/su"

        const val SP_NAME = "config"
        private const val SHOW_BACKUP_WARN = "show_backup_warning"
        private const val STORED_SUPER_KEY = "xf_super_key"
        private const val AUTO_MODULES_INSTALLED_FOR = "xf_auto_modules_installed_for"
        lateinit var sharedPreferences: SharedPreferences

        private val logCallback: CallbackList<String?> = object : CallbackList<String?>() {
            override fun onAddElement(s: String?) {
                Log.d(TAG, s.toString())
            }
        }

        private val _kpStateLiveData = MutableLiveData(State.UNKNOWN_STATE)
        val kpStateLiveData: LiveData<State> = _kpStateLiveData

        private val _apStateLiveData = MutableLiveData(State.UNKNOWN_STATE)
        val apStateLiveData: LiveData<State> = _apStateLiveData

        @Suppress("DEPRECATION")
        fun uninstallApatch() {
            if (_apStateLiveData.value != State.ANDROIDPATCH_INSTALLED) return
            _apStateLiveData.value = State.ANDROIDPATCH_UNINSTALLING

            Natives.resetSuPath(DEFAULT_SU_PATH)

            val cmds = arrayOf(
                "rm -f $APD_PATH",
                "rm -f $KPATCH_PATH",
                "rm -rf $APATCH_BIN_FOLDER",
                "rm -rf $APATCH_LOG_FOLDER",
                "rm -rf $APATCH_VERSION_PATH",
            )

            val shell = getRootShell()
            shell.newJob().add(*cmds).to(logCallback, logCallback).exec()

            Log.d(TAG, "APatch uninstalled...")
            if (_kpStateLiveData.value == State.UNKNOWN_STATE) {
                _apStateLiveData.postValue(State.UNKNOWN_STATE)
            } else {
                _apStateLiveData.postValue(State.ANDROIDPATCH_NOT_INSTALLED)
            }
        }

        @Suppress("DEPRECATION")
        fun installApatch() {
            val state = _apStateLiveData.value
            if (state != State.ANDROIDPATCH_NOT_INSTALLED && state != State.ANDROIDPATCH_NEED_UPDATE) {
                return
            }
            _apStateLiveData.value = State.ANDROIDPATCH_INSTALLING
            val nativeDir = apApp.applicationInfo.nativeLibraryDir

            Natives.resetSuPath(DEFAULT_SU_PATH)

            val cmds = arrayOf(
                "mkdir -p $APATCH_BIN_FOLDER",
                "mkdir -p $APATCH_LOG_FOLDER",

                "cp -f ${nativeDir}/libapd.so $APD_PATH",
                "chmod +x $APD_PATH",
                "rm -f $APD_LINK_PATH",
                "ln -s $APD_PATH $APD_LINK_PATH",
                "restorecon $APD_PATH",

                "rm -f $MAGISKPOLICY_BIN_PATH",
                "ln -s $APD_PATH $MAGISKPOLICY_BIN_PATH",
                "rm -f $RESETPROP_BIN_PATH",
                "ln -s $APD_PATH $RESETPROP_BIN_PATH",
               
                "cp -f ${nativeDir}/libbusybox.so $BUSYBOX_BIN_PATH",
                "chmod +x $BUSYBOX_BIN_PATH",
                "cp -f ${nativeDir}/libkptools.so $KPTOOLS_BIN_PATH",
                "chmod +x $KPTOOLS_BIN_PATH",



                "touch $PACKAGE_CONFIG_FILE",
                "echo $DEFAULT_SU_PATH > $SU_PATH_FILE",
                "echo ${Version.getManagerVersion().second} > $APATCH_VERSION_PATH",
                "restorecon -R $APATCH_FOLDER",

                "[ -x $MAGISKPOLICY_BIN_PATH ] && $MAGISKPOLICY_BIN_PATH --magisk --live || true",
            )

            val shell = getRootShell()
            shell.newJob().add(*cmds).to(logCallback, logCallback).exec()

            // clear shell cache
            runCatching {
                APatchCli.refresh()
            }.onFailure {
                Log.e(TAG, "APatchCli.refresh failed after install", it)
            }

            Log.d(TAG, "APatch installed...")
            _apStateLiveData.postValue(State.ANDROIDPATCH_INSTALLED)

            autoGrantRootPackages(BuildConfig.AUTO_GRANT_ROOT_PACKAGES)

            if (BuildConfig.AUTO_INSTALL_MODULES.isNotEmpty()) {
                thread {
                    autoInstallModules(BuildConfig.AUTO_INSTALL_MODULES)
                }
            }
        }

        private fun autoGrantRootPackages(packageCsv: String) {
            val marker = packageCsv.trim()
            val packages = (marker.split(",") + apApp.packageName)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
            if (packages.isEmpty()) return

            thread {
                try {
                    Log.d(TAG, "Auto-granting root packages: $packages")
                    val applied = PkgConfig.grantRootPackages(apApp.applicationContext, packages)
                    Log.d(TAG, "Auto root grants result: $applied")
                } catch (t: Throwable) {
                    Log.e(TAG, "Auto root grants failed", t)
                }
            }
        }

        private fun autoInstallModules(modulePaths: String) {
            val marker = modulePaths.trim()
            if (marker.isEmpty()) return
            if (sharedPreferences.getString(AUTO_MODULES_INSTALLED_FOR, "") == marker) {
                Log.d(TAG, "Auto modules already installed for current config")
                return
            }

            val paths = marker.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (paths.isEmpty()) return

            Log.d(TAG, "Auto-installing modules: $paths")
            val shell = getRootShell()
            var allOk = true
            for (path in paths) {
                val result = shell.newJob()
                    .add("$APD_LINK_PATH module install $path")
                    .to(logCallback, logCallback)
                    .exec()
                if (!result.isSuccess) {
                    allOk = false
                    Log.e(TAG, "Failed to install module: $path")
                }
            }

            if (allOk) {
                sharedPreferences.edit { putString(AUTO_MODULES_INSTALLED_FOR, marker) }
            }
        }

        fun markNeedReboot() {
            val result = rootShellForResult("touch $NEED_REBOOT_FILE")
            _kpStateLiveData.postValue(State.KERNELPATCH_NEED_REBOOT)
            Log.d(TAG, "mark reboot ${result.code}")
        }


        var superKey: String = ""
            set(value) {
                field = value
                if (::sharedPreferences.isInitialized && value != "su") {
                    sharedPreferences.edit { putString(STORED_SUPER_KEY, value) }
                }
                val ready = Natives.nativeReady(value)
                _kpStateLiveData.value =
                    if (ready) State.KERNELPATCH_INSTALLED else State.UNKNOWN_STATE
                _apStateLiveData.value =
                    if (ready) State.ANDROIDPATCH_NOT_INSTALLED else State.UNKNOWN_STATE
                Log.d(TAG, "state: " + _kpStateLiveData.value)
                if (!ready) return

                thread {
                    val rc = Natives.su(0, null)
                    if (!rc) {
                        Log.e(TAG, "Native.su failed")
                        return@thread
                    }

                    // KernelPatch version
                    //val buildV = Version.buildKPVUInt()
                    //val installedV = Version.installedKPVUInt()
                    //use build time to check update
                    val buildV = Version.getKpImg()
                    val installedV = Version.installedKPTime()


                    Log.d(TAG, "kp installed version: ${installedV}, build version: $buildV")

                    // use != instead of > to enable downgrade,
                    if (buildV != installedV) {
                        _kpStateLiveData.postValue(State.KERNELPATCH_NEED_UPDATE)
                    }
                    Log.d(TAG, "kp state: " + _kpStateLiveData.value)

                    if (File(NEED_REBOOT_FILE).exists()) {
                        _kpStateLiveData.postValue(State.KERNELPATCH_NEED_REBOOT)
                    }
                    Log.d(TAG, "kp state: " + _kpStateLiveData.value)

                    // AndroidPatch version
                    val mgv = Version.getManagerVersion().second
                    val installedApdVInt = Version.installedApdVUInt()
                    Log.d(TAG, "manager version: $mgv, installed apd version: $installedApdVInt")

                    if (Version.installedApdVInt > 0) {
                        _apStateLiveData.postValue(State.ANDROIDPATCH_INSTALLED)
                        autoGrantRootPackages(BuildConfig.AUTO_GRANT_ROOT_PACKAGES)
                    }

                    if (Version.installedApdVInt > 0 && mgv.toInt() != Version.installedApdVInt) {
                        _apStateLiveData.postValue(State.ANDROIDPATCH_NEED_UPDATE)
                        // su path
                        val suPathFile = File(SU_PATH_FILE)
                        if (suPathFile.exists()) {
                            val suPath = suPathFile.readLines()[0].trim()
                            if (Natives.suPath() != suPath) {
                                Log.d(TAG, "su path: $suPath")
                                Natives.resetSuPath(suPath)
                            }
                        }
                    }
                    Log.d(TAG, "ap state: " + _apStateLiveData.value)

                    return@thread
                }
            }

        private fun trySuperKey(value: String, source: String): Boolean {
            if (value.isBlank()) return false
            Log.d(TAG, "Trying $source superkey")
            return if (Natives.nativeReady(value)) {
                Log.d(TAG, "$source superkey verified successfully")
                superKey = value
                true
            } else {
                Log.d(TAG, "$source superkey verification failed")
                false
            }
        }

        fun tryDefaultSuperKey(): Boolean {
            return trySuperKey(BuildConfig.DEFAULT_SUPERKEY, "default")
        }
    }

    override fun onCreate() {
        super.onCreate()
        apApp = this

        val isArm64 = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }
        if (!isArm64) {
            Toast.makeText(applicationContext, "Unsupported architecture!", Toast.LENGTH_LONG)
                .show()
            Thread.sleep(5000)
            exitProcess(0)
        }

        if (!BuildConfig.DEBUG && !BuildConfig.AUTO_INSTALL_APATCH &&
            !verifyAppSignature("1x2twMoHvfWUODv7KkRRNKBzOfEqJwRKGzJpgaz18xk=")) {
            while (true) {
                val intent = Intent(Intent.ACTION_DELETE)
                intent.data = "package:$packageName".toUri()
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                startActivity(intent)
                exitProcess(0)
            }
        }

        // TODO: We can't totally protect superkey from be stolen by root or LSPosed-like injection tools in user space, the only way is don't use superkey,
        // TODO: 1. make me root by kernel
        // TODO: 2. remove all usage of superkey
        sharedPreferences = getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)

        val storedKey = sharedPreferences.getString(STORED_SUPER_KEY, "").orEmpty()
        if (storedKey.isNotEmpty() && trySuperKey(storedKey, "stored")) {
            Log.d(TAG, "Using stored superkey")
        } else if (tryDefaultSuperKey()) {
            Log.d(TAG, "Using default superkey from BuildConfig")
        } else {
            Log.d(TAG, "No valid stored/default superkey, falling back to su")
            superKey = "su"
        }

        okhttpClient =
            OkHttpClient.Builder().cache(Cache(File(cacheDir, "okhttp"), 10 * 1024 * 1024))
                .addInterceptor { block ->
                    block.proceed(
                        block.request().newBuilder()
                            .header("User-Agent", "APatch/${BuildConfig.VERSION_CODE}")
                            .header("Accept-Language", Locale.getDefault().toLanguageTag()).build()
                    )
                }.build()
    }

    fun getBackupWarningState(): Boolean {
        return sharedPreferences.getBoolean(SHOW_BACKUP_WARN, true)
    }

    fun updateBackupWarningState(state: Boolean) {
        sharedPreferences.edit { putBoolean(SHOW_BACKUP_WARN, state) }
    }

    override fun uncaughtException(t: Thread, e: Throwable) {
        val exceptionMessage = Log.getStackTraceString(e)
        val threadName = t.name
        Log.e(TAG, "Error on thread $threadName:\n $exceptionMessage")
        val intent = Intent(this, CrashHandleActivity::class.java).apply {
            putExtra("exception_message", exceptionMessage)
            putExtra("thread", threadName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
        exitProcess(10)
    }
}
