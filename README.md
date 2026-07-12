# 手机助听器

一个公益、免费、本地处理声音的 Android 助听辅助 App。

当前提供两条互不混合的下载路线：

- **有线耳机稳定版**：优先追求稳定、低延迟，适合 Type-C 或普通有线耳机。
- **蓝牙耳机先行版**：支持蓝牙入耳式耳机、开放式耳机和骨传导类耳机，用于提前体验蓝牙优化。

> 本项目不是医疗器械，不能替代专业助听器、听力师验配、医院检查或医疗诊断。

## 立即下载

- [下载 Android 蓝牙耳机先行版 APK](https://raw.githubusercontent.com/hhkkoo11/phone-hearing-aid/bluetooth-preview/release/hearing-aid-bluetooth-preview.apk)
- [查看蓝牙先行版使用教程](BLUETOOTH_PREVIEW.md)
- [下载 Android 有线耳机稳定版 APK](https://raw.githubusercontent.com/hhkkoo11/phone-hearing-aid/wired-stable/release/hearing-aid-wired-stable.apk)
- [iPhone / iPad 支持状态](IOS.md)

## 蓝牙耳机先行版

- 分支：`bluetooth-preview`
- 版本：`bluetooth-preview-2.0.0`
- 支持：蓝牙入耳式、开放式和骨传导类耳机
- 输入：默认使用手机麦克风收音
- 输出：优先使用蓝牙 A2DP 音乐通道，避免误走低质量通话通道
- 处理：三频段人声增强、轻度风噪和嘶声抑制、动态压缩、限幅及啸叫保护
- 自动化：检测耳机后自动准备助听，后台和息屏时通过前台服务继续运行

蓝牙音频存在设备自身的编解码和缓存延迟。这个版本会减少 App 内部额外延迟，但无法消除耳机硬件及蓝牙协议产生的全部延迟。

## 最简单用法

1. 先连接蓝牙耳机，并确认手机播放音乐时耳机有声音。
2. 打开 App，允许麦克风、通知和后台运行权限。
3. App 检测到耳机后会自动开始；听不清就按“大一点”。
4. 出现刺耳、耳痛、耳鸣、啸叫或明显不适时，立即按“小一点”或暂停。
5. 听歌、刷视频或打电话前，可在 App 首页点“暂停”，把耳机恢复为普通耳机使用。

## 隐私

当前版本的麦克风音频在手机本地实时处理，不上传到服务器。App 内更新只请求 GitHub 上的版本信息和 APK 文件。

## 构建

要求：JDK 17、Android SDK 35、Gradle 8.x。

```powershell
gradle testDebugUnitTest assembleDebug lintDebug
```

生成的调试 APK：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 授权

项目采用非商业公益源代码授权，详见 [LICENSE](LICENSE)。允许个人学习、研究、公益使用和非商业改进；未经授权，不得用于商业销售、付费分发、广告变现、闭源包装、捆绑硬件销售或冒充原创。
