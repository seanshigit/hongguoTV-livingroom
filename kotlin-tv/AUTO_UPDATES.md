# 电视应用内自动更新

更新渠道：[seanminjie-del/hongguoTV-livingroom](https://github.com/seanminjie-del/hongguoTV-livingroom/releases)。**先手动覆盖安装 0.12.0-local**，接入本仓库渠道；此前 0.10/0.11 的更新器指向上游仓库，不能自动获取本仓库版本。0.14.14 起，GitHub 检查或下载失败时会尝试 [KVM 备用源](https://kvm.fullcoin.com/hongguotv-update/update.json)；更早版本要先从 GitHub 更新或手动覆盖安装 0.14.14，才具备备用能力。

## 电视上的使用

- 默认自动检查并下载。启动后延迟 8 秒，只在前台浏览片库时检查，每 6 小时一次；播放、后台和其他操作浮层不会被更新打断。
- 设置 → 版本与更新，可以手动检查或关闭「自动检查并下载更新」。关闭后仍可手动更新。
- 下载并校验完成后选择「安装新版」。第一次按系统提示允许本应用安装未知来源应用，再确认覆盖安装；普通电视应用不能静默安装。
- 「稍后」推迟该版本提醒一天。网络失败保留原应用，删除未完成文件，下载重试间隔至少 30 分钟。
- 检查 HTTPS 来源、文件大小、SHA-256、实际包名、版本、最低 Android 版本和签名。版本必须递增，签名必须与当前已安装应用相同。
- 同签名覆盖安装保留收藏、稍后看、观看进度和设置。切换更新渠道会清除旧渠道检查/延后记录，保留用户关闭自动更新的偏好。

目前 APK 使用本地 Android Debug 测试签名。未来发布必须继续使用同一签名材料；换签名不能直接覆盖，切勿通过卸载绕过校验而丢失记录。不要向公开仓库上传 keystore、密码或令牌。

## 准备下一版本

更新清单取自公开 Release 的 `latest/download/update.json`。仅推送源码不会发布更新；需要上传同签名 APK 与清单，并发布为非 prerelease 的 latest。标记 latest 用于渠道发现，不代表 APK 改为正式签名。现有上游同步/签名发布模板不属于本仓库当前发布流程，保持禁用。

1. 递增 `kotlin-tv/app/build.gradle.kts` 的 versionCode 和 versionName，或通过 `HONGGUOTV_VERSION_CODE` / `HONGGUOTV_VERSION_NAME` 传入相同值。
2. 使用原本地签名构建；先执行核心测试、Android 构建和 Lint。CI 的临时 Debug 签名可能与本地签名不同，不能直接用于此更新渠道。
3. 把 APK 命名为 `hongguotv-VERSION-android8.apk`。提交与 APK 对应的源码，再运行以下工具：

```sh
python3 kotlin-tv/distribution/prepare-update.py \
  --apk outputs/hongguotv-VERSION-android8.apk \
  --previous-apk /path/to/previous-release.apk \
  --build-tools "$ANDROID_HOME/build-tools/36.1.0" \
  --tag tv-vVERSION --output outputs/update.json
```

工具从真实 APK 读取包名、版本、minSdk，调用 apksigner 验签并与前版比较，计算大小和 SHA-256；拒绝回退、换签名和非法命名。可用 `--notes-file` 传入最多1500字的更新说明。它只生成清单，不上传文件或读取签名私钥。

4. 从同一提交用 `git archive` 导出源码 ZIP，计算附件 SHA256SUMS；先创建 GitHub Release 草稿，上传 APK、源码、update.json 和校验和，核对远端附件大小及 digest 后发布并设为 latest。不要覆盖已发布版本附件，也不要将较旧版本设为 latest。
5. 对比公开清单与下载附件，验证电视旧版实际检查、下载、校验和系统安装，然后检查版本与本机记录。保留前版 Release 供人工回退下载；客户端不会自动降级。

KVM 的 `hongguotv-mirror.timer` 约每 15 分钟运行 `distribution/mirror_update.py`：从 GitHub 获取最新清单，校验包大小和 SHA-256，先保存安装包再原子替换清单。同步失败保留上一版，较旧版本或同一版本被替换的安装包不会覆盖当前版本；电视仍会在安装前核对包名、版本和签名。

本地工具回归使用真实 0.11/0.10 APK：设置 `UPDATE_APK`、`PREVIOUS_APK`、`ANDROID_BUILD_TOOLS` 后运行 `python3 kotlin-tv/distribution/test_prepare_update.py`。渠道迁移验收：构建测试 APK 时设置 `HONGGUOTV_TEST_RUNNER=com.hongguotv.nativeapp.UpdateAcceptance`，在测试设备运行该 Instrumentation；默认仍使用原 TvAcceptance。

电视网络需能访问 GitHub 及其附件 CDN 或 KVM 备用源，系统需提供安装器与未知来源授权入口。实体电视和 Android8 本轮升级仍需实机确认。
