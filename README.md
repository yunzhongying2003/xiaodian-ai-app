# xiaodian-ai-app

小店 AI 点餐客服商户 App（安卓唯一，老板模式 + 后厨模式同属一个 App）。

- 代码在 `app-android/`（Kotlin + Jetpack Compose），说明见 `app-android/README.md`。
- **登录**：用用户名和密码登录（`/app/login`），之后所有请求带 Bearer token；token 失效（401）自动回到登录页。
- **后厨模式**：全屏看板，按「待制作 / 制作中 / 待取·配送」分组，大字取餐号、已等时长（超 15 分钟标红）、一个大按钮推进状态。待付款的单灰显「等待收款」。
- **老板模式**：底部导航「待处理 / 订单 / 菜单 / 设置」，顶部显示今日已完成金额；确认收款只在老板的订单页。
- **不漏单**：前台服务每 8 秒**轮询** `/kf/orders/list` 和 `/kf/handoff/list`，有新单 / 新待人工就发通知、响提示音并中文语音播报。**不使用 WebSocket，也不用 FCM**。
- **编译产物**：GitHub Actions（`.github/workflows/android.yml`）编 Debug APK，**发布到 GitHub Releases**（预发布，tag 为 `build-N`，N 即 versionCode），不放 Actions Artifacts。推送到 `main` 自动编译；其它分支可手动触发（workflow_dispatch）。
- 本机（Muse VM）没有可用的 Android 编译环境，编译验证只靠 CI。
