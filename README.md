# CarPlay-CRV

基于开源项目 **DiPlay** 的 Honda CR-V 车机 CarPlay 适配项目。

- Upstream: `shihabal3amri/DiPlay`
- Target vehicle: **Honda CR-V 2021**
- Target OS: **Android 4.2.2**
- Android API baseline: **API 17**
- Package name: `com.shihab.diplay`

## 项目目标

本项目的目标不是单独维护一套脱离上游的代码，而是：

1. 保持上游 DiPlay 的 `shared` 核心代码完整同步；
2. 在独立的 CR-V legacy 兼容层中适配 Android 4.2.2 / API17；
3. 保留 CR-V 专用 USB、Lockdown、iAP2、MFi、NCM、AirPlay、MediaCodec、AudioTrack 和 Wi-Fi handoff 逻辑；
4. 构建可直接用于 CR-V 车机实机测试的 APK；
5. 后续迭代不得通过提高 Android 版本要求来绕过兼容问题。

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
- CI 会对 API17 基线、Java API 使用和 Android Lint `NewApi` 进行硬检查。

## 当前架构

```text
Upstream DiPlay
      │
      ▼
 shared/
 完整上游核心
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

保持与上游 DiPlay 核心一致。

不要直接为了 CR-V 兼容性大面积修改这里的上游源码。需要 API17 适配时，优先在 `crvlegacy` 或 `mobile/Crv*` 中处理。

### `crvlegacy`

CR-V 的 Android 4.2.2 兼容层。

该模块复用上游共享代码，但会隔离只适用于新 Android 版本或非 CR-V 平台的功能，例如：

- Android Auto / AndroidX Car App
- HUD / BYD
- Glance / Cluster 扩展
- 新版 Wi-Fi P2P
- LocalOnlyHotspot
- 高版本热点管理接口
- 非 CR-V 实验性网络路径

### `mobile`

CR-V 专用运行层，目前包括：

- CR-V CarPlay Activity
- USB Host
- iPhone USB 枚举与重枚举
- USBMUX
- Lockdown pairing
- iAP2
- MFi authentication
- CDC-NCM
- IPv6 / VPN / TUN
- AirPlay
- H.264 MediaCodec
- AudioTrack
- Touch input
- Android 4.2.2 legacy Wi-Fi handoff
- CR-V 诊断日志与自动重连

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

## 无线 CarPlay

Android 4.2.2 没有现代 `LocalOnlyHotspot` API，因此 CR-V 无线模式采用 legacy Wi-Fi framework 方案。

当前实现包括：

- 反射调用 Honda / Android legacy hotspot 接口；
- 自动检测 AP 网络接口和 IPv4 地址；
- iAP2 wireless identification；
- Wi-Fi SSID / passphrase / channel handoff；
- AirPlay listener。

无线模式仍需要在真实 CR-V 车机和 iPhone 上持续验证。

## MFi

CR-V 目标明确**不使用外接 CH341**。上游 `shared/` 可能仍保留 CH341 通用实现，但 CR-V 的 `mobile/Crv*` 运行路径不会发现、申请权限或连接 CH341 设备。

当前 CR-V 认证方向为：

1. 优先研究和复用 Honda 原厂认证服务 / Binder / HAL；
2. 仅做板载 `/dev/i2c-*` 节点和权限元数据诊断，不直接读写寄存器；
3. 在原厂认证路径未完成前，Standalone 测试使用显式提供的合法 Local MFi 身份。

项目不会把 MFi 私钥或证书提交到仓库。

Standalone CarPlay 测试需要通过受控本地方式提供授权的 MFi 资产：

```text
identity.pk8
certificate.p7b
```

CI 普通源码构建不会自动包含这些认证材料。

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

CI 还会检查最终 APK manifest，防止生成错误的 API18/19+ APK。

## GitHub Actions

CR-V 主 CI：

```text
CR-V 2021 Android 4.2.2 Build
```

流水线主要执行：

1. CR-V Android 4.2.2 / API17 基线检查；
2. API17 Java library surface 扫描；
3. 上游 `shared` 测试；
4. `crvlegacy` 编译；
5. Android Lint / NewApi 硬检查；
6. CR-V APK 构建；
7. APK 包名检查；
8. APK `minSdk=17` 检查；
9. APK 签名信息检查；
10. 上传 GitHub Actions Artifact；
11. 发布/更新 GitHub Release 中的正式 API17 APK。

## APK 产物

CI 成功后会生成：

```text
CarPlay-CRV-2021-v0.2.15-api17.apk
```

Release 说明会记录版本、包名、minSdk、签名模式和源码 commit；APK 不再自动提交回 `main`。

## CR-V 车机安装说明

目标车机为 Android 4.2.2，因此 APK 必须是：

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

因此当前测试重点是先确保 APK 本身满足 Android 4.2.2 / API17。

## 实机验证项目

以下项目不能由 GitHub CI 完全替代，必须在真实 CR-V + iPhone 上验证：

- iPhone USB 枚举 / 重枚举
- Honda USB Host interface claim
- Lockdown pairing
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

## 开发规则

CR-V 分支开发遵循以下原则：

1. **Android 4.2.2 / API17 是固定硬基线。**
2. 优先融合上游，而不是长期 fork 大量核心逻辑。
3. 上游核心功能与 CR-V 兼容层分离。
4. 遇到高版本 API 时做兼容实现，不提高 minSdk。
5. 保持 `com.shihab.diplay` 包名，避免影响 Honda 车机识别。
6. 所有车机相关更改必须经过 CI 和真实车辆双重验证。

## License / Attribution

本项目基于 DiPlay 开发。

请保留原项目版权、许可证以及相关第三方依赖的许可声明。
