# AGENTS.md

> 本文档供 AI Agent（如 Trae、Claude、Cursor 等）阅读，用于理解项目背景、移植规则与协作约定。请先完整阅读本文档再开始任何任务。

## 项目身份

- **项目名称**：SurveyController Android
- **项目性质**：SurveyController 官方 Windows 桌面端的 **安卓端独立移植版**
- **GitHub 仓库**：https://github.com/shiaho777/SurveyController-Android
- **License**：GPL-3.0（与官方一致）
- **当前版本**：v1.0.1（功能对齐官方 v4.0.6，含官方 #103 代理剩余时长修复）

## 官方源仓库

- **官方主仓库**：https://github.com/SurveyController/SurveyController
- **官方语言**：Python 3.13+ + PySide6（Qt）
- **官方平台**：Windows（桌面端）
- **官方文档**：https://surveydoc.hungrym0.com/
- **官方 Release**：https://github.com/SurveyController/SurveyController/releases
- **官方订阅方式**：本仓库 fork 了官方主仓库到 `shiaho777/SurveyController`，本地 git remote 配置：
  - `origin` → `shiaho777/SurveyController`（fork，用于同步官方更新）
  - `upstream` → `SurveyController/SurveyController`（官方源，用于 fetch 最新代码）

  > **注意**：fork 仓库**只读**，不往里提交任何东西。那是原作者（hungryM0）的仓库，我们只用来同步官方更新。

## 版本管理策略

### 独立版本号，跟随官方节奏

安卓端使用**独立的 semver 版本号**，不绑定官方数字，但**跟随官方更新节奏递增**：

| 官方更新类型 | 安卓版本递增规则 | 示例 |
|-------------|-----------------|------|
| Patch（修 bug，如 v4.0.6 → v4.0.7） | patch +1 | v1.0.0 → v1.0.1 |
| Minor（新功能，如 v4.0.7 → v4.1.0） | minor +1 | v1.0.1 → v1.1.0 |
| Major（大改/破坏性，如 v4.1.0 → v5.0.0） | major +1 | v1.1.0 → v2.0.0 |
| 安卓独有 bug 修复（官方无更新） | patch +1 | v1.0.0 → v1.0.1 |
| 安卓独有功能（官方无对应） | minor +1 | v1.0.0 → v1.1.0 |

### Release Notes 规范

**每个 release 必须在 notes 开头写明对齐的官方版本**，格式：

```
## v1.0.1

对齐官方 vX.Y.Z

### 同步官方变更
- [官方 commit/PR 引用] 变更说明

### 安卓端修复/优化
- 变更说明
```

### versionCode 规则

Android 的 `versionCode` 必须单调递增整数。采用 `major*10000 + minor*100 + patch` 编码：

- v1.0.0 → versionCode = 10000
- v1.0.1 → versionCode = 10001
- v1.1.0 → versionCode = 10100
- v2.0.0 → versionCode = 20000

> 修改位置：[app/build.gradle.kts](app/build.gradle.kts) 的 `versionCode` 和 `versionName`，以及 [AppVersion.kt](app/src/main/java/com/surveycontroller/android/app/AppVersion.kt) 的 `VERSION` 常量。**两处必须同步**。

## 移植规则

### 核心原则

1. **业务逻辑与 UI 解耦**：延续官方设计理念，provider/engine/questions 等核心逻辑保持与官方 1:1 对应，UI 层按安卓原生体验重写
2. **不改动 `software/` 目录**：那是官方源码，我们只读不写。所有移植产物都在 `android/` 目录下
3. **对应关系**：安卓的每个 provider/core 模块都对应官方的某个 Python 文件，注释里标注（如 `// 对标桌面端 providers/wjx/parser.py`）

### 移植流程（官方发新版本时）

1. **同步官方代码**：
   ```bash
   cd /Users/shiaho/Downloads/SurveyController-main
   git fetch upstream
   git merge upstream/main
   ```
2. **分析官方变更**：
   ```bash
   git log upstream/main --oneline -20  # 查看官方最近提交
   git diff <旧版本>..<新版本> -- software/  # 查看官方源码改动
   ```
3. **移植到安卓端**：
   - 对照官方 Python 改动，找到 android/ 下对应的 Kotlin 文件
   - 保持业务逻辑一致，适配 Kotlin 语法和 Android API
   - 注意 ICU regex 等平台差异（见下方"踩坑记录"）
4. **更新版本号**：
   - 修改 `app/build.gradle.kts` 的 `versionCode` 和 `versionName`
   - 修改 `AppVersion.kt` 的 `VERSION` 常量
   - 按上方规则递增
5. **测试**：
   ```bash
   cd android
   ./gradlew :app:testDebugUnitTest  # 单元测试
   ./gradlew :app:assembleRelease    # 构建验证
   ```
6. **提交到安卓仓库**：
   ```bash
   cd android
   git add .
   git commit -m "feat: 同步官方 vX.Y.Z 变更"
   git push
   ```
7. **发 Release**：
   - 在 GitHub 创建 release，tag 用安卓版本号（如 `v1.0.1`）
   - Release notes 开头写"对齐官方 vX.Y.Z"
   - 上传签名后的 APK

### 不需要移植的内容

- **PySide6 UI 层**：官方的 `software/ui/` 是 Qt 界面，安卓用 Compose 重写，不移植
- **Velopack 自动更新**：官方桌面端的更新机制，安卓用 GitHub Release + APK 下载
- **Windows 系统集成**：注册表、电源管理、设备指纹等 Windows 专属逻辑
- **Python 工具链配置**：pyproject.toml、uv.lock 等

## 技术栈

| 层 | 技术 | 说明 |
|----|------|------|
| 语言 | Kotlin 2.x | 对标官方 Python 3.13 |
| UI | Jetpack Compose + Material 3 | 对标官方 PySide6（Qt） |
| 异步 | Kotlin Coroutines + Flow | 对标官方 asyncio |
| HTTP | OkHttp | 对标官方 httpx |
| HTML 解析 | Jsoup | 对标官方 BeautifulSoup |
| JSON | kotlinx.serialization + org.json | 对标官方 json 标准库 |
| 依赖注入 | Hilt | 官方无对应（Python 无 DI） |
| 持久化 | DataStore Preferences | 对标官方 JSON 文件存储 |
| 扫码 | CameraX + ZXing | 对标官方拖入二维码图片解析 |
| 构建 | Gradle Kotlin DSL | 对标官方 uv + pyproject.toml |

## 项目结构

```
android/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/surveycontroller/android/
│   │   │   │   ├── app/           # 版本常量、Application
│   │   │   │   ├── core/          # 核心业务逻辑（对标 software/core/）
│   │   │   │   │   ├── ai/        # AI 填空（对标 software/core/ai/）
│   │   │   │   │   ├── backend/   # 后端通信（对标 software/network/）
│   │   │   │   │   ├── engine/    # 执行引擎（对标 software/core/engine/）
│   │   │   │   │   ├── model/     # 领域模型
│   │   │   │   │   ├── network/   # HTTP 客户端、代理（对标 software/network/）
│   │   │   │   │   ├── persona/   # 人设（对标 software/core/persona/）
│   │   │   │   │   ├── psychometrics/  # 信度（对标 software/core/psychometrics/）
│   │   │   │   │   ├── questions/ # 答案生成（对标 software/core/questions/）
│   │   │   │   │   ├── reverse_fill/  # 反填（对标 software/core/reverse_fill/）
│   │   │   │   │   └── update/    # 更新检查
│   │   │   │   ├── data/          # 配置编解码、存储（对标 software/app/config/）
│   │   │   │   ├── di/            # Hilt 依赖注入
│   │   │   │   ├── provider/      # 平台适配（对标 software/providers/ + 顶层 credamo/、tencent/、wjx/）
│   │   │   │   │   ├── wjx/       # 问卷星
│   │   │   │   │   ├── tencent/   # 腾讯问卷
│   │   │   │   │   └── credamo/   # Credamo 见数
│   │   │   │   └── ui/            # Compose UI（重写，不移植 software/ui/）
│   │   │   │       ├── components/  # 可复用组件
│   │   │   │       ├── screens/    # 各页面
│   │   │   │       └── theme/      # 主题
│   │   │   ├── res/               # 资源（图片、字符串、主题）
│   │   │   └── AndroidManifest.xml
│   │   └── test/                  # 单元测试（对标 CI/unit_tests/）
│   ├── build.gradle.kts           # 构建配置
│   └── proguard-rules.pro         # 混淆规则
├── gradle/
│   ├── libs.versions.toml         # 依赖版本目录
│   └── wrapper/
├── .gitignore
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew
├── LICENSE
└── README.md
```

## 构建与测试

### 环境要求
- Android Studio Ladybug+ / JDK 17 / Android SDK 35

### 常用命令

```bash
# 编译
./gradlew :app:compileDebugKotlin

# 单元测试（360 个测试用例）
./gradlew :app:testDebugUnitTest

# 构建 release APK（产物：app/build/outputs/apk/release/app-release-unsigned.apk）
./gradlew :app:assembleRelease

# 查看依赖树
./gradlew :app:dependencies
```

### 体积优化基线

当前 release APK 体积 **3.14 MB**（从最初的 25 MB 优化而来）。已实施的优化：

| 优化项 | 收益 |
|--------|------|
| ML Kit → ZXing（移除 native 库） | -21 MB |
| 仅打包 arm64-v8a | -5 MB |
| 删除死资源（location_tree_2022.json 等） | -663 KB |
| WebP 压缩 + R8 `-allowaccessmodification` | -1.2 MB |

> **移植新功能时注意**：不要引入带 native 库的依赖，会破坏体积优势。优先选纯 Java/Kotlin 实现。

## 踩坑记录

### 1. ICU regex vs java.util.regex

**问题**：Android 真机用 ICU regex（`com.android.icu.util.regex`），比 JVM 的 `java.util.regex` 严格。单独的 `}`（不闭合量词组）在 ICU 里是**语法错误**，但在 JVM 单元测试里能跑过。

**案例**：`Regex("""\{(fillblank-[^{}]+)}""")` 在真机崩溃，单测却全过。

**规则**：正则中**字面量 `{` 和 `}` 都必须转义**（即使在字符类外）。字符类内的 `[^{}]` 不受影响。

**验证**：JVM 单测过 ≠ Android 真机能跑。如有条件，跑 `./gradlew :app:connectedDebugAndroidTest` 真机测试。

### 2. Kotlin 字符串字面量转义

`"""\{(fillblank-[^{}]+)}"""` 里 `\{` 是合法的（反斜杠加字面量 `{`），但闭合的 `}` 不转义会出问题。用三引号字符串时仍需注意 regex 元字符转义。

### 3. Gradle Kotlin DSL 的 versionCode 偏移

`splits.abi` 模式下给每个 ABI 设置 `versionCode` 偏移，在 Kotlin DSL 里 `androidApplicationVariants` 的 API 不可直接用。如需多 APK 分发，改用 `androidComponents.onVariants` API（AGP 8+）。

## 协作约定

### Git 提交规范

提交信息格式：`<type>: <描述>`

| type | 含义 |
|------|------|
| `feat` | 新功能（含官方移植） |
| `fix` | bug 修复 |
| `docs` | 文档变更 |
| `refactor` | 重构（不改功能） |
| `perf` | 性能优化 |
| `build` | 构建配置/依赖 |
| `chore` | 杂项 |

示例：
- `feat: 同步官方 v4.0.7，修复 wjx 矩阵题提交`
- `fix: 修复 ICU regex 崩溃（TencentProvider）`
- `perf: APK 体积优化，ML Kit → ZXing`
- `docs: 更新 README 贡献者列表`

### 代码风格

- Kotlin 4 空格缩进
- 包名统一 `com.surveycontroller.android.*`
- 对标官方的模块在文件头注释标注对应 Python 文件路径
- 新增 public API 必须有 KDoc 注释

### 测试要求

- 核心逻辑（provider、questions、psychometrics）必须有单元测试
- 对标官方 `CI/unit_tests/` 下的测试用例
- 移植新功能时，同步移植对应测试

## 安卓端优化方向（不依赖官方）

这些是安卓端独有、官方不会做的优化方向：

1. **真机扫码体验**：CameraX 自动对焦、曝光补偿、扫码动画反馈
2. **前台服务**：长任务用 Foreground Service 保活，避免被系统杀死
3. **Material You 动态主题**：跟随系统壁纸取色
4. **WorkManager**：定时任务、重试策略
5. **无障碍**：TalkBack 支持、字体缩放适配
6. **平板适配**：大屏双栏布局
7. **多模块化**：按 provider 拆分 Gradle 模块，按需加载

## 联系方式

- **GitHub Issues**：https://github.com/shiaho777/SurveyController-Android/issues
- **QQ 交流群**：346131215
