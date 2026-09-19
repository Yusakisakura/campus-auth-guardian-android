# Campus Auth Guardian — Android

校园网 ePortal 自动认证守护 · Android 客户端

**Rust 内核 + Jetpack Compose + Material 3**

> 从 [campus-auth-guardian](https://github.com/nicbn/campus-auth-guardian) Windows 版移植而来，核心 Rust 业务逻辑复用率 ~85%，Android 壳全新编写。

---

## 功能特性

- **ePortal 自动认证** — 检测校园网 Portal 并自动完成 JSONP 认证
- **守护模式** — 后台常驻轮询（3 秒间隔），断线自动重连，指数退避避障
- **非校园网识别** — 通过 Portal 服务器可达性探测区分校园网与普通网络，避免误操作
- **多运营商** — 支持校园网 / 中国移动 / 中国联通 / 中国电信
- **持久通知** — 状态栏常驻通知实时显示认证状态
- **开机自启** — BootReceiver + 前台 Service，系统重启后自动恢复
- **OEM 自启引导** — 检测小米 / 华为 / OPPO / vivo / 三星等品牌，给出专属后台保活指引
- **首次使用向导** — 4 步完成学号、密码、运营商配置
- **Material 3** — 动态取色（Android 12+）、深浅色主题、Edge-to-edge

---

## 架构

```
campus-auth-guardian-android/
├── rust/core/                           # Rust 认证内核
│   ├── Cargo.toml                       # guardian-core-android (cdylib)
│   └── src/
│       ├── auth.rs                      # ePortal JSONP 认证协议
│       ├── netcheck.rs                  # 连通性 / captive portal / 非校园网检测
│       ├── config.rs                    # INI 配置读写
│       ├── guardian.rs                  # 守护循环状态机（自适应轮询间隔）
│       ├── logger.rs                    # 内存环形缓冲日志
│       ├── ipdetect.rs                  # IP 检测（Android 适配：UDP 探测 + ConnectivityManager）
│       ├── uniffi_api.rs               # UniFFI 导出层
│       └── guardian.udl                 # UniFFI 接口定义
├── app/src/main/java/com/campusauth/
│   ├── MainActivity.kt                  # 主 Activity（电池优化弹窗）
│   ├── CampusAuthApp.kt                 # Application（通知渠道注册）
│   ├── ffi/
│   │   └── GuardianBridge.kt           # Kotlin↔Rust FFI 桥接（UniFFI 生成绑定的上层封装）
│   ├── service/
│   │   ├── GuardianService.kt          # 前台 Service（WakeLock + WifiLock 保活）
│   │   ├── EventPoller.kt             # 事件分发单例（SharedFlow 广播 Rust 事件）
│   │   └── BootReceiver.kt            # 开机自启广播接收器
│   ├── viewmodel/
│   │   └── StatusViewModel.kt         # 状态页 ViewModel（单一状态源）
│   ├── util/
│   │   └── OemAutoStart.kt           # OEM 品牌检测与自启引导
│   └── ui/
│       ├── theme/                      # Material 3 主题（动态取色）
│       ├── navigation/
│       │   └── AppNavigation.kt       # NavigationCompose 路由 + 底部导航栏
│       ├── screen/
│       │   ├── StatusScreen.kt        # 首页：双状态卡 + 守护模式开关
│       │   ├── SettingsScreen.kt      # 设置：运营商 / 服务器 / 账号参数
│       │   ├── LogsScreen.kt         # 运行日志
│       │   ├── WizardScreen.kt       # 首次使用 4 步配置向导
│       │   └── AboutScreen.kt        # 关于页（版本 / 构建信息 / 设备信息）
│       └── component/
│           └── StatusCard.kt         # 状态指示卡片组件
└── gradle.properties                   # VERSION_CODE / VERSION_NAME（版本管理）
```

---

## 系统要求

| 项目 | 要求 |
|------|------|
| Android 版本 | 8.0+（API 26）|
| 目标 SDK | 35（Android 15）|
| 材质动态取色 | Android 12+ |
| CPU 架构 | arm64-v8a、x86_64 |

---

## 构建环境

```bash
# Android Studio Ladybug+（或纯 CLI 环境）

# Android NDK
sdkmanager "ndk;30.0.16248370"

# Rust Android 交叉编译目标
rustup target add aarch64-linux-android x86_64-linux-android

# UniFFI CLI（生成 Kotlin 绑定）
cargo install uniffi-bindgen
```

---

## 构建

```bash
# 直接 Android Studio 打开项目，Gradle 会自动执行 buildRust task
# 或命令行：
./gradlew assembleDebug

# APK 产出：
# app/build/outputs/apk/debug/app-debug.apk
```

`buildRust` task 会自动：
1. 交叉编译 Rust 为 `.so`（arm64 + x86_64）
2. 生成 UniFFI Kotlin 绑定
3. 将 `.so` 复制到 `jniLibs/`

---

## 版本管理

版本号在 `gradle.properties` 中维护：

```properties
VERSION_CODE=1        # 每次发版单调递增
VERSION_NAME=0.1.0    # 语义化版本
```

构建时自动注入的信息（About 页可查看）：
- Git commit hash（短）
- 提交时间
- 当前分支
- APK 构建时间

---

## 核心 Rust 模块与 Windows 版复用情况

| 模块 | 复用 | 说明 |
|------|------|------|
| `auth.rs` | ✅ 100% | ePortal JSONP 认证协议完全复用 |
| `config.rs` | ✅ 100% | INI 配置解析完全复用 |
| `guardian.rs` | ✅ ~90% | 守护循环复用，新增自适应轮询 + 非校园网判断 |
| `netcheck.rs` | ✅ ~90% | 连通性检测复用，新增 Portal 可达性探测 |
| `logger.rs` | ✅ 95% | 去除文件轮转（Android 用 logcat） |
| `ipdetect.rs` | 🔄 重写 | `GetAdaptersAddresses` → UDP 探测 + ConnectivityManager |
| `uniffi_api.rs` | 🔄 重写 | C FFI → UniFFI（功能等价） |
| WinUI 3 壳 | ❌ 重写 | Compose + Material 3 全新 UI |

---

## 权限

| 权限 | 用途 |
|------|------|
| `INTERNET` | 网络认证请求 |
| `ACCESS_NETWORK_STATE` / `ACCESS_WIFI_STATE` | 网络状态监听 |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` | 前台 Service 常驻 |
| `RECEIVE_BOOT_COMPLETED` | 开机自启 |
| `WAKE_LOCK` | CPU 唤醒锁（后台保活） |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 请求电池优化白名单 |

---

## License

MIT License