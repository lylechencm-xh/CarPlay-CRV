# CarPlay-CRV

基于 [DiPlay](https://github.com/shihabal3amri/DiPlay) 开发的 **2021 款 Honda CR-V CarPlay 适配项目**，优先实现有线 CarPlay。

## 适配信息
- 车机：Android **4.2.2 / API 17**（`minSdk=17`，不提高兼容基线）
- 包名：`com.shihab.diplay`
- 主要模块：USB、USBMUX、Lockdown、iAP2/MFi、CDC-NCM、AirPlay、音视频与触控

> **仍处于实车测试阶段。** 历史测试已在 USB/NCM/USBMUX/Lockdown 环节取得进展；MFi 认证及完整 CarPlay 音视频、触控链路尚未完成实车验证。无线 CarPlay 当前不是默认启用功能。

## 构建与安装

```bash
./gradlew :mobile:assembleDebug
```

APK 输出：`mobile/build/outputs/apk/debug/mobile-debug.apk`。

也可在 [GitHub Actions](https://github.com/lylechencm-xh/CarPlay-CRV/actions/workflows/crv-2021.yml) 的成功构建记录中下载 **Artifacts**。当前不提供 GitHub Release 安装包；公开构建不包含 MFi 私钥或证书。

## 测试与反馈

发生连接故障时，请保存应用生成的 `carplay-crv-*.log`，并记录 APK 版本、iPhone/iOS 版本和故障现象。日志通常位于 `/sdcard/Android/data/com.shihab.diplay/files/`，分享前请脱敏。

详细说明：[构建指南](docs/BUILD.md) · [实车测试清单](CRV-2021-TESTING.md)

## 致谢

基于 [DiPlay](https://github.com/shihabal3amri/DiPlay)；保留原项目及第三方依赖的许可声明。
