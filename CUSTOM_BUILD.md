# APatch 自定义构建说明

## 修改内容

本版本对 APatch 进行了以下自动化修改：

### 1. 默认 SuperKey 自动配置

**文件**: `app/src/main/java/me/bmax/apatch/APatchApp.kt`

**修改内容**:
- 首次启动时自动设置 SuperKey 为 `xiaofeng777`
- 跳过手动输入 SuperKey 的步骤
- 如果已有 SuperKey 配置，则保持不变

**代码位置**: `onCreate()` 方法

```kotlin
// Auto-set default superkey if not configured
var storedKey = APatchKeyHelper.readSPSuperKey()
if (storedKey.isEmpty()) {
    storedKey = "xiaofeng777"
    APatchKeyHelper.writeSPSuperKey(storedKey)
    Log.d(TAG, "Auto-configured default superkey")
}
superKey = storedKey
```

### 2. 自动安装 AndroidPatch

**文件**: `app/src/main/java/me/bmax/apatch/ui/screen/Home.kt`

**修改内容**:
- 检测到 `ANDROIDPATCH_NOT_INSTALLED` 状态时自动触发安装
- 无需手动点击 "Install Android Patch" 按钮

**代码位置**: `AStatusCard()` 函数

```kotlin
// Auto-install AndroidPatch when not installed
LaunchedEffect(apState) {
    if (apState == APApplication.State.ANDROIDPATCH_NOT_INSTALLED) {
        Log.d(TAG, "Auto-installing AndroidPatch...")
        APApplication.installApatch()
    }
}
```

## 编译步骤

### 前置要求

1. **Android Studio** (最新版本)
2. **JDK 17+**
3. **Android SDK** (API 33+)
4. **NDK** (r26+)
5. **Rust 工具链** (用于编译 apd CLI)

### 安装 Rust

```bash
# Linux/macOS
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh

# Windows
# 下载并运行: https://rustup.rs/
```

添加 Android 目标：

```bash
rustup target add aarch64-linux-android
rustup target add armv7-linux-androideabi
rustup target add x86_64-linux-android
rustup target add i686-linux-android
```

### 编译步骤

1. **克隆仓库**（如果还没有）

```bash
cd other_repos/APatch
```

2. **配置 NDK 路径**

在 `local.properties` 中添加：

```properties
sdk.dir=/path/to/Android/Sdk
ndk.dir=/path/to/Android/Sdk/ndk/26.x.xxxxx
```

3. **编译 APD CLI**

```bash
cd apd
cargo build --release --target aarch64-linux-android
cd ..
```

4. **使用 Android Studio 编译**

```bash
# 打开 Android Studio
# File -> Open -> 选择 APatch 目录
# Build -> Build Bundle(s) / APK(s) -> Build APK(s)
```

或使用命令行：

```bash
./gradlew assembleRelease
```

5. **生成的 APK 位置**

```
app/build/outputs/apk/release/app-release.apk
```

### 签名 APK（可选）

如果需要签名：

```bash
# 生成密钥
keytool -genkey -v -keystore my-release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias my-key-alias

# 签名 APK
jarsigner -verbose -sigalg SHA256withRSA -digestalg SHA-256 -keystore my-release-key.jks app-release.apk my-key-alias

# 对齐 APK
zipalign -v 4 app-release.apk app-release-aligned.apk
```

## 使用说明

### 首次使用

1. **刷入修补后的 boot.img**
   ```bash
   fastboot flash boot apatch_patched_boot.img
   fastboot reboot
   ```

2. **安装修改后的 APatch APK**
   ```bash
   adb install app-release.apk
   ```

3. **打开应用**
   - 应用会自动使用 `xiaofeng777` 作为 SuperKey
   - 自动安装 AndroidPatch 组件
   - 无需手动操作

### 验证安装

```bash
# 检查 APatch 状态
adb shell "su -c '/data/adb/ap/bin/apd -s xiaofeng777 module list'"
```

## 注意事项

1. **SuperKey 安全性**
   - 默认 SuperKey 为 `xiaofeng777`
   - 建议在生产环境中修改为更安全的密钥
   - 修改位置：`APatchApp.kt` 第 285 行

2. **签名验证**
   - 修改后的 APK 签名会改变
   - 如果启用了签名验证，需要更新签名哈希
   - 位置：`APatchApp.kt` 第 273 行

3. **自动安装行为**
   - 自动安装仅在首次检测到未安装状态时触发
   - 如果需要禁用自动安装，删除 `Home.kt` 中的 `LaunchedEffect` 代码块

## 恢复原版行为

如果需要恢复原版 APatch 的行为：

1. **恢复手动 SuperKey 输入**
   ```bash
   git checkout app/src/main/java/me/bmax/apatch/APatchApp.kt
   ```

2. **恢复手动安装按钮**
   ```bash
   git checkout app/src/main/java/me/bmax/apatch/ui/screen/Home.kt
   ```

3. **重新编译**
   ```bash
   ./gradlew clean assembleRelease
   ```

## 故障排除

### 编译错误

1. **NDK 未找到**
   ```
   解决：在 local.properties 中正确配置 ndk.dir
   ```

2. **Rust 编译失败**
   ```bash
   # 更新 Rust 工具链
   rustup update
   
   # 重新添加目标
   rustup target add aarch64-linux-android
   ```

3. **Gradle 同步失败**
   ```bash
   # 清理并重新构建
   ./gradlew clean
   ./gradlew build --refresh-dependencies
   ```

### 运行时问题

1. **SuperKey 验证失败**
   - 检查 boot.img 是否正确修补
   - 确认设备已 root

2. **自动安装未触发**
   - 检查 logcat 日志：`adb logcat | grep APatch`
   - 确认应用有 root 权限

## 相关文档

- [APatch 官方文档](https://apatch.dev)
- [APatch GitHub](https://github.com/bmax121/APatch)
- [Android 开发文档](https://developer.android.com)
