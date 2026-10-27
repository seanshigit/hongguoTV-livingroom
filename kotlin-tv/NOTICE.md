# 原生版第三方声明

`core` 中内容请求签名、Spade URL / key 处理、MP4 CENC 处理是以下 GPL-3.0 源码的 Kotlin 移植：

- drpys, `spider/js/红果果[短].js`
- https://github.com/woshishiq1/drpys/tree/22261adfa31435e3b3ef5a730b8a2a75bcaf1715
- 本仓库对照源：`../server/vendor/hongguo.cjs`，取回于 2026-09-21。
- 本版改动：完整 Kotlin 实现；签名只选择已验证的 hash branch 0；加密媒体通过 Media3 DataSource 直接读取；增加样本边界检查、子样本处理和 AVC 原始编码恢复。

`kotlin-tv/` 原生应用源码和派生实现以 GPL-3.0-only 发布，许可证全文见 `LICENSE`。发布 APK 时同时提供本分支对应源码、Gradle 包装器和构建说明。APK 不包含 Node、React Native、JavaScript 引擎或该 JavaScript 源文件。`tools/signing-oracle.cjs` 仅为开发期生成对照测试数据的工具，不参与应用构建或运行。

2026-10-13 的访客签名适配参考：

- jackotom/hongguo-drama，`src/native/app-source.js`
- https://github.com/jackotom/hongguo-drama/tree/5a12c8840ec54fe1a7b10d0519cf64b4875b1983
- GPL-3.0-only；它同样基于上面的 drpys / N3urda 签名算法。
- 本版移植：每次请求生成本地临时访客编号，在 URL 和签名中使用相同编号；会话 nonce 与启动时间不再固定；响应必须包含所选分集，不能替换成其他集。保留已验证的 hash branch 0。
- `tools/visitor-signing-oracle.cjs` 可用上述固定版本源码生成独立对照数据；仅开发期使用，APK 无 Node 依赖。

2026-10-28 播放设备注册与空响应修复参考：

- tsukiyo-zero/hongguoTV，提交 `388931852ddc614db8b00775a3dc54c5ede6307f`：`DeviceRegister.kt`、`PlaybackDeviceSession.kt` 的匿名设备注册与进程内身份复用实现。
- 该部分继续以 GPL-3.0-only 发布；本分支仅移植注册协议、白名单 Cookie 处理和直接 `video_list` 模型解析，未引入账号凭据或持久设备身份。

2026-10-17 对照 jackotom/hongguo-drama `v1.73` / [`84b6e88e65374942c123a53c7d6ad9e78bcd101b`](https://github.com/jackotom/hongguo-drama/tree/84b6e88e65374942c123a53c7d6ad9e78bcd101b)，播放接口实现相对上述版本未变化。参考其有限重试、取消与完整响应保护思路，为本客户端的内容及分段 GET 请求增加短退避；未引入桌面端运行时依赖。具体差异见 [核对记录](../docs/research/2026-10-17-upstream-iteration.md)。

其他依赖：

- ZXing core（本机生成手机输入与更新二维码）：https://github.com/zxing/zxing — Apache-2.0
- Kotlin 标准库：https://github.com/JetBrains/kotlin — Apache-2.0
- AndroidX Media3：https://github.com/androidx/media — Apache-2.0
- OkHttp / Okio：https://github.com/square/okhttp / https://github.com/square/okio — Apache-2.0
- Bouncy Castle SM3：https://github.com/bcgit/bc-java — MIT
- jsoup（仅解析官网 HTML，不执行网页脚本）：https://jsoup.org/ — MIT
- Android desugar_jdk_libs：https://github.com/google/desugar_jdk_libs — GPL-2.0 with Classpath Exception
- org.json（仅 JVM 工具，APK 使用 Android 内置实现）：https://github.com/stleary/JSON-java — 公有领域声明
- JUnit（仅测试）：https://github.com/junit-team/junit4 — EPL-1.0

应用图标和 TV banner 沿用本仓库素材。这里的应用不是内容平台官方客户端。
