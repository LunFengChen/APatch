#!/bin/bash
# APatch 自定义构建脚本

set -e

echo "================================"
echo "APatch 自定义构建脚本"
echo "================================"
echo ""

# 检查 Rust
if ! command -v cargo &> /dev/null; then
    echo "❌ Rust 未安装"
    echo "请运行: curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh"
    exit 1
fi

echo "✓ Rust 已安装"

# 检查 Android 目标
if ! rustup target list | grep -q "aarch64-linux-android (installed)"; then
    echo "添加 Android 目标..."
    rustup target add aarch64-linux-android
    rustup target add armv7-linux-androideabi
    rustup target add x86_64-linux-android
    rustup target add i686-linux-android
fi

echo "✓ Android 目标已配置"

# 编译 apd CLI
echo ""
echo "编译 apd CLI..."
cd apd
cargo build --release --target aarch64-linux-android
cd ..

echo "✓ apd CLI 编译完成"

# 编译 APK
echo ""
echo "编译 APK..."
./gradlew clean assembleRelease

echo ""
echo "================================"
echo "✓ 编译完成！"
echo "================================"
echo ""
echo "APK 位置: app/build/outputs/apk/release/app-release.apk"
echo ""
echo "安装命令:"
echo "  adb install app/build/outputs/apk/release/app-release.apk"
echo ""
