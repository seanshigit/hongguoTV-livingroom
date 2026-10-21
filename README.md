# 红果 TV · 题材频道

面向 Android TV / 电视盒子的红果短剧与漫剧客户端，采用 **Kotlin 原生 View + Media3**。Android 8.0 起可用，安装 APK 后直接联网，无需部署 Node 或 Docker 服务。

**已对照 `jackotom/hongguo-drama v2.2` 最新源码。** 2026-10-22 核对最新提交 [`34c960ae`](https://github.com/jackotom/hongguo-drama/commit/34c960aeaf88887cc3927d5ce3e78cd1320ff8f0)，相对 v1.73，播放请求与签名接口未变；电视版补入按真实短边分辨率判定画质的修正，保留已有有限重试、Media3 预加载及搜索补全。[电视版发布与验证记录](kotlin-tv/RELEASE_0.14.2-local.md)

[下载 APK 与源码](https://github.com/seanminjie-del/hongguoTV-livingroom/releases) · [原生工程与构建](kotlin-tv/README.md) · [预加载验证记录](kotlin-tv/RELEASE_0.13.0-local.md) · [第三方来源](kotlin-tv/NOTICE.md)

![首页实际运行截图](docs/screenshots/home.png)

新增独立的 [iPhone 原生版](ios-native/README.md)：SwiftUI + 系统播放器，支持题材片库、搜索、收藏、续播与选集，手机不需要电脑端服务。

## 功能

- **题材频道首页**：左侧题材入口、右侧单频道海报墙，正常首屏 14 张完整剧卡，少内容频道放大封面。热门包含全部已载入内容，题材可重叠、频道内去重；保留续播、收藏更新与稍后看。此前验收样本：首页漫剧 60 部、短剧 72 部。
- **搜索完整解析**：读取全部结果与分组，按剧集 ID 跨页去重，完整剧名优先；每页 24 条，剩余结果继续翻页，不按首屏数量截断。
- **搜索备用来源**：App 搜索无响应时检索官网公开片库，显示进度、范围与分页状态。本轮每类公开片库 800 部；这不等于红果 App 全站完整覆盖。
- **遥控器操作**：频道确认后切换，右键进内容、第一列左键回频道，各频道独立记忆所选剧及滚动位置；详情和搜索返回恢复位置。搜索分页固定可见，末排下键进入分页、上键回结果；播放时确认暂停/恢复，下键选集，上键更多操作。
- **原生播放**：MP4 分段读取、兼容编码选择、断点续播与选集；异常提供错误码和重试入口。
- **临时请求恢复**：片库与媒体分段 GET 遇到指定临时 HTTP / 传输故障时，退避 300、900 毫秒，最多三次请求；可取消、保留 Range 与画质，签名 POST 及数据校验失败不在请求层重放。
- **Media3官方预加载**：本集接近结束时加载下一集开头3秒的音视频样本，换集复用已预加载媒体源；仅预载下一集，后台与低内存时清理。
- **自动更新**：前台浏览时自动检查并下载本仓库新版，校验文件与签名后由系统确认安装；设置中可关闭自动更新或手动检查，播放时不打扰。[更新说明](kotlin-tv/AUTO_UPDATES.md)

![搜索实际运行截图](docs/screenshots/search.png)

## 安装与验证

本轮电视版为 **0.14.2-local / versionCode 16**，包含《聚宝仙盆》第十四季等新季漏搜修复和真实画质判定修正。APK 沿用本地 debug 签名，包名 `com.hongguotv.nativeapp`，与 0.13 / 0.14 的证书相同。公开下载及自动更新以[本仓库最新 Release](https://github.com/seanshigit/hongguoTV-livingroom/releases/latest)的 `update.json` 为准，安装及验证见 [0.14.2 记录](kotlin-tv/RELEASE_0.14.2-local.md)。同签名覆盖安装保留本机记录；安装仍需系统确认。本仓库未配置云端正式签名发布。

0.14 通过111项核心测试、Android构建与Lint（0 errors、33 warnings）；Android TV API36模拟器完成漫剧/真人短剧共4次官方预加载换集、同源交接、暂停恢复、后台释放与低内存取消。结束到下一集首帧样本为149–197毫秒，不是旧版同条件对照。自动更新完整验收见[0.12记录](kotlin-tv/RELEASE_0.12.0-local.md)，频道/搜索/大字号验收见[0.11记录](kotlin-tv/RELEASE_0.11.0-local.md)。**本轮按用户要求仅本地测试；实体电视、Android8实机及全站/全剧播放尚未验收。**

## 构建

需要 JDK 17、Android SDK 36。仓库根目录执行：

```sh
./kotlin-tv/gradlew -p kotlin-tv :core:test :app:assembleDebug :app:lintDebug --console=plain
```

APK 输出：`kotlin-tv/app/build/outputs/apk/debug/app-debug.apk`。

## 来源与许可

基于 [N3urda/hongguoTV](https://github.com/N3urda/hongguoTV) 改造，保留原生工程与旧 React Native / Node 源码。新取址适配参考 [jackotom/hongguo-drama](https://github.com/jackotom/hongguo-drama)，签名与媒体处理算法同源于 [woshishiq1/drpys](https://github.com/woshishiq1/drpys)。

原生应用及派生实现使用 **GPL-3.0-only**，见 [kotlin-tv/LICENSE](kotlin-tv/LICENSE) 和 [NOTICE](kotlin-tv/NOTICE.md)；其他原始目录按各自已有许可证。下载 APK 时可同时获取对应源码。非内容平台官方客户端。

## 原仓库旧版资料

以下为保留的 React Native / Node 版历史说明，其下载链接及发布流程不代表本仓库当前版本。

---

用于自家电视的非官方红果短剧客户端。首版采用 **React Native TV + TypeScript**，最低 Android 8.0（API 26）。电视负责界面和播放，独立 Node 服务负责内容接入与媒体分段处理，便于后续其他平台复用。

**下载：[v0.1.1 APK、源码及 Docker 部署包](https://github.com/N3urda/hongguoTV/releases/tag/v0.1.1)** · [Docker 部署指南](docs/DOCKER.md)

## 当前功能

- 推荐、关键词搜索、分页、详情和选集。
- 遥控器焦点、方向键、确定键和返回键操作。
- 原比例播放、暂停、前后跳转、上下集及自动连播。
- 本地收藏、最近观看、断点续播。
- 服务地址 / 可选访问口令设置，连接检测及播放失败重试。

v0.1.1 改善 TV 焦点滚动、返回恢复、遥控器播放按键、安全边距与选集分组，见 [本版交付说明](docs/RELEASE_0.1.1.md)。Android 8.0 的最低版本配置不等于目标电视已经验收；Apple TV 工程骨架保留，移动端和 Web 还未交付。

## 在电视上使用

1. 在电脑或 NAS 安装 Node.js 22 或更新版本，在本目录运行：

   ```sh
   npm ci
   npm run bridge -- --lan
   ```

2. 从 Release 下载 APK，拷贝到电视安装。本地构建产物为 `outputs/hongguotv-0.1.1-android8.apk`，也可以通过已连接的 ADB 安装：

   ```sh
   adb install -r outputs/hongguotv-0.1.1-android8.apk
   ```

3. 电视和服务所在设备连接同一家庭网络。在应用「设置」中输入启动日志显示的 `http://电脑局域网IP:8787`，选择「连接并保存」。电视上不能把 `127.0.0.1` 当成电脑地址。
4. 进入推荐或搜索，打开剧集开始播放。观看期间内容服务需保持运行，电脑不能休眠。

首次启动默认没有内容服务地址，也不会用测试数据冒充真实内容。「测试播放器」仅播放明确标注的 Big Buck Bunny 公开样片。

服务默认仅监听 `127.0.0.1`；`--lan` 显式开放家庭网络访问。可通过 `BRIDGE_TOKEN` 设置访问口令，并在电视端填入相同值。此服务按家庭网络使用设计，未实现公网部署所需的完整访问控制。手机 / 浏览器不要假定可直接使用原生端的媒体和请求头配置。

## 独立服务部署

只部署服务时不需要安装 React Native 依赖：

```sh
npm ci --prefix server
node server/index.mjs --lan
```

电脑 / NAS 可使用 Docker，从源码或 Release 的 Docker 部署包根目录执行：

```sh
cp .env.example .env
docker compose --env-file .env -f server/compose.yaml up -d --build
```

也提供 `ghcr.io/n3urda/hongguotv-bridge:0.1.1` 的 AMD64 / ARM64 镜像，以及根目录的镜像部署 Compose；私有镜像登录、更新和排查见 [Docker 部署指南](docs/DOCKER.md)。服务参数为 `BRIDGE_HOST`、`PORT`、`BRIDGE_TOKEN`；Node 进程不会自动读取 `.env`，应由 shell 或部署工具传入环境变量。

## 开发与验证

```sh
npm ci
npm run typecheck
npm test
npm run bridge
# 另一个终端：访问真实平台，结果写入 outputs/probe.json
npm run probe
# 需要 JDK 17、Android SDK 36、Build Tools 36.0.0、NDK 27.1.12297006
npm run build:android
```

`build:android` 生成包含 JavaScript 的 release APK，运行时不需要 Metro。首版使用本地开发签名供自用安装；签名文件被 Git 忽略，换机器构建需保留同一密钥才能直接覆盖已有安装。对外发布前应配置自己的正式签名。

服务 API 与部署边界见 [接口说明](docs/API.md)。

## 项目资料

- [v0.1.1 交付与验收](docs/RELEASE_0.1.1.md)
- [首版交付与验收](docs/FIRST_RELEASE.md)
- [Docker 部署](docs/DOCKER.md)
- [项目状态](docs/PROJECT_STATUS.md)
- [React Native 与多平台架构](docs/ARCHITECTURE.md)
- [接口研究](docs/research/2026-09-21-hongguo-open-source.md)

## 来源与许可证

项目与红果官方无关联。内容接口为非官方实现，平台变化可能导致失效；错误会明确显示，不会替换为虚假内容。

Node 服务使用固定提交的 `drpys` 源，保留原文件、GPL-3.0 许可证和来源记录，见 [第三方说明](server/vendor/NOTICE.md)。Android / tvOS 工程骨架源于 MIT 许可的 RN TV 模板。本项目代码按 [GPL-3.0](LICENSE) 提供，第三方部分保留各自许可。
