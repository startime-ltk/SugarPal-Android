# 糖伴 SugarPal · 安卓端

糖伴 SugarPal 的 Android 客户端：本地血糖记录、趋势与日历视图、AGP 图谱报告、健康摘要分享、AI 控糖建议、异常报警与应用内更新。

> **当前版本 v1.2.10**（versionCode 13，2026-11-04）· 包名 `com.sugarpal.app` · 支持 Android 7.0（API 24）及以上。
> 桌面版见 [startime-ltk/SugarPal](https://github.com/startime-ltk/SugarPal)。

## 两端关系

- 两端**共用同一套业务口径**：数据库字段表、餐别自动识别、凌晨 4 点业务日边界、报警阈值（默认低 3.9 / 高 13.9 mmol/L）、AGP 指标（TIR / TAR / TBR / SD / CV / 估算 HbA1c）。
- 两端**各自使用本地数据库**：PC 端为 H2，安卓端为原生 SQLite。表结构字段一致，时间统一以本地时区 `yyyy-MM-dd HH:mm:ss` 文本存储，保证业务日归属跨端一致。
- **目前没有云同步、账号体系或服务端**，两端的记录不自动互通；跨端迁移通过 CSV 导入 / 导出完成。

## 功能

| 分类 | 功能 |
|------|------|
| 记录 | 血糖记录新增 / 修改 / 删除、备注补充与修改、餐别自动识别（空腹 / 餐前 / 餐后 / 睡前 / 三餐及加餐后时长）、重复时间点校验、0~50 mmol/L 范围校验 |
| 图表 | 血糖趋势曲线、按月日历视图（点圆点查看单次测量详情）、健康数据多维趋势 |
| 报告 | AGP 标准化葡萄糖图谱：24 小时分位曲线、TIR / TAR / TBR、平均血糖、标准差 SD、变异系数 CV、估算 HbA1c、按餐别 / 分时段统计；可导出 PDF |
| 分享 | 健康摘要 PDF 生成，经 FileProvider 调用系统分享 |
| 智能 | AI 多模型建议（支持 6 家模型服务），API Key 混淆存储在本机；不配置则功能不可用 |
| 监测 | 异常报警阈值设置（默认低 3.9 / 高 13.9 mmol/L），录入后即时本地通知（NotificationChannel + 震动） |
| 数据 | CSV 批量导入（模板下载、选择文件、预览后入库、缺失餐别推断）、CSV 全量导出、清空全部数据 |
| 更新 | 应用内检查更新 → 下载 APK → 调系统安装器安装（Android 8.0+ 未知来源授权引导）；默认 GitHub Releases，内置 ghproxy / moeyy / llkk 加速镜像并回退直连；可自定义更新源地址与下载加速地址 |
| 设置 | AI 模型设置、报警阈值、软件更新、数据管理、动态版本号显示 |

## 技术栈

| 组件 | 版本 / 配置 |
|------|-------------|
| 语言 | Kotlin 2.2.10 |
| 界面 | 原生 Android View + XML 布局（未使用 Compose） |
| 兼容库 | androidx.appcompat 1.7.0 |
| 数据库 | 原生 SQLite（SQLiteOpenHelper） |
| 编译 | compileSdk / targetSdk 36，minSdk 24，Java 17，启用 coreLibraryDesugaring |
| 构建 | Gradle Wrapper（Kotlin DSL） |

版本号由根目录 `version.properties` 单一来源驱动，执行 `assemble` / `bundle` / `deliverApk` 等构建任务时自动 `versionCode + 1`、`versionName` 的 PATCH 位 `+1`，保证 APK 内版本名与成品文件名一致。

## 构建

```bash
git clone https://github.com/startime-ltk/SugarPal-Android.git
cd SugarPal-Android
./gradlew assembleRelease
# 或产出规范名并复制到「成品」目录
./gradlew deliverApkRelease
```

产物：`糖伴SugarPal-<版本>.apk`。Release 发布包命名为 `SugarPal-<版本>.apk`。

## 下载

- [SugarPal-1.2.10.apk](https://github.com/startime-ltk/SugarPal-Android/releases/download/v1.2.10/SugarPal-1.2.10.apk)（4.2 MB）
- SHA256：`8954c3b4354758ff03f99fba4fa902a9e2e57be5425f9e7763ae7821af21fcc5`
- 同一签名证书，新版本**可直接覆盖安装**，无需卸载（仅早期 44 MB 调试版需先卸载一次）。
- **源码比安装包新**：`v1.2.10` 发布后 `main` 上还有 `ae5c678`（日历视图点击血糖记录可补充 / 修改备注）等 3 个提交，未打进 `SugarPal-1.2.10.apk`，将在 v1.2.11 收口。已发布安装包不替换。

## 数据与隐私

- 数据全部存于本机 SQLite，不上传服务器，断网可正常使用。
- 只有主动点击「AI 建议」时才会调用你所配置的模型服务，需自备 API Key。
- 「检查更新」会访问 GitHub Releases 或你配置的更新源，除此之外不联网。

## 源码结构

```
app/src/main/java/com/sugarpal/app/
├── MainActivity.kt               # 主界面与功能入口
├── data/                         # 数据层
│   ├── SugarPalDbHelper.kt       # SQLite 建表（字段与 PC 端 H2 对齐）
│   ├── RecordDao.kt              # 记录增删改查
│   ├── MealDescriber.kt          # 餐别识别
│   ├── AgpCalculator.kt          # AGP 指标计算
│   ├── AgpPdfExporter.kt         # AGP 报告 PDF
│   ├── HealthSummaryService.kt   # 健康摘要
│   ├── SummaryPdfExporter.kt
│   ├── AiConfig.kt / AiSuggestionService.kt
│   ├── AlarmConfig.kt / AlarmService.kt / NotificationHelper.kt
│   ├── CsvImporter.kt / CsvExporter.kt
│   ├── UpdateChecker.kt / UpdateConfig.kt / UpdateDownloader.kt
│   └── TimeFmt.kt
└── ui/                           # 各功能对话框与自定义图表 View
```
