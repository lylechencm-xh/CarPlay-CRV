# 2026-10-13 触屏流畅度优化

依据 `_carlogs/20261013/日志分析.md`，触控发送的窗口最大耗时约 8ms，但解码输出帧龄经常超过 500ms。本轮针对车机视频输出滞后；未将帧龄视为触控端到端延迟。

## 修改

- API17 解码器新增内部积压检测，不依赖应用待解码队列深度。最新输出帧龄至少 500ms、已提交的新帧比输出领先至少 250ms，且该状态持续至少 750ms，才触发恢复。
- 恢复先清除当前配置对应的排队旧帧，再 flush 解码器并等待新关键帧。保留配置边界，不直接跳过压缩参考帧继续解码。首次输出前沿用重建策略，flush 失败沿用重建回退。
- 恢复间隔至少 3 秒；正常追平、静止画面、低频输入会结束积压检测。恢复可能造成短暂画面暂停，阈值仍需实车调优。
- codec-config 输出直接释放，避免当作画面渲染或计入普通帧输出进度。
- 新增 `generation`、`renderedAgeMaxMs`、`releaseMaxUs`；接收窗口新增 `decryptMaxUs`、`convertMaxUs`、`submitMaxUs`、`frameMaxBytes`。窗口最大值不是逐帧 P95，也不是触控到画面的总耗时。帧龄仍从解码器 submit 开始，不包含此前的解密和传输。

## 实车复测

用相同手机、线缆和界面内容连续运行至少 45 分钟，在仅视频、视频加音乐两种情况下反复滑动列表、拖动地图、点击返回。比较渲染帧龄窗口分布、卡顿持续时间和恢复次数，确认没有反复关键帧等待造成新的停顿。重点观察输出恢复日志是否伴随 generation 增长，以及哪个视频处理阶段仍有数百毫秒耗时。

本地基线仍为 0.2.28.2，日志 APK 为 0.2.28.3-crv，未找回该 APK 的对应源码。本次修改不能宣称已在该实车构建上验证。普通源码构建不包含独立 CarPlay 认证资产。

## 联网核对后的第二轮优化

[AOSP 低延迟解码说明](https://source.android.com/docs/core/media/low-latency-media)明确该标准模式从 Android 11 才加入，需要底层解码器驱动支持。因此 API17 构建没有套用现代 `low-latency` 参数。

[MediaCodec 官方源码说明](https://android.googlesource.com/platform/frameworks/base/+/master/media/java/android/media/MediaCodec.java)说明了 Surface 输出和缓冲生命周期。本项目继续使用现有 Surface 解码路径，以及恢复后从关键帧开始的新参考链。

结合本地源码进一步优化：原来每个视频包都执行 `isRandomAccessPacket`，Annex B 路径需要扫描帧内容。正常播放时该结果不影响提交，只在等待关键帧时有意义。现在只在等待关键帧时扫描；正常帧仍完整提交解码，不破坏参考链。减少 CPU 工作量是源码层面的判断，实际节省时间和触屏改善幅度尚未实车测量。

新增解码输入窗口最大耗时：`inputWaitMaxUs`（单次输入缓冲等待）、`inputCopyMaxUs`（清理及复制）、`inputSubmitMaxUs`（提交）。这些指标和既有 `feedMaxUs` 一起帮助区分 Java 扫描/复制、codec 等待与驱动提交开销。

同时补充恢复边界保护：输入等待期间处理输出，如果触发了同一 codec 对象的 flush，立即放弃当前旧参考链帧。不能仅比较 codec 对象身份，因为 flush 并不创建新对象。

## 验证结果

- `:mobile:testDebugUnitTest`：第二轮最终构建 111 项通过，0 失败、0 错误；包含新增的 4 项内部积压恢复回归用例。
- `:mobile:assembleDebug`：通过。
- `:crvlegacy:lintDebug`、`:mobile:lintDebug`：通过；首次检查遇到编译产物缺失的工具异常，重跑成功。
- 第二轮最终 `:mobile:lintDebug`：独立 Gradle 进程重跑通过，含依赖库分析；中途曾遇到编译产物缺失的工具异常。
- API17 基线、Java 库扫描和公开源码检查：通过。
- `verify-crv-apk.py`：通过；最终 APK 使用单个 DEX035，仅引用 API17 可用的平台类型和成员。
- 实车触控体验与长时间播放改善幅度：待复测。
