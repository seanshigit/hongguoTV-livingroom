# 电视应用内自动更新

**原版已经有自动更新。** 原版 `com.hongguotv.nativeapp` 仍使用本仓库公开 Release 的 `latest/download/update.json`；历史 `seanminjie-del` 地址当前会重定向到 [seanshigit/hongguoTV-livingroom](https://github.com/seanshigit/hongguoTV-livingroom/releases)。0.12.0-local 起接入此渠道，此前 0.10/0.11 的更新器指向上游仓库。0.14.14 起，GitHub 检查或下载失败时会尝试 [KVM 备用源](https://kvm.fullcoin.com/hongguotv-update/update.json)。

仅推送修复源码、完成 CI 或提供另一个安装包，不会更新电视检查的清单。原版通道和播放修复版通道分别维护：

| 渠道 | 实际包名 | 更新清单 | 分发要求 |
| --- | --- | --- | --- |
| `legacy`，工具默认 | `com.hongguotv.nativeapp` | 原 Release `latest/download/update.json`；失败时尝试现有 KVM | 必须延续原版签名；新的原版 Release 才可设为 latest |
| `playbackfix` | `com.hongguotv.nativeapp.playbackfix` | 固定为 `https://raw.githubusercontent.com/seanshigit/hongguoTV-livingroom/updates/playbackfix.json` | 必须延续播放修复版签名；Release 不得设为 latest，不接入原版 KVM |

播放修复版从 `0.14.17-playbackfix / versionCode 32` 开始支持自己的更新清单。清单位于专用 `updates` 分支的 `playbackfix.json`，APK 来自该清单指向的本仓库 Release。源码中的渠道支持不代表安装包与清单已发布；实际可用版本以公开附件和对应清单为准。

## 现有用户如何接入

- **仍在使用原版：** 原版的自动更新功能保留，但原版无法覆盖安装不同包名或不同签名的 APK。原版签名证书 SHA-256 为 `2a569cd27ab064123d8a407442d87931abfdca3329e0c1889a44929060f2718a`；恢复匹配的私钥后才能继续向原版推送覆盖升级。播放修复版可另行安装并保留原版，通过两边「设置 → 手机备份与恢复」迁移本机记录；账号需要重新登录。
- **已安装 0.14.16-playbackfix：** 该版为独立安装包，尚未开启自己的更新器。因此需要一次手动、同签名覆盖安装 `0.14.17-playbackfix`，之后才可使用专用通道。覆盖同包名的播放修复版保留其已有数据，不要卸载后重装。
- **已安装 0.14.17-playbackfix 或后续版：** 在已发布专用清单和同签名新版 APK 的前提下，可自动检查下载，或从「设置 → 版本与更新」手动检查。切勿把播放修复版清单发布到原版的 latest 或 KVM 路径。

## 电视上的使用

- 默认自动检查并下载。启动后延迟 8 秒，只在前台浏览片库时检查，每 6 小时一次；播放、后台和其他操作浮层不会被更新打断。
- 设置 → 版本与更新，可以手动检查或关闭「自动检查并下载更新」。关闭后仍可手动更新。
- 下载并校验完成后选择「安装新版」。第一次按系统提示允许本应用安装未知来源应用，再确认覆盖安装；普通电视应用不能静默安装。
- 「稍后」推迟该版本提醒一天。网络失败保留原应用，删除未完成文件，下载重试间隔至少 30 分钟。
- 检查 HTTPS 来源、文件大小、SHA-256、实际包名、版本、最低 Android 版本和签名。版本必须递增，签名必须与当前已安装应用相同。
- 同签名覆盖安装保留收藏、稍后看、观看进度和设置。切换更新渠道会清除旧渠道检查/延后记录，保留用户关闭自动更新的偏好。

两个渠道分别使用各自固定的签名材料。未来发布必须继续使用同一渠道的密钥；临时生成的 CI Debug 密钥不能代替已交付版本的签名。不要向公开仓库上传 keystore、密码或令牌。

播放修复版检查或下载失败时继续使用已安装版本，不切换到原版清单、原版签名或原版 KVM 镜像。两个渠道均不自动降级；需要回退功能时，应基于正常代码制作更高 `versionCode` 的同签名修复版本，避免卸载导致数据丢失。

## 准备原版更新（legacy）

更新清单取自公开 Release 的 `latest/download/update.json`。仅推送源码不会发布更新；需要上传同签名 APK 与清单，并发布为非 prerelease 的 latest。标记 latest 用于渠道发现，不代表 APK 改为正式签名。现有上游同步/签名发布模板不属于本仓库当前发布流程，保持禁用。

1. 递增 `kotlin-tv/app/build.gradle.kts` 的 versionCode 和 versionName，或通过 `HONGGUOTV_VERSION_CODE` / `HONGGUOTV_VERSION_NAME` 传入相同值。
2. 使用原本地签名构建；先执行核心测试、Android 构建和 Lint。CI 的临时 Debug 签名可能与本地签名不同，不能直接用于此更新渠道。
3. 把 APK 命名为 `hongguotv-VERSION-android8.apk`。提交与 APK 对应的源码，再运行以下工具：

```sh
python3 kotlin-tv/distribution/prepare-update.py \
  --channel legacy \
  --apk outputs/hongguotv-VERSION-android8.apk \
  --previous-apk /path/to/previous-release.apk \
  --build-tools "$ANDROID_HOME/build-tools/36.0.0" \
  --tag tv-vVERSION --output outputs/update.json
```

`--channel` 可省略，默认仍为 `legacy`。工具从真实 APK 读取包名、版本、minSdk，调用 apksigner 验签并与前版比较，计算大小和 SHA-256；拒绝跨渠道包名、换签名、版本不递增和非法命名。可用 `--notes-file` 传入最多1500字的更新说明。它只生成清单，不上传文件或读取签名私钥。

4. 从同一提交用 `git archive` 导出源码 ZIP，计算附件 SHA256SUMS；先创建 GitHub Release 草稿，上传 APK、源码、update.json 和校验和，核对远端附件大小及 digest 后发布并设为 latest。不要覆盖已发布版本附件，也不要将较旧版本设为 latest。
5. 对比公开清单与下载附件，验证电视旧版实际检查、下载、校验和系统安装，然后检查版本与本机记录。保留前版 Release 供人工回退下载；客户端不会自动降级。

KVM 的 `hongguotv-mirror.timer` 约每 15 分钟运行 `distribution/mirror_update.py`：从 GitHub 获取最新清单，校验包大小和 SHA-256，先保存安装包再原子替换清单。同步失败保留上一版，较旧版本或同一版本被替换的安装包不会覆盖当前版本；电视仍会在安装前核对包名、版本和签名。

## 准备播放修复版更新（playbackfix）

1. 使用播放修复版的固定签名构建；设置 `HONGGUOTV_VERSION_CODE=32`、`HONGGUOTV_VERSION_NAME=0.14.17-playbackfix` 和 `-Phongguotv.sideBySide=true`，完成核心测试、Android 构建及 Lint。后续版本继续递增 `versionCode`。
2. 将 APK 命名为 `hongguotv-0.14.17-playbackfix-android8.apk`，以已交付的 **0.14.16-playbackfix APK** 作为前版。不能拿原版 0.14.15 作为签名或版本对照。先提交与 APK 对应的源码，再生成下列清单，使 `sourceCommit` 指向真实构建版本；未提交时生成的本地候选清单必须在发布前重新生成。

```sh
python3 kotlin-tv/distribution/prepare-update.py \
  --channel playbackfix \
  --apk outputs/hongguotv-0.14.17-playbackfix-android8.apk \
  --previous-apk outputs/hongguotv-0.14.16-playbackfix-android8.apk \
  --build-tools "$ANDROID_HOME/build-tools/36.0.0" \
  --tag tv-v0.14.17-playbackfix \
  --output outputs/playbackfix.json
```

两个实际 APK 都必须是 `com.hongguotv.nativeapp.playbackfix`，签名相同，且本次版本码必须从 31 递增至 32。工具不改变 manifest schema；客户端按实际包名隔离渠道。

3. 从对应提交导出源码 ZIP，并生成附件摘要；创建 `tv-v0.14.17-playbackfix` Release 草稿，上传 APK、源码、`playbackfix.json` 和摘要文件。核对远端附件字节数与 SHA-256 后再公开。
4. **该 Release 必须保持非 latest。** 在 GitHub CLI 中，创建与公开草稿时均显式使用 `--latest=false`，例如 `gh release edit tv-v0.14.17-playbackfix --draft=false --latest=false`；使用网页时不要勾选「Set as the latest release」。不能替换原版 Release 的 `update.json` 或原版 APK。
5. 只有 Release 附件已经公开并通过核对后，才将本次 `playbackfix.json` 提交到专用 `updates` 分支的同名路径。固定公开地址为：

```text
https://raw.githubusercontent.com/seanshigit/hongguoTV-livingroom/updates/playbackfix.json
```

该分支只保存可公开的更新元数据，不保存密钥。后续发布仍然先发布非 latest 的不可变附件，再更新此清单；旧版本附件保留。不要把较旧版本或同版本不同摘要重新公布为当前版本。

6. 核对专用清单的包名、版本、大小、摘要和 APK 附件，并再次确认原版 `latest/download/update.json` 及原版 KVM 清单未发生变化。播放修复版没有新 KVM 镜像，本轮不修改或启用服务器镜像任务。

`distribution/release.yml` 和 `distribution/publish-tv-update.py` 是旧发布模板，包含旧版本、旧证书及旧脚本路径，不能直接用于以上任一渠道。实际发布应使用经过核对的固定签名和本工具生成的清单。

## 验证发布准备工具

渠道边界、换签名、版本回退及镜像隔离回归不依赖 APK 或 Android SDK：

```sh
python3 -m unittest discover -s kotlin-tv/distribution -p 'test_*update.py'
```

另可用真实安装包检验 APK 元数据和签名。历史原版 0.11/0.10 APK 沿用 `UPDATE_APK`、`PREVIOUS_APK`、`ANDROID_BUILD_TOOLS`；播放修复版使用以下参数：

```sh
UPDATE_APK=outputs/hongguotv-0.14.17-playbackfix-android8.apk \
PREVIOUS_APK=outputs/hongguotv-0.14.16-playbackfix-android8.apk \
ANDROID_BUILD_TOOLS="$ANDROID_HOME/build-tools/36.0.0" \
UPDATE_CHANNEL=playbackfix UPDATE_VERSION=0.14.17-playbackfix UPDATE_CODE=32 \
python3 kotlin-tv/distribution/test_prepare_update.py
```

未提供真实 APK 参数时，该项集成验证明确跳过，不能当作 APK 已验签。渠道迁移验收可构建 `HONGGUOTV_TEST_RUNNER=com.hongguotv.nativeapp.UpdateAcceptance` 并在测试设备运行；实体电视和 Android 8 的系统安装流程仍需实机确认。

原版需能访问 GitHub 及其附件 CDN 或原版 KVM；播放修复版需能访问 `raw.githubusercontent.com`、GitHub 及其附件 CDN。系统需提供安装器与未知来源授权入口。
