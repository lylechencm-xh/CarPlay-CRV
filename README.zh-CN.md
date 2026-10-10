# CarPlay-CRV（简体中文）

针对 **2021 款 Honda CR-V 原厂车机** 的开源 CarPlay 适配实验项目，基于 [DiPlay](https://github.com/shihabal3amri/DiPlay) 开发，并独立维护 Android 4.2.2 兼容层。

> **尚未完成实车端到端验收。** 此分支面向 CR-V 研究和测试，不代表已可在所有 2021 CR-V 车机上稳定使用。请停车后测试。完整架构、已知问题及技术细节见 [主 README](README.md)。

## 关键事实

| 项目 | 当前设置 / 状态 |
| --- | --- |
| 目标车型 | Honda CR-V 2021 |
| 车机系统 | **Android 4.2.2 / API17**（不可提高最低版本） |
| 应用包名 | `com.shihab.diplay` |
| 源码版本字段 | `0.2.28`（另有后续诊断与兼容代码提交） |
| 核心链路 | 默认 Auto：USB 优先，拔线后尝试切换 Wi-Fi，重新插线恢复 USB；另有纯有线和 Wi-Fi 测试模式 |
| GitHub Releases | **2026-10-09 已全部清空，当前无公开 Release APK** |
| 当前 APK 获取途径 | GitHub Actions 成功运行的 Artifacts / 自行编译 |

## 当前实车进度

- 既有实车日志曾观察到 iPhone USB config 6、CDC-NCM、USBMUX ready、Lockdown 配对记录已保存等阶段。
- 支持受控本地 MFi 身份文件、板载 I²C 探测和 Honda 原厂认证链路的**被动诊断**；不预置或公开 MFi 密钥。
- Honda `link_iap_adapter` 属于 iAP 会话传输的候选接口，不被直接当作证书签名服务；MediaCore 监听器不主动启动认证或操作 `/dev/jdev`。
- 新增音视频恢复策略（H.264 flush / 关键帧、Opus 纯 Java 后备解码等）；来自吉利分支的功能仅按模块吸收，不包含其原厂特有的 USB/MFi 实现。
- **仍需完整实车确认 MFi/iAP、AirPlay 画面、音频、触控与重连。** 源码存在相关实现不等于测试通过。

## 获取 APK

1. 打开 [GitHub Actions：CR-V 2021 Android 4.2.2 Build](https://github.com/lylechencm-xh/CarPlay-CRV/actions/workflows/crv-2021.yml)。
2. 选择 `main` 分支的成功构建记录。
3. 下载该记录页面底部的 APK Artifact ZIP，解压获得 `mobile-debug.apk`。
4. 检查包名 `com.shihab.diplay`、最低 API17 及实际签名标签后，再安装到**车机**（不是 iPhone）。

注意：`debug-signed` 只能说明采用调试签名，Honda 原厂安装器是否识别仍需验证；`fixed-signed` 仅在仓库配置了固定测试签名 secrets 时提供。签名不同的 APK 可能无法直接覆盖安装。历史 Git tag 并不代表当前仍有 Release 可下载。

## 本地编译

需准备 JDK 17、Android SDK（含 android-17 平台）、Android NDK `23.2.8568313`、CMake `3.22.1`。项目自带 Gradle wrapper：

```bash
./gradlew :mobile:assembleDebug
```

输出：`mobile/build/outputs/apk/debug/mobile-debug.apk`。

普通构建不默认携带配件身份。合法授权的独立车机测试可以通过 `DIPLAY_AUTH_ASSETS_DIR` 显式提供 `offline-mfi/identity.pk8` 与 `offline-mfi/certificate.p7b` 并运行 `./gradlew :mobile:assembleStandaloneDebug`；私钥、配对记录和证书不得提交至 GitHub。参见 [构建指南](docs/BUILD.md)。

## 实车诊断日志

每次启动会生成唯一的日志文件，格式类似：

```text
carplay-crv-v<版本>-<时间>-p<PID>-b<运行时间>.log
```

优先保存在应用专用外部目录，典型位置为 `/sdcard/Android/data/com.shihab.diplay/files/`；实际以设备挂载情况为准，不保证直接保存至 U 盘。单段日志上限 512 KiB，当前运行最多保留两段；旧日志会自动清理，CR-V 日志总量控制在约 4 MiB、最多 8 个文件。使用单 USB 端口时，可先连接 iPhone 进行测试，再换成存储设备导出，或在授权 ADB 环境下读取。

下一次实车测试请记录：车机固件、iPhone/iOS、APK 构建记录和签名、最终界面状态、故障时间、完整日志。分享前删除设备敏感信息、任何 MFi 凭据、Lockdown 数据与网络口令。

## 更多资料

- [代码导览：模块、连接链路、媒体、日志与修改入口](docs/CODEBASE-GUIDE.zh-CN.md)
- [主 README：架构、MediaCore、API17、发布流程与开发优先级](README.md)
- [CR-V 实车测试清单](CRV-2021-TESTING.md)
- [编译与签名说明](docs/BUILD.md)
- [选择性吸收 DiPlay-Geely 代码的边界](docs/CRV-GEELY-SELECTIVE-PORT.md)
- [原始 DiPlay 中文 README 存档](docs/UPSTREAM-README.zh-CN.md)

## 许可证与致谢

感谢 DiPlay 及其贡献者。请遵守本仓库 [LICENSE](LICENSE)、第三方许可和上游署名要求。本项目为研究性质，未宣称获得 Apple 或 Honda 官方认证。
