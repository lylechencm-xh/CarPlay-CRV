# 自动模式拔线后无线接管修复（2026-10-13）

## 问题依据

用户现象：自动模式有线可用，拔线后断开。现存 20261013 实车日志显示热点 state=12（ENABLING），随后 Auto Wi-Fi unavailable，因而没有准备无线端点。该日志并未验证无线连接成功。

代码还存在时序风险：Activity 在 USB detach 后延迟 300ms 才确认设备消失；NCM 错误与 AirPlay TCP EOF 可先到。原网络服务的错误处理会关闭全部监听、会话与网络资源，控制器也可能在无线准备完成后因先到的 EOF 提前退出。

## 本次修改

- 热点处于 ENABLING 时，有界等待过渡完成，而不是立即放弃。若转为 ENABLED，读取实际配置与 AP 地址，使用已有车载 WPA/WPA2 PSK 热点；仅接受可见的有效密码，配置读取后再次确认未变化。密码不写入日志。
- 使用既有车载热点时，应用仅借用它，停止 CarPlay 不关闭该热点、不改写它的配置。处于 DISABLED 时仍使用原有创建、备份与恢复逻辑。失败、超时、隐藏密码或不支持的安全类型有明确失败原因。
- NCM bridge 报错且有独立 Wi-Fi 监听时，网络服务原子移交主监听，保留 Wi-Fi 和附件状态，然后发出专用的 wired-transport-lost 回调。控制器没有准备好凭据时仍走完整失败收尾。
- 控制器在已准备无线接管的情况下，允许先到的有线 TCP EOF、iAP2 结束或 USB 网络失败启动接管，不再依赖 Activity 的延迟拔线回调先发生。
- 重复退休 USB 传输为幂等操作；迟到的桥接错误不关闭保留下来的无线监听。有线控制结束时即使 Wi-Fi 已活跃，也进入无线维持循环。
- 只有 AP 地址对应的 AirPlay 会话实际活跃，才记录“Auto Wi-Fi CarPlay active after USB detach”；停止/恢复期间退出等待不再误报成功。接管超时仍为 45 秒。

Android 4.2.2 的热点状态、配置接口参考：[AOSP WifiService](https://android.googlesource.com/platform/frameworks/base.git/+/android-4.2.2_r1/services/java/com/android/server/WifiService.java)。OEM 实现是否能读出密码与真实 AP 地址，仍需车机验证。

## 实车验收

验证结果：应用与共享代码编译通过。热点/凭据/状态机/恢复测试 19 项通过；网络服务测试在 Android API28 与 API33 测试环境共 16 项通过，其中新增用例验证桥接错误早于拔线广播时无线监听仍开放、重复退休为幂等、最终主动停止仍关闭所有监听。合计 35 项，失败/错误均为 0。测试运行依赖使用已有本地缓存，未修改测试运行平台依赖配置。

本次未生成或安装 APK，也没有新的实车日志；已完成代码修复与软件回归，端到端无线接管待验收。

1. 使用含本次修改的构建，在自动模式建立有线连接。
2. 确认日志包含热点 ready、IPv4 listener ready、`iap2 tx=0x5703`（Wi-Fi 凭据已下发）。缺任一步时，尚未准备好拔线接管。
3. 拔线后应出现 retaining prepared Wi-Fi listener / keeping Wi-Fi handoff，随后在 45 秒内出现 AP 地址上的 AirPlay 活跃会话与视频首帧。
4. 验证触控、音乐和辅助音频；退出应用后确认借用的车载热点仍保持原配置。

软件回归与实车跑通是不同结果。本次代码修复不能替代 iPhone 与 Honda 车机端到端验收，也不能证明下一次可纯蓝牙自动重连；当前流程仍先通过 USB 完成 iAP2 凭据交换。
