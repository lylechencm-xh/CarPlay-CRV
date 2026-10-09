# CarPlay-CRV

基于开源项目 **DiPlay** 的 Honda CR-V 车机 CarPlay 适配项目。

- Upstream: `shihabal3amri/DiPlay`
- Target vehicle: **Honda CR-V 2021**
- Target OS: **Android 4.2.2**
- Android API baseline: **API 17**
- Package name: `com.shihab.diplay`
- Current source branch: `main`
- Current source status: **v0.2.28 source + post-v0.2.28 diagnostics/compatibility improvements**
- Release status: **当前没有已发布的 GitHub Release（历史 Releases 已清空）**
- Build delivery: **GitHub Actions Artifact（成功构建后下载）**

> **实验性项目 / 尚未确认完整 CarPlay 实车可用。** 历史实车日志曾验证 USB、CDC-NCM、USBMUX、Lockdown/pairing 等阶段，认证和后续画面、音频、触控的完整闭环仍需复测。源码已支持受控的本地 MFi 身份配置及 Honda 原厂认证链路诊断；实现能力不等于实车认证成功。

## 项目目标

本项目的目标不是单独维护一套脱离上游的代码，而是：

1. 保持上游 DiPlay 的 `shared` 核心代码尽可能完整同步；
2. 在独立的 CR-V legacy 兼容层中适配 Android 4.2.2 / API17；
3. 保留 CR-V 专用 USB、Lockdown、iAP2、MFi、NCM、AirPlay、MediaCodec、AudioTrack 和 Wi-Fi handoff 逻辑；
4. 构建可直接用于 CR-V 车机实机测试的 APK；
5. 后续迭代不得通过提高 Android 版本要求来绕过兼容问题；
6. 优先复用 Honda 车机原生 iAP / MFi 基础设施，而不是假设必须依赖外接认证硬件。

## CR-V 兼容基线 — DO NOT RAISE

CR-V 车机基线固定为：

- **Android 4.2.2**
- **API 17**
- `mobile minSdk = 17`
- `crvlegacy minSdk = 17`

任何后续开发都必须遵守：

- CR-V 运行代码不得依赖 API18+ 才存在的 Android API；
- 如果上游新代码要求更高 API，必须做 API17 兼容实现或隔离该功能；
- 不允许为了让编译通过而提高 `minSdk`；
- CI 会对 API17 基线、Java API 使用、DEX 和 Android Lint `NewApi` 进行检查。

## 当前架构

```text
Upstream DiPlay
      │
      ▼
 shared/
 上游核心代码
      │
      ▼
 crvlegacy/
 Android 4.2.2 / API17 兼容层
      │
      ▼
 mobile/
 CR-V 专用入口、UI、USB 与连接逻辑
      │
      ▼
 CR-V APK
```

### `shared`

尽量保持与上游 DiPlay 核心一致。

需要 API17 适配时，优先在 `crvlegacy` 或 `mobile/Crv*` 中处理，避免为了单一车型大面积破坏上游结构。

### `crvlegacy`

CR-V 的 Android 4.2.2 兼容层。

该模块复用上游共享代码，同时隔离只适用于新 Android 版本或非 CR-V 平台的功能，例如：

- Android Auto / AndroidX Car App
- HUD / BYD
- Glance / Cluster 扩展
- 新版 Wi-Fi P2P
- LocalOnlyHotspot
- 高版本热点管理接口
- 非 CR-V 实验性网络路径
- API21+ USB helper
- API29+ 网络 helper
- API33+ USB compatibility helper

### `mobile`

CR-V 专用运行层，目前包括：

- CR-V CarPlay Activity
- USB Host
- iPhone USB 枚举与重枚举
- USBMUX
- Lockdown pairing
- iAP2
- MFi authentication diagnostics
- Honda MediaCore passive diagnostics
- CDC-NCM
- IPv6 / VPN / TUN
- AirPlay
- H.264 MediaCodec
- AudioTrack
- Touch input
- Android 4.2.2 legacy Wi-Fi handoff
- CR-V 诊断日志与自动重连

## 当前实车链路状态（证据分级）

| 层级 | 进展 | 证据边界 |
| --- | --- | --- |
| Android 4.2.2 / API17 | 源码及 CI 固定基线 | CI 不能证明车机可正确解码/运行全部功能 |
| USB CarPlay config 6 / CDC-NCM | 历史实车日志出现配置切换及 NCM ready | 不代表所有 USB 线束/重连场景稳定 |
| USBMUX / Lockdown | 历史实车日志出现 USBMUX ready、paired and saved | 只表示配对/传输阶段取得进展 |
| MFi / iAP2 | 已支持本地凭据来源和原厂接口被动诊断 | 仍需新一轮真车日志证明握手结果 |
| AirPlay / 音视频 / 触控 | 已有实现与模拟/自动化检查 | 尚无完整 CR-V 实车闭环验收记录 |

历史断点集中在 **MFi / iAP 认证**。之后本地打包身份文件的方案已纳入源码，不能再把“缺少本地证书”视为唯一原因，也不能把本地文件存在等同于认证成功。下一次测试应优先分析 Honda OEM 认证资源归属和会话是否真正进入 AirPlay。

连接路径（阶段示意，并非已全部通过）：

```text
iPhone USB -> config 6 / CDC-NCM -> USBMUX -> Lockdown
           -> iAP2 / MFi -> CarPlay session -> AirPlay
           -> H.264 video / audio / touch
```

## 有线 CarPlay 主链路

```text
iPhone USB
   ↓
USB Host
   ↓
USBMUX
   ↓
Lockdown
   ↓
com.apple.carkit.service
   ↓
iAP2 / MFi
   ↓
CDC-NCM
   ↓
IPv6 / VPN / TUN
   ↓
AirPlay
   ↓
Video / Audio / Touch
```

目前优先保证 **Wired USB CarPlay** 稳定运行，再继续完善无线 handoff。

## Honda MFi / MediaCore 调查

近期对 CR-V 原厂系统的分析表明：

`link_iap_adapter` 的 Binder descriptor 为：

```text
com.honda.telematics.server.link.iap.ILinkManagerIAPAdapter
```

当前代码已经不再把它误判为 MFi certificate/signature 服务，而将其分类为：

```text
iAP session transport
authContract = false
```

这意味着 Honda 原厂认证更可能由另一条链路负责。

当前已经定位到 Mitsubishi/Honda 原厂 MediaCore：

```text
package:
com.mitsubishielectric.ada.framework.mcservice

service:
com.mitsubishielectric.ada.framework.mcservice.MediaCoreService

binder:
com.mitsubishielectric.ada.framework.mcservice.IMediaCoreService
```

从原厂接口特征看，Honda 的认证模型更接近：

```text
iAPauthStart(int storageHandle, int authKind): int
        ↓
onIAPauthResult(int)
```

而不是简单暴露：

```text
getCertificate()
signChallenge()
```

因此当前优先调查的原厂路径为：

```text
iPhone
  ↓
Honda USB / iAP stack
  ↓
MediaCoreService
  ↓
Jungo / OEM native stack
  ↓
/dev/jdev
  ↓
MFi authentication
```

## Honda MediaCore 被动诊断

当前 `main` 已加入 **passive Honda MediaCore listener**。

该监听器只用于观察原厂 MediaCore 状态：

- 不主动执行 `iAPauthStart()`；
- 不直接打开 `/dev/jdev`；
- 不主动抢占 iAP 认证；
- 不发送原厂未知 Binder 认证事务；
- 不直接读写 MFi 硬件。

重点记录：

```text
Honda MediaCore connected ...
Honda MediaCore passive-listener registered=...
Honda MediaCore storage event=...
Honda MediaCore iap2 state=...
Honda MediaCore auth-result=...
```

尤其关注：

```text
candidateHandle
```

因为原厂 `iAPauthStart()` 的第一个参数就是 `storageHandle`。

当前目标是先通过真实 CR-V 日志确认：

1. MediaCore 是否允许当前 APK 绑定；
2. 原厂服务是否能看到同一台 iPhone；
3. 是否能捕获有效 `storageHandle`；
4. 是否能观察到原厂认证结果；
5. `/dev/jdev` 的存在、权限和实际所有者；
6. 我们自己的 USB claim 是否与 Honda 原厂 iAP ownership 冲突。

## MFi

CR-V 目标明确**不优先依赖外接 CH341**。本地打包/安装有合法授权身份的测试方案已支持，但不意味着所有 iPhone 或原厂 MFi 会话已通过实车验收。

上游 `shared/` 可能仍保留 CH341 通用实现，但 CR-V 主运行路径将 Honda 原厂认证基础设施作为优先调查方向。

当前认证优先级：

1. **Honda MediaCore / Jungo 原厂认证链路**
2. 板载 MFi / `/dev/i2c-*` 能力探测
3. 合法 Local MFi identity 作为受控 fallback
4. 外接 CH341 仅保留通用上游能力，不作为 CR-V 默认方案

Standalone / fallback 测试可能使用：

```text
identity.pk8
certificate.p7b
```

这些文件不会提交到仓库，也不会默认打包进公开 APK。

## `/dev/jdev`

当前代码会探测 Honda 原厂：

```text
/dev/jdev
```

记录：

- 是否存在；
- 是否可读；
- 是否可写；
- uid / gid / mode；
- 与 MediaCore / Jungo 的关联信息。

当前不会主动打开该 proprietary device。

原因是目前尚未确认其 ioctl / protocol，也不希望在真实车机上与 Honda 原厂 MediaCore 发生资源竞争。

## 无线 CarPlay

Android 4.2.2 没有现代 `LocalOnlyHotspot` API，因此 CR-V 无线模式采用 legacy Wi-Fi framework 方案。

当前实现包括：

- 反射调用 Honda / Android legacy hotspot 接口；
- 自动检测 AP 网络接口和 IPv4 地址；
- iAP2 wireless identification；
- Wi-Fi SSID / passphrase / channel handoff；
- AirPlay listener。

**当前主入口设置了 `Crv2021Config.WIRED_ONLY = true`，所以无线逻辑虽有源码实现，但不是当前默认可用功能。** 无线模式仍需真实 CR-V 车机与 iPhone 验证。

## 旁支上游按模块吸收（2026-10-08）

参考：[DiPlay-Geely-Android43](https://github.com/xikai6282/DiPlay-Geely-Android43)。本项目**不整体移植吉利车机代码**，只吸收与 Android 4.2.2 / API17 和 CR-V 主架构兼容的独立模块：

- H.264 队列溢出时优先在解码线程尝试 `MediaCodec.flush()`，再请求关键帧；异常时重新创建解码器。
- 改善画面接收/解码线程优先级，并为视频 TCP 设置低延迟选项。
- 当车机缺少可用的 `audio/opus` MediaCodec 时，使用 Concentus 纯 Java Opus 解码后备方案。
- 不合并吉利 H52 专用 USB、原厂 Binder、硬件特定流号或未知 MFi 身份材料。

详见 [选择性吸收说明](docs/CRV-GEELY-SELECTIVE-PORT.md)。这些改进仅说明源码已包含相应策略，**尚不能证明 CR-V 已通过持续音视频测试**。

## 构建

构建工具链：JDK 17、Android SDK（含 Android 17 平台与编译所需 SDK）、NDK `23.2.8568313`、CMake `3.22.1`，使用仓库内 Gradle wrapper。

CR-V APK 构建：

```bash
./gradlew :mobile:assembleDebug
```

输出：`mobile/build/outputs/apk/debug/mobile-debug.apk`。普通源码/CI APK **不默认携带 MFi 配件身份**；如果需要使用合法授权身份进行 standalone 测试，可根据 [构建说明](docs/BUILD.md) 配置 `DIPLAY_AUTH_ASSETS_DIR` 并运行 `./gradlew :mobile:assembleStandaloneDebug`。不要向公开仓库提交密钥、证书或包含它们的 APK。

最终 APK 的关键要求：

```text
package = com.shihab.diplay
minSdk = 17
targetSdk = 28
Android baseline = 4.2.2
```

CI 会检查最终 APK manifest，避免意外生成 API18/19+ APK。

## GitHub Actions 与 APK 下载

主构建工作流：[CR-V 2021 Android 4.2.2 Build](https://github.com/lylechencm-xh/CarPlay-CRV/actions/workflows/crv-2021.yml)。

CI 主要运行：

1. API17 / Java API / 源码凭据保护检查。
2. `crv-simulator` 记录回放测试、`shared` 单元测试与 `crvlegacy` 编译。
3. Android Lint、APK 构建、DEX / API17 surface、包名和 `minSdk` 验证。
4. v1/JAR APK 签名检查及 GitHub Actions Artifact 上传。

**下载步骤**：进入 Actions → `CR-V 2021 Android 4.2.2 Build` → 选择 `main` 分支的成功运行 → 在运行页面的 **Artifacts** 下载 `CarPlay-CRV-2021-...-api17-debug-signed` 或 `...-fixed-signed` 压缩包 → 解压后获取 APK。Artifact 可能需要登录 GitHub，也受 GitHub 保留期限限制。

**签名说明**：未配置固定签名的 CI 构建使用 `debug-signed`，可用于构建/安装排查，但不保证被 Honda 安装器识别。仅在配置固定签名 secrets 时输出 `fixed-signed`。不同签名的 APK 不一定能直接覆盖安装。

**发布规则**：正常 push 和 PR 只构建 Artifact，不自动创建 GitHub Release。工作流允许在 `workflow_dispatch` 中显式设置 `publish_release=true` 后发布历史配置的 `v0.2.28` 标签；需要重新正式发布前，应先核对当前版本号、标签、APK 和更新说明。

## 当前 Release 与源码状态

2026-10-09 已清空本仓库历史 **17 个 GitHub Releases**；[Releases 页面](https://github.com/lylechencm-xh/CarPlay-CRV/releases)当前没有可下载的 APK。

- 当前 `mobile` 源码版本字段：`versionName = 0.2.28`、`versionCode = 47`（debug 构建带 `-crv` 名称后缀）。
- 历史 Git 标签（如 `v0.2.28-crv-api17`）仍保留，但**标签不等于存在 Release 或可下载 APK**。
- `main` 已包含后续 Honda MediaCore 被动诊断和吉利旁支的选择性音视频兼容优化；不能用旧 `v0.2.28` Release 的说明代表当前源码。
- APK 不进入 `main` 源码树；下载请使用上方 GitHub Actions Artifacts，或自行构建。

后续若发布新 Release，应明确版本号、构建 commit、API17 基线、签名类型、认证来源及真车验证程度。

## CR-V 车机安装说明

目标车机为 Android 4.2.2，因此 APK 必须满足：

```text
minSdk <= 17
```

如果 APK 设置成 API19：

```text
车机 API17
APK minSdk 19
→ Android 层直接不兼容
```

Honda 原厂安装器有可能不会显示明确的 `INSTALL_FAILED_OLDER_SDK`，而只表现为 APK 不显示、无法识别或无法安装。

除此之外，Honda 原厂安装器还可能受以下因素影响：

- APK 签名
- 包名
- 安装白名单
- USB / 存储介质
- APK 文件格式
- 原厂系统安装策略

因此 CR-V 版本必须持续锁定 Android 4.2.2 / API17。

## 日志获取与实车复测

应用以每次会话唯一文件名记录诊断信息（包含版本、毫秒时间、PID、开机运行时长）：

```text
carplay-crv-v<version>-<yyyyMMdd-HHmmss-SSS>-p<PID>-b<bootMs>.log
```

优先保存在 `Context.getExternalFilesDir(null)`，典型路径为：

```text
/sdcard/Android/data/com.shihab.diplay/files/
```

如果应用专用外部目录不可用则回退到应用私有 `filesDir`；**实际路径由车机挂载方式决定，不能保证直接写入移动硬盘**。只有一个 USB 接口时，先插 iPhone 完成测试，拔出后再从车机导出日志；也可在已授权 ADB 的条件下通过 `adb pull` 提取。

日志包含时间、序列号、阶段状态、异常及受限堆栈；不会主动记录原始配件私钥和完整协议载荷。日志仍可能包含设备或系统信息，**分享前请检查并脱敏**，不要公开 MFi 密钥、证书、Lockdown 配对记录、令牌或热点密码。

下次实测优先搜索：

```text
Honda MediaCore bind requested=
Honda MediaCore connected
Honda MediaCore passive-listener registered=
Honda MediaCore storage event=
candidateHandle=
Honda MediaCore iap2 state=
Honda MediaCore auth-result=
Honda MediaCore Jungo device=/dev/jdev
USBMUX ready
Lockdown paired
CarPlay active
```

报告请包含：**APK 的 Actions 运行链接与 SHA/签名标签、车机 Android 固件、iPhone/iOS、连接步骤、车机界面最终状态、完整唯一命名 `.log`、故障发生时间**。同时关注 USB config6、NCM 后端、USBMUX、Lockdown、MFi、AirPlay、视频、音频和触控。

详细实车检查清单：[CRV-2021-TESTING.md](CRV-2021-TESTING.md)。历史测试文档中的旧版 Release/文件名仅供参考，下载路径请以上述 Actions 为准。

## 实机验证项目

以下项目不能由 GitHub CI 完全替代，必须在真实 CR-V + iPhone 上验证：

- iPhone USB 枚举 / 重枚举
- Honda USB Host interface claim
- Lockdown pairing
- Honda MediaCore Binder 访问
- MFi authentication
- CDC-NCM
- Android 4.2.2 VpnService / TUN 行为
- AirPlay TCP
- H.264 MediaCodec
- AudioTrack
- Touch input
- legacy Wi-Fi hotspot
- iPhone Wi-Fi handoff
- 无线 AirPlay 视频 / 音频 / 触控

## 当前开发优先级

```text
P0  新一轮实车日志核验 Honda MediaCore storageHandle / auth flow
P0  核验合法本地 MFi 身份在实际会话中的认证结果与 USB ownership / handoff
P0  完整有线 CarPlay session（AirPlay 视频、音频、触控）

P1  /dev/jdev 权限与 owner
P1  Honda Binder transaction compatibility
P1  USB ownership conflict / handoff
P1  NCM / AirPlay 稳定性

P2  Local identity fallback
P2  Wireless CarPlay
```

## 开发规则

CR-V 开发遵循以下原则：

1. **Android 4.2.2 / API17 是固定硬基线。**
2. 优先融合上游，而不是长期 fork 大量核心逻辑。
3. 上游核心功能与 CR-V 兼容层分离。
4. 遇到高版本 API 时做兼容实现，不提高 minSdk。
5. 保持 `com.shihab.diplay` 包名，避免影响 Honda 车机识别。
6. 所有车机相关更改必须经过 CI 和真实车辆双重验证。
7. 对 Honda 原厂 Binder / device 节点采用保守策略：先观察、再验证、最后才主动调用。
8. 不在公开仓库提交 MFi 私钥、证书或车辆敏感数据。

## License / Attribution

本项目基于 DiPlay 开发。

请保留原项目版权、许可证以及相关第三方依赖的许可声明。
