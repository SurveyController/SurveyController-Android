<div align="center">
  <img src="app/src/main/res/drawable-nodpi/app_logo.webp" alt="SurveyController" width="120" height="120" />
  <h1>SurveyController Android</h1>

  [![GitHub Stars](https://img.shields.io/github/stars/shiaho777/SurveyController-Android?style=flat&logo=github&color=yellow)](https://github.com/shiaho777/SurveyController-Android/stargazers)
  [![GitHub Release](https://img.shields.io/github/v/release/shiaho777/SurveyController-Android?style=flat&logo=github&color=blue)](https://github.com/shiaho777/SurveyController-Android/releases/latest)
  ![Downloads](https://img.shields.io/github/downloads/shiaho777/SurveyController-Android/total?style=flat&logo=github&color=green)
  [![Issues](https://img.shields.io/github/issues/shiaho777/SurveyController-Android?style=flat&logo=github)](https://github.com/shiaho777/SurveyController-Android/issues)
  [![Kotlin](https://img.shields.io/badge/Kotlin-2.x-7F52FF?style=flat&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
  [![Android](https://img.shields.io/badge/Android-8.0+-3DDC84?style=flat&logo=android&logoColor=white)](https://www.android.com/)
  [![License](https://img.shields.io/github/license/shiaho777/SurveyController-Android?style=flat&color=orange)](./LICENSE)

  <p><strong>一站式问卷自动化处理程序安卓端，适配问卷星、腾讯问卷、Credamo见数平台</strong></p>
  <p>支持指定ip填写地区、信度系数、作答时长与分布比例</p>

</div>

> [!WARNING]
> **该项目仅供 HTTP 接口自动化学习与测试使用。** 请确保拥有目标测试问卷的授权再使用，**严禁污染他人问卷数据！**

---

## 主要特性

1. **多平台支持** - 同时支持问卷星、腾讯问卷、Credamo见数平台，一套工具搞定三个平台
2. **Material You 界面** - 纯 Kotlin + Jetpack Compose 构建，遵循 Material 3 设计语言，适配安卓原生体验
3. **支持二维码扫码** - 直接调用相机扫描问卷二维码，自动识别链接
4. **定制答案比例** - 支持自定义各选项权重与多选题命中概率分布
5. **指定ip地区** - 支持随机IP或指定特定地区IP提交
6. **配置导入导出** - 保存配置文件便于后续复用，跨设备同步
7. **AI 主观题作答** - 填空题自动生成作答内容（限时免费）

## 开始使用

> [!TIP]
> **安装包：** 前往 [发行版](https://github.com/shiaho777/SurveyController-Android/releases/latest) 下载最新版本 APK，安装后直接运行即可

**系统要求：** Android 8.0（API 26）及以上，arm64-v8a 架构（95%+ 现代真机）

建议配合[教程文档](https://surveydoc.hungrym0.com/)食用。Windows 版请前往 [SurveyController](https://github.com/SurveyController/SurveyController)，Mac 版请前往 [SurveyController-Mac](https://github.com/SurveyController/SurveyController-Mac)

### 从源码构建

**环境要求：** Android Studio Ladybug+ / JDK 17 / Android SDK 35

```bash
git clone https://github.com/shiaho777/SurveyController-Android.git
cd SurveyController-Android
./gradlew :app:assembleRelease
```

产物路径：`app/build/outputs/apk/release/app-release-unsigned.apk`

> [!NOTE]
> 默认构建未签名，发布前需自行配置签名 keystore。如需调试构建：`./gradlew :app:assembleDebug`

## 使用方法

1. **输入问卷** - 粘贴问卷链接，或点击扫码按钮扫描二维码
2. **自动解析** - 点击 `自动配置问卷`，自动识别平台和题目结构
3. **调整配置** - 在配置向导中，拖动滑块对各题设置答案权重和概率分布
4. **设置运行参数** - 指定目标提交份数、并发数、随机IP等设置项
5. **启动任务** - 点击 `开始执行` 并等待任务完成

## 关键配置说明

| 配置项 | 说明 |
|--------|------|
| **目标份数** | 计划提交的问卷总数。建议先测试 3~5 份，确认配置没问题后再增加 |
| **并发数** | 同时提交的任务数量。并发越高速度越快，但失败率也可能更高 |
| **AI 填空** | 开启后可自动生成填空题内容。需要先确认 AI 配置可用 |
| **随机 IP** | 使用代理 IP 模拟不同地区访问。会消耗随机 IP 额度或自备代理资源 |
| **User-Agent** | HTTP 请求标识，决定问卷后台看到的访问设备来源 |
| **作答时长** | 控制每份问卷提交时的作答时长参数 |

详细配置项请参考[教程文档](https://surveydoc.hungrym0.com/runtime.html)。

## 技术架构

```mermaid
flowchart TB
  link["问卷链接 / 二维码"]
  detect["平台识别"]
  config["答题配置<br/>选项权重 / 多选概率 / 填空内容 / 作答时长"]
  session["HTTP 会话<br/>User-Agent / Referer / 代理 IP"]
  result["提交结果<br/>成功 / 失败 / 重试"]

  link --> detect
  detect --> wjx_parse
  detect --> tencent_parse
  detect --> credamo_parse

  subgraph wjx["问卷星 HTTP 链路"]
    wjx_parse["GET 问卷页面<br/>解析 shortid / starttime"]
    wjx_answer["生成 submitdata<br/>1$选项}2$文本"]
    wjx_params["构造提交参数<br/>starttime / cst / ktimes / rn<br/>jqnonce / jqsign / t"]
    wjx_submit["POST processjq.ashx<br/>data: submitdata / sceneId"]
    wjx_parse --> wjx_answer --> wjx_params --> wjx_submit
  end

  subgraph tencent["腾讯问卷 HTTP 链路"]
    tencent_parse["GET session / questions<br/>解析 survey_id / hash / answer_session_id"]
    tencent_answer["生成 answer_survey<br/>pages / questions / options"]
    tencent_params["构造提交参数<br/>survey_id / hash / pv_uid / _<br/>duration / ua / uid / sid"]
    tencent_submit["POST /api/v2/respondent/surveys/{survey_id}/answers"]
    tencent_parse --> tencent_answer --> tencent_params --> tencent_submit
  end

  subgraph credamo["Credamo 见数 HTTP 链路"]
    credamo_parse["GET detail / init<br/>解析 shortUrl / answerToken / timestamp"]
    credamo_answer["生成 answerQstList<br/>qstId / choiceId / answerContent"]
    credamo_params["构造提交参数<br/>timeCode / answerToken<br/>answerStartTime / answerEndTime<br/>nonce / timestamp / signature"]
    credamo_submit["POST /v1/survey/answer/noauth/save"]
    credamo_parse --> credamo_answer --> credamo_params --> credamo_submit
  end

  config --> wjx_answer
  config --> tencent_answer
  config --> credamo_answer

  session --> wjx_submit
  session --> tencent_submit
  session --> credamo_submit

  wjx_submit --> result
  tencent_submit --> result
  credamo_submit --> result
```

## 交流群

如有疑问或需要技术支持，可加入QQ群：
346131215

<img width="256" alt="qq" src="app/src/main/res/drawable-nodpi/community_qr.webp" />

## 参与贡献

欢迎提交 Pull Request，改进方向包括但不限于：
- 增加对更多题型的支持
- 增加对更多问卷平台的支持
- UI/UX 优化与安卓原生特性适配
- 性能优化与代码重构

## 贡献者

感谢以下贡献者对本项目的支持：

<div style="display: flex; gap: 10px;">
  <a href="https://github.com/hungryM0">
    <img src="https://github.com/hungryM0.png" width="50" height="50" alt="hungryM0" style="border-radius: 50%;" />
  </a>
  <a href="https://github.com/shiaho777">
    <img src="https://github.com/shiaho777.png" width="50" height="50" alt="shiaho777" style="border-radius: 50%;" />
  </a>
  <a href="https://github.com/BingBuLiang">
    <img src="https://github.com/BingBuLiang.png" width="50" height="50" alt="BingBuLiang" style="border-radius: 50%;" />
  </a>
  <a href="https://github.com/dAwn-Rebirth">
    <img src="https://github.com/dAwn-Rebirth.png" width="50" height="50" alt="dAwn-Rebirth" style="border-radius: 50%;" />
  </a>
  <a href="https://github.com/Moyuin-aka">
    <img src="https://github.com/Moyuin-aka.png" width="50" height="50" alt="Moyuin-aka" style="border-radius: 50%;" />
  </a>
  <a href="https://github.com/qintaiyang">
    <img src="https://github.com/qintaiyang.png" width="50" height="50" alt="qintaiyang" style="border-radius: 50%;" />
  </a>
</div>

## 致谢

本项目基于 [SurveyController](https://github.com/SurveyController/SurveyController) 安卓端移植，感谢原项目团队的开源贡献。

## Star History

[![Star History Chart](https://api.star-history.com/svg?repos=shiaho777/SurveyController-Android&type=date&legend=top-left)](https://www.star-history.com/#shiaho777/SurveyController-Android&type=date&legend=top-left)
