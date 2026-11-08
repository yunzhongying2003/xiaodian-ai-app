# 商户 App（安卓唯一）

决策（2026-11-03 用户定）：开发只考虑安卓，推广时要求商户仅使用安卓端。
老板模式与后厨模式同属一个安卓原生（Kotlin + Compose）App，不做 iOS，不做跨平台。

## 编译与下载
- 编译走 GitHub Actions：仓库根目录 `.github/workflows/android.yml`。
- 产物**发布到 GitHub Releases**（tag `build-N`），文件名 `xiaodian-<versionName>-<N>.apk`。不放 Actions Artifacts。
- `versionCode` = CI 的 `GITHUB_RUN_NUMBER`（通过 `-PappVersionCode` 传入，本地默认 1）；`versionName` = 0.3.0。

### 固定签名
- 仓库 Settings → Secrets and variables → Actions 配好 4 个 secret 后，CI 编 `assembleRelease` 并用正式 key 签名：
  `SIGNING_KEYSTORE_B64`（keystore 的 base64 单行）、`SIGNING_STORE_PASSWORD`、`SIGNING_KEY_ALIAS`、`SIGNING_KEY_PASSWORD`。
- 没配 secret 时退回 debug 编译，Release 标为预发布，说明里写「未配置签名，debug 包」。
- Release 说明里写了签名证书 SHA-256，可以核对每次是不是同一把 key。
- `app/build.gradle.kts` 的 signingConfigs 只读环境变量 `SIGNING_STORE_FILE` / `SIGNING_STORE_PASSWORD` /
  `SIGNING_KEY_ALIAS` / `SIGNING_KEY_PASSWORD`，仓库里不放任何密钥。
- 本地编正式包：设置上面 4 个环境变量后 `gradle :app:assembleRelease`。
- Actions 页手动运行并勾选 `signing_selftest`：用临时 key 自测 release 签名流程，不发布 Release。
- 暂未开启 R8/minify（没有真机回归前不冒险）；从 debug 包换成正式签名包时，需要先卸载旧版再安装一次。
- 推送到 `main` 自动编译；其它分支在 Actions 页手动触发（workflow_dispatch）。
- 本地编译（需 JDK 17、Android SDK platforms;android-34、build-tools 34、Gradle 8.7）：
  ```bash
  cd app-android
  gradle :app:assembleDebug
  ```

## 结构
| 文件 | 作用 |
| --- | --- |
| `ApiClient.kt` | 单例 OkHttpClient（统一超时）+ `AuthProvider`（只加 `Authorization: Bearer <token>`）；登录 / 退出 / `/app/me`；任何接口 401 都清 token 回登录页。 |
| `Settings.kt` | SharedPreferences（MODE_PRIVATE）：服务器地址、登录 token / 用户名 / 有效期、当前模式、提醒 / 播报开关。 |
| `OrderApi.kt` / `HandoffApi.kt` / `MenuApi.kt` | 各接口的 Repository（org.json 解析）。 |
| `AppViewModel.kt` | 页面数据（StateFlow），按 id 合并刷新；回复草稿按 msgid 保存；前台每 8 秒自动刷新。 |
| `OrderAlertService.kt` | 前台服务（dataSync）：SSE 长连接 + 轮询兜底，按模式提醒（通知 + 提示音 + 播报）。 |
| `EventStream.kt` | 手写 SSE 解析（`SseParser`）和 `/app/events` 连接；`AppEvents` 通知界面刷新、告知 SSE 是否在线。 |
| `Speaker.kt` | 播报：服务器人声（`/tts/synthesize`，缓存在 cacheDir/tts）失败退回手机 TTS，按顺序播。 |
| `StatsApi.kt` | 今日统计 `/app/stats/today`。 |
| `BackgroundGuide.kt` | 电池优化白名单、国产系统自启动设置入口。 |
| `OrderScreen.kt` | 后厨看板、老板订单页（确认已收款）。 |
| `HandoffScreen.kt` | 待处理：媒体下载后查看 / 播放，回复、发图、标记已处理。 |
| `MenuScreen.kt` | 菜单：用量卡片、添加、AI 草稿批量加入、改价 / 删除（带确认）、在售开关。 |
| `LoginScreen.kt` | 登录页：服务器地址、用户名、密码。 |
| `SettingsScreen.kt` | 设置：当前用户、登录有效期、退出登录、提醒 / 播报 / 服务器人声开关、保持后台提醒、自启动设置、切换模式、退出 App。 |
| `AppLogic.kt` | 早期的纯逻辑类（PinGate / ModePolicy / OfflineQueue），目前界面未使用。 |

## 模式
- 后厨模式：全屏看板，按状态分组；一个大按钮推进到下一状态，其它状态（含取消）在「更多」里，取消需确认。
  待付款的单灰显「等待收款」，后厨不能确认收款。
- 老板模式：待处理（带角标）/ 订单（确认已收款，actor=boss）/ 菜单 / 设置。
- 两个模式可以随时一键切换（模式隔离和老板 PIN 暂缓）。

## 推送方式：SSE + 轮询兜底
- 服务器是唯一真相源，不用 FCM。
- 前台服务 `OrderAlertService` 保持 `GET /app/events`（Bearer，text/event-stream，后端每 15 秒 `: ping`）：
  - 事件 `order_new` / `order_paid` / `order_status` / `handoff_new`，收到后提醒并让界面刷新；
  - 断线指数退避重连 2s → 60s；每次连上先全量拉一次（补断线期间的单）；读超时 90 秒；
  - 401 清 token 回登录页；404（旧后端）或连不上时退回每 8 秒轮询；SSE 在线时轮询降为每 60 秒兜底；
  - SSE 和轮询共用「已提醒 key」（`new:<order_id>`、`paid:<order_id>`、`handoff:<id>`），不重复播报。
- 播报分两段（按 App 当前保存的模式）：
  - 老板：`order_new` →「有新单待收款，取餐号 X」；`handoff_new` →「有一条待人工消息」；
  - 后厨：`order_paid` →「新单，取餐号 X，自取/配送」；不播 `order_new` 和待人工。
- 播报优先用服务器人声：设置「使用服务器人声」开着、`/health` 的 `tts` 为 `"on"` 时，
  `POST /tts/synthesize {"text": "..."}`（Bearer）拿 mp3 播放；失败、超过 5 秒或 tts 不是 on 时用手机 TTS。
- 需要的权限：`POST_NOTIFICATIONS`（Android 13+ 运行时申请）、`FOREGROUND_SERVICE_DATA_SYNC`、
  `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`。
- 设置页「保持后台提醒」把 App 加入电池白名单；「自启动设置」跳小米 / 华为 / OPPO / vivo 的自启动页面（失败打开应用详情）。

## 只使用的后端接口
`/kf/orders/list`、`/kf/orders/{id}/status`、`/kf/handoff/list|reply|resolve`、`/kf/media/{id}`、
`/kf/send-image`、`/kf/menu/*`、`/kf/usage`，登录用的 `/app/login`、`/app/logout`、`/app/me`，
以及 `/app/events`（SSE）、`/app/stats/today`（今日统计，404 时退回 `/kf/usage`）、`/health`、`/tts/synthesize`（与 `/kf/*` 同域名）。

## 时间
后端时间字段带偏移（如 `2026-11-08T12:30:00+08:00`、`Z`、`+0800`）时按偏移换算；不带偏移时按本机时区理解。

## 登录
- 首次启动 / 没有 token / 收到 401 时显示登录页：`POST /app/login {username, password, device_name=Build.MODEL}` → `{token, expires_at, username}`。
- 之后所有请求带 `Authorization: Bearer <token>`；任何接口返回 401 就清掉 token 回到登录页；后台提醒服务遇到 401 会停止并通知「登录已过期，请重新登录」。
- token 存在普通 SharedPreferences（MODE_PRIVATE，仅本 App 可读），未用 EncryptedSharedPreferences。

## 未实现
真实到账通知解析（`PaymentNotificationListener` 仍是空壳，需试点实测文案）、蓝牙 SCO 音频调优、
按账号角色区分老板 / 后厨、模式隔离。
