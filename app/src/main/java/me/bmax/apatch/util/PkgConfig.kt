package me.bmax.apatch.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Parcelable
import android.util.Log
import androidx.annotation.Keep
import androidx.compose.runtime.Immutable
import kotlinx.parcelize.Parcelize
import me.bmax.apatch.APApplication
import me.bmax.apatch.Natives
import java.io.File
import java.io.FileWriter
import kotlin.concurrent.thread

object PkgConfig {
    private const val TAG = "PkgConfig"

    private const val CSV_HEADER = "pkg,exclude,allow,uid,to_uid,sctx"

    @Immutable
    @Parcelize
    @Keep
    data class Config(
        var pkg: String = "", var exclude: Int = 0, var allow: Int = 0, var profile: Natives.Profile
    ) : Parcelable {
        companion object {
            fun fromLine(line: String): Config {
                val sp = line.split(",")
                val profile = Natives.Profile(sp[3].toInt(), sp[4].toInt(), sp[5])
                return Config(sp[0], sp[1].toInt(), sp[2].toInt(), profile)
            }
        }

        fun isDefault(): Boolean {
            return allow == 0 && exclude == 0
        }

        fun toLine(): String {
            return "${pkg},${exclude},${allow},${profile.uid},${profile.toUid},${profile.scontext}"
        }
    }

    fun readConfigs(): HashMap<Int, Config> {
        val configs = HashMap<Int, Config>()
        val file = File(APApplication.PACKAGE_CONFIG_FILE)
        if (file.exists()) {
            file.readLines().drop(1).filter { it.isNotEmpty() }.forEach {
                Log.d(TAG, it)
                val p = runCatching { Config.fromLine(it) }
                    .onFailure { e -> Log.w(TAG, "skip malformed package_config line: $it", e) }
                    .getOrNull() ?: return@forEach
                if (!p.isDefault()) {
                    configs[p.profile.uid] = p
                }
            }
        }
        return configs
    }

    private fun writeConfigs(configs: HashMap<Int, Config>) {
        val file = File(APApplication.PACKAGE_CONFIG_FILE)
        if (!file.parentFile?.exists()!!) file.parentFile?.mkdirs()
        val writer = FileWriter(file, false)
        writer.write(CSV_HEADER + '\n')
        configs.values.forEach {
            if (!it.isDefault()) {
                writer.write(it.toLine() + '\n')
            }
        }
        writer.flush()
        writer.close()
    }

    fun grantRootPackages(context: Context, packageNames: List<String>): List<String> {
        val applied = ArrayList<String>()
        val missing = ArrayList<String>()
        synchronized(PkgConfig.javaClass) {
            Natives.su()
            val configs = readConfigs()
            for (pkg in packageNames.map { it.trim() }.filter { it.isNotEmpty() }.distinct()) {
                try {
                    val appInfo = context.packageManager.getApplicationInfo(pkg, 0)
                    val uid = appInfo.uid
                    val config = Config(
                        pkg = pkg,
                        exclude = 0,
                        allow = 1,
                        profile = Natives.Profile(
                            uid = uid,
                            toUid = 0,
                            scontext = APApplication.MAGISK_SCONTEXT,
                        )
                    )
                    configs[uid] = config
                    val grantRc = Natives.grantSu(uid, 0, APApplication.MAGISK_SCONTEXT)
                    val excludeRc = Natives.setUidExclude(uid, 0)
                    applied.add("$pkg:$uid grant=$grantRc exclude=$excludeRc")
                } catch (e: PackageManager.NameNotFoundException) {
                    missing.add(pkg)
                } catch (t: Throwable) {
                    Log.e(TAG, "auto grant failed for $pkg", t)
                    missing.add("$pkg:${t.javaClass.simpleName}")
                }
            }
            writeConfigs(configs)
        }
        if (applied.isNotEmpty()) Log.i(TAG, "auto root grants applied: $applied")
        if (missing.isNotEmpty()) Log.w(TAG, "auto root grant packages missing/failed: $missing")
        return applied
    }

    fun changeConfig(config: Config) {
        thread {
            synchronized(PkgConfig.javaClass) {
                Natives.su()
                val configs = readConfigs()
                val uid = config.profile.uid
                // Root App should not be excluded
                if (config.allow == 1) {
                    config.exclude = 0
                }
                if (config.allow == 0 && configs[uid] != null && config.exclude != 0) {
                    configs.remove(uid)
                } else {
                    Log.d(TAG, "change config: $config")
                    configs[uid] = config
                }
                writeConfigs(configs)
            }
        }
    }
}
