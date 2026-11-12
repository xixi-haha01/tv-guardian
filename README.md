# Android TVGuardian

专为 Android TV 设计的学习守护应用。每 20 分钟弹出 5 道题，
覆盖天津市 3-5 年级 数学、语文、英语。答错超过 10 题自动锁定 20 分钟强制休息。
**支持手机浏览器远程控制：随时上锁、解锁、触发答题、查看错题。**

## 特性

- 📺 **Android TV 原生** — Leanback 适配，D-pad 焦点导航
- 📚 **天津市 3-5 年级** — 数学、语文、英语题库
- ⏰ **定时弹题** — 默认每 20 分钟弹出 5 道
- 🔁 **智能重练** — 高频错题加权优先出现
- 🔒 **防沉迷锁定** — 累计错误 10 道后强制休息 20 分钟
- 📖 **错题本** — 本地持久化错题频次记录
- 📱 **手机远程控制** — 内嵌 HTTP Server，浏览器即控

## 🚀 手机远程控制（新增）

电视端启动后会自动在局域网开启 HTTP Server（端口 8080）。
**手机连同一个 WiFi，浏览器打开 `http://电视IP:8080/` 即可控制**。

### 控制功能

| 操作 | 说明 |
|------|------|
| 📚 立即弹题 | 家长手动触发，让孩子现在就答题 |
| 🔒 立即锁定 | 电视强制黑屏进入倒计时（可指定分钟数） |
| 🗝️ 解除锁定 | 孩子认错后提前解锁 |
| ♻️ 重置错误计数 | 重新开始统计 |
| ⚙️ 修改设置 | 远程调弹题间隔、锁定阈值、锁定时长 |
| 📊 实时状态 | 下次弹题倒计时、错误/锁定状态 |
| 📖 错题本 | 查看高频错题 Top30 |

### 怎么找电视 IP

1. 打开电视上的 TVGuardian 主界面
2. 底部状态栏会显示：`📱 手机浏览器打开: http://192.168.x.x:8080/`
3. 手机浏览器输入这个地址

### REST API（高级）

也可以直接调 API 做自动化：

```
GET  /api/status              → 状态 JSON
POST /api/lock                → {minutes: 20}  锁定
POST /api/unlock              → 解锁
POST /api/quiz                → 立即触发答题
GET  /api/settings            → 获取设置
POST /api/settings            → 修改设置 JSON
GET  /api/wrong               → 错题列表
POST /api/reset               → 重置错误计数
```

## 快速获取 APK

### 方式一：GitHub Actions（推荐）

```bash
cd tv-guardian-android
git init && git add . && git commit -m "TVGuardian v1.0"
git remote add origin https://github.com/<你的用户名>/tv-guardian.git
git push -u origin main
```

然后 GitHub 仓库 → **Actions** → **Build TVGuardian APK** → **Run workflow** → 等 5 分钟 → 下载 Artifacts 里的 APK。

### 方式二：GitHub Release

新建 Release（打 tag 如 `v1.0.0`）→ Actions 自动构建并上传 APK 到 Release Assets → 直接下载。

## 本地构建（需要 Android Studio + SDK）

```bash
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

## 项目结构

```
tv-guardian-android/
├── app/
│   ├── build.gradle.kts              # + NanoHTTPD 依赖
│   ├── proguard-rules.pro            # + NanoHTTPD keep
│   └── src/main/
│       ├── AndroidManifest.xml       # + INTERNET + cleartext
│       ├── assets/
│       │   ├── questions.json        # 题库
│       │   └── control.html          # 手机控制面板（新增）
│       ├── java/com/tvguardian/
│       │   ├── MainActivity.kt       # 启动 Server + 显示 IP
│       │   ├── QuizActivity.kt       # 全屏答题
│       │   ├── LockActivity.kt       # 全屏锁定
│       │   ├── data/
│       │   │   ├── PrefsManager.kt
│       │   │   └── QuestionBankLoader.kt
│       │   ├── model/Question.kt
│       │   └── remote/               # 远程控制（新增）
│       │       ├── RemoteControlServer.kt  # HTTP Server + REST API
│       │       └── IpUtils.kt              # 局域网 IP 获取
│       └── res/                      # UI 布局 + 图标
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradle/wrapper/
└── .github/workflows/android.yml     # CI 自动构建
```

## 自定义设置

| 参数 | 默认 | 说明 |
|------|------|------|
| interval_minutes | 20 | 弹题间隔（分钟） |
| questions_per_round | 5 | 每轮题数 |
| max_wrong_before_lock | 10 | 触发锁定的错误数 |
| lock_minutes | 20 | 锁定时长 |
| high_freq_weight | 3 | 高频错题权重倍率 |

设置可以**手机远程改**（控制面板底部表单），改完立即生效。

## 电视安装方法

1. **U 盘法**：APK → U 盘 → 插电视 → 文件管理器 → 安装
2. **ADB 法**：`adb connect <电视IP> && adb install app-debug.apk`
3. **投屏法**：小米/华为/乐视等自带安装助手

## 补充题库

编辑 `app/src/main/assets/questions.json`，按现有格式添加即可，重新构建后生效。
