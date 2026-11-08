# 商户 App（安卓唯一）

决策（2026-11-03 用户定）：开发只考虑安卓，推广时要求商户仅使用安卓端。
老板模式与后厨模式同属一个安卓原生（Kotlin + Compose）App，不做 iOS，不做跨平台。

## 编译与下载
- 编译走 GitHub Actions：仓库根目录 `.github/workflows/android.yml`。
- 产物是 Debug APK，**发布到 GitHub Releases**（预发布，tag `build-N`，名称「小店AI助手 Debug build #N」）。
  不放 Actions Artifacts。
- `versionCode` = CI 的 `GITHUB_RUN_NUMBER`（通过 `-PappVersionCode` 传入，本地默认 1）；`versionName` = 0.2.0。
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
| `OrderAlertService.kt` | 前台服务（dataSync）：每 8 秒轮询，新单 / 新待人工 → 通知 + 提示音 + 中文 TTS。 |
| `OrderScreen.kt` | 后厨看板、老板订单页（确认已收款）。 |
| `HandoffScreen.kt` | 待处理：媒体下载后查看 / 播放，回复、发图、标记已处理。 |
| `MenuScreen.kt` | 菜单：用量卡片、添加、AI 草稿批量加入、改价 / 删除（带确认）、在售开关。 |
| `LoginScreen.kt` | 登录页：服务器地址、用户名、密码。 |
| `SettingsScreen.kt` | 设置：当前用户、登录有效期、退出登录、提醒开关、切换模式、退出 App。 |
| `AppLogic.kt` | 早期的纯逻辑类（PinGate / ModePolicy / OfflineQueue），目前界面未使用。 |

## 模式
- 后厨模式：全屏看板，按状态分组；一个大按钮推进到下一状态，其它状态（含取消）在「更多」里，取消需确认。
  待付款的单灰显「等待收款」，后厨不能确认收款。
- 老板模式：待处理（带角标）/ 订单（确认已收款，actor=boss）/ 菜单 / 设置。
- 两个模式可以随时一键切换（模式隔离和老板 PIN 暂缓）。

## 推送方式：轮询
- 服务器是唯一真相源。App **不用 WebSocket，也不用 FCM**：
  - App 在前台时，ViewModel 每 8 秒刷新一次订单和待人工，另有手动刷新按钮；
  - 前台服务 `OrderAlertService` 每 8 秒拉取，对比已见过的 id，出现新单 / 新待人工就提醒。
- 需要的权限：`POST_NOTIFICATIONS`（Android 13+ 运行时申请）、`FOREGROUND_SERVICE_DATA_SYNC`。
- 后厨机建议把 App 加入电池白名单，避免被系统杀掉后台服务。

## 只使用的后端接口
`/kf/orders/list`、`/kf/orders/{id}/status`、`/kf/handoff/list|reply|resolve`、`/kf/media/{id}`、
`/kf/send-image`、`/kf/menu/*`、`/kf/usage`，以及登录用的 `/app/login`、`/app/logout`、`/app/me`（与 `/kf/*` 同域名）。

## 登录
- 首次启动 / 没有 token / 收到 401 时显示登录页：`POST /app/login {username, password, device_name=Build.MODEL}` → `{token, expires_at, username}`。
- 之后所有请求带 `Authorization: Bearer <token>`；任何接口返回 401 就清掉 token 回到登录页；后台提醒服务遇到 401 会停止并通知「登录已过期，请重新登录」。
- token 存在普通 SharedPreferences（MODE_PRIVATE，仅本 App 可读），未用 EncryptedSharedPreferences。

## 未实现
真实到账通知解析（`PaymentNotificationListener` 仍是空壳，需试点实测文案）、蓝牙 SCO 音频调优、
按账号角色区分老板 / 后厨、模式隔离。
