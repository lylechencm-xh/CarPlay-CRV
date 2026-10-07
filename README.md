# CarPlay-CRV

基于开源项目 **DiPlay** 的 Honda CR-V 车机 CarPlay 适配项目。

- Upstream: `shihabal3amri/DiPlay`
- Target vehicle: **Honda CR-V 2021**
- Target OS: **Android 4.2.2**
- Android API baseline: **API 17**
- Package name: `com.shihab.diplay`
- Current source branch: `main`
- Current source status: **post-v0.2.28 diagnostics**
- Latest release: **v0.2.28-crv-api17**

> 本项目当前仍处于真实车辆持续验证阶段。USB / USBMUX / Lockdown 主链路已经取得明确实车进展，当前主要阻塞点集中在 Honda 原厂 MFi / iAP 认证链路。

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

## 当前实车链路状态

目前实车测试已经确认或观察到以下阶段：

```text
iPhone USB detected
        ↓
USB CarPlay configuration / config 6
        ↓
CDC-NCM selected
        ↓
USBMUX ready
        ↓
Lockdown paired / pairing record saved
        ↓
MFi / iAP authentication
        ↓
CarPlay session
```

当前最主要的阻塞点是：

```text
MFi / iAP authentication
```

因此现阶段代码优化重点已经从“USB 是否能通”转向“Honda 原厂认证链路到底由谁负责、如何安全复用”。

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

CR-V 目标明确**不优先依赖外接 CH341**。

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

无线模式仍需要在真实 CR-V 车机和 iPhone 上持续验证。

## 构建

CR-V APK 构建：

```bash
./gradlew :mobile:assembleDebug
```

最终 APK 的关键要求：

```text
package = com.shihab.diplay
minSdk = 17
targetSdk = 28
Android baseline = 4.2.2
```

CI 会检查最终 APK manifest，避免意外生成 API18/19+ APK。

## GitHub Actions

CR-V 主 CI：

```text
CR-V 2021 Android 4.2.2 Build
```

流水线主要执行：

1. CR-V Android 4.2.2 / API17 基线检查；
2. API17 Java / Android member surface 检查；
3. 上游 `shared` 测试；
4. `crvlegacy` 编译；
5. Android Lint / NewApi 检查；
6. CR-V APK 构建；
7. APK 包名检查；
8. APK `minSdk=17` 检查；
9. DEX / API17 compatibility 检查；
10. APK 签名信息检查；
11. 上传 GitHub Actions Artifact；
12. 发布或更新 GitHub Release。

## 当前 Release 与源码状态

最新正式 Release：

```text
v0.2.28-crv-api17
```

APK：

```text
CarPlay-CRV-2021-v0.2.28-api17.apk
```

Release 对应源码 commit：

```text
29c48ad22e1c6773c898c5020a2f068ef3ef58a0
```

当前 `main` 已经继续向前迭代。

当前 post-v0.2.28 代码新增了：

- Honda MediaCore 被动监听；
- `storageHandle` / iAP2 event 诊断；
- 原厂 `auth-result` 监听；
- `/dev/jdev` 诊断；
- `link_iap_adapter` 正确分类；
- 减少重复全平台扫描。

因此：

> **v0.2.28 APK 不包含最新 Honda MediaCore passive diagnostics。**

后续实车诊断应优先使用包含这些改动的新构建。

## APK 产物

正式 Release APK 不提交到 `main` 源码树。

Release 说明应记录：

- 版本号；
- package；
- minSdk；
- Android 基线；
- 签名模式；
- 源码 commit；
- 关键 CR-V 改动。

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

## 日志与实车验证

下一轮真实车机测试重点关注：

```text
Honda MediaCore bind requested=
Honda MediaCore connected
Honda MediaCore passive-listener registered=
Honda MediaCore storage event=
candidateHandle=
Honda MediaCore iap2 state=
Honda MediaCore auth-result=
Honda MediaCore Jungo device=/dev/jdev
```

同时继续保留：

- iPhone USB descriptor / config 记录；
- USB interface claim；
- NCM backend；
- USBMUX；
- Lockdown；
- pairing；
- iAP2；
- CarPlay session；
- AirPlay；
- reconnect / disconnect reason。

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
P0  Honda MediaCore storageHandle / auth flow
P0  MFi ownership / handoff
P0  完整有线 CarPlay session

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
