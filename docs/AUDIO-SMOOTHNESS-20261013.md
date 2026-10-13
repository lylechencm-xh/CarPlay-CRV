# 2026-10-13 音频流畅度优化

## 依据与边界

实车日志为 LPCM 48kHz 双声道音乐，软件队列深度中位数 79 包，按当次 RTP 时间戳步进估计约 583ms，不含 AudioTrack 缓冲。累计两次再缓冲，另有约 0.305% 的接收序号缺口。持续积压与接收缺口不是同一问题，本轮不能宣称修复网络丢失。

[AOSP 音频调度说明](https://source.android.com/docs/core/audio/avoiding_pi)讨论了调度抖动和阻塞对音频连续性的影响。[AudioTrack 官方源码](https://android.googlesource.com/platform/frameworks/base/+/master/media/java/android/media/AudioTrack.java)提供旧版播放采样率设置及错误返回约定。本轮采用 API17 可用的 AudioTrack 路径，最终 APK 仍需通过 API17 验证。

## 修改

- LPCM 音乐合并已到达的短包，目标约 20ms，每轮最多取 8 包。不等待凑包，单包正常到达时立即写入；按 RTP 扩展/填充边界提取并转换完整声道采样，不修改输入包。
- 软件队列的实际 PCM 时长连续至少 2 秒超过 300ms 后，缓慢将音乐播放速率提高，最高 0.5%。每 500ms 最多变化约 0.1%；积压降至 100ms 后缓慢恢复，预缓冲/暂停时恢复原速。仅作用于 LPCM 音乐，不影响辅助语音和 AAC/Opus。该方法会轻微改变速度和音调，不是保音调时间伸缩；实际听感与收益待实车验证。驱动拒绝设置时尝试恢复原速并停用校正，记录错误及当前速率。
- 保留 300ms 音乐预填充和既有断流恢复。初次预填充或再缓冲期间，每次写入限制到启动阈值，避免大 PCM 块在未启动的缓冲中阻塞。
- 不主动裁掉音乐积压；保留原有 96 包满队列保护。因此无法保证任意长时间停顿都无跳音。

## 新增诊断

`queuePayloadBytes`、`queuePcmMs`（仅 LPCM 有效，压缩流为 -1）、`oldestPacketMs`、`trackQueuedBytes`、`trackQueuedMs`、`playbackRateHz`、`writeCallsTotal`、`batchedPacketsTotal`。AudioTrack 缓冲估算由写入量与播放头差得到，不包含扬声器后的声学延迟，也不是 API17 缺失的 underrunCount。

## 实车验证

相同音乐连续播放至少 45 分钟，分别测试仅音乐、音乐加视频、导航提示/语音插入。比较积压时长、队列满丢弃、再缓冲次数、速率校正及听感，确认没有额外短暂停顿或明显变调。检查“断流恢复”和“速率提示拒绝”的日志；记录 USB/UDP 序号缺口以单独排查传输丢失。

日志 APK 为 0.2.28.3-crv，本地源码基线不同，实车收益尚未验证。普通源码构建不含独立 CarPlay 认证资产。

## 联网核对后的恢复策略优化

[旧版 AudioTrack 官方源码](https://android.googlesource.com/platform/frameworks/base/+/16b8cff/media/java/android/media/AudioTrack.java)明确流式写入会阻塞，队列中的音频被依次消费；最小缓冲并不保证负载下无卡顿。结合本地恢复策略，发现接收间隔超过 80ms 时即便仍有约 150ms 可播音频，也可能主动暂停，因此本轮把暂停水位限制到最多 30ms，同时保留接收间隔和软件队列为空的条件。30ms 是本项目的保守初始策略，非 Android 官方推荐数值，仍需实车验证。

再缓冲后的短尾音原来必须重新达到 300ms 预填充才能播放，发送端已结束时可能一直保持暂停。现在仅当暂停后确实写入新 PCM、软件队列为空且收包/写入均已空闲至少 600ms 时，允许播放不足目标值的剩余音频。未收到新数据不会重复恢复旧音频；初始短音频仍使用 180ms 尾部等待。600ms 同样是项目策略，需测试短断流、曲目切换和结束尾音的听感。

再缓冲日志增加暂停水位和接收间隔，尾部恢复日志标明是否从再缓冲恢复。本轮没有改变 0.5% 速率校正上限；官方旧版接口说明确认该方法会改变音调，不能描述为保音调校正。

已恢复的短尾音允许自然播放完毕，避免其最后 30ms 再次被暂停。新 PCM 到达后结束该尾音状态，重新使用正常断流保护。

## 验证结果

- 联网优化后的独立构建目录运行 `:mobile:testDebugUnitTest`：125 项通过，0 失败、0 错误；音频优化两轮共新增 12 项测试，其中本轮新增 4 项恢复边界测试。
- `:mobile:lintDebug`（含依赖库分析）：通过。
- API17 基线检查、Java 库扫描和公开源码检查：通过。
- Android 17 平台 jar 确认提供 `AudioTrack.setPlaybackRate(int)`。
- 现有构建目录 `:mobile:assembleDebug`：通过，并重新通过所依赖的回归测试。
- `verify-crv-apk.py`：通过；最终 APK 为单个 DEX035，仅引用 API17 可用的平台类型及成员。
