# CI 自定义构建

## 使用方法

1. Fork 此仓库到你的 GitHub 账号
2. 进入 Actions 页面
3. 选择 "Custom APatch Build" workflow
4. 点击 "Run workflow"
5. 配置参数：
   - **Default SuperKey**: 默认密钥（留空则需手动输入）
   - **自动安装 AndroidPatch**: 是否自动安装（推荐开启）
   - **自动安装模块路径**: 模块路径，逗号分隔
     - 示例: `/sdcard/Download/module1.zip,/sdcard/Download/module2.zip`
6. 等待构建完成，下载 APK

## 参数说明

### Default SuperKey
- 留空：首次打开应用需要手动输入 SuperKey
- 填写：自动使用指定的 SuperKey（如 `xiaofeng777`）

### 自动安装 AndroidPatch
- `true`：打开应用后自动安装 AndroidPatch 组件
- `false`：需要手动点击 "Install Android Patch" 按钮

### 自动安装模块路径
- 留空：不自动安装模块
- 填写：自动安装指定路径的模块
- 格式：逗号分隔的完整路径
- 示例：
  ```
  /sdcard/Download/LSPosed.zip,/sdcard/Download/ZygiskNext.zip
  ```

## 本地构建

```bash
./gradlew assembleRelease \
  -PDEFAULT_SUPERKEY="xiaofeng777" \
  -PAUTO_INSTALL_APATCH="true" \
  -PAUTO_INSTALL_MODULES="/sdcard/Download/module1.zip,/sdcard/Download/module2.zip"
```

## 注意事项

1. 模块路径必须是设备上的完整路径
2. 模块需要提前推送到设备
3. 自动安装在 AndroidPatch 安装完成后触发
