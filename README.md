# LiveCut AI

基于多模态理解的直播商品讲解定位、返场管理与审核导出系统。

面向 AI 应用开发与 Java 后端秋招作品，无需商品清单，自动发现视频中的商品讲解、组织返场片段并支持审核导出。

## 当前进度

- 已实现：无清单结果契约 v2.0、数据/引用校验、媒体探测、准确切片、真实文件 ASR、文本商品发现、本地 OCR 和可恢复的 Python 分析 CLI。
- Java 已实现视频上传、MySQL 来源注册、Range 播放、持久阶段调度和模型调用次数预算；Python Worker 已用真实 10 分钟录像跑通四阶段。审核工作台接入中，正式审核版本导出待实现。
- 合成示例用于接口开发，不代表真实视频分析效果；性能与质量数据均待测。
- 真实文件 ASR 已接通：公开 10 分钟开发样本生成 97 条时间戳转写；这证明接口链路，不代表转写或商品定位精度已达标。
- 真实识别仍按开发样本逐轮核验；结构合法和单元测试通过均不代表定位准确，详见 [真实验证记录](./docs/06-首轮真实视频验证.md)。

## 设计文档

- [方案总览](./docs/README.md)
- [当前执行需求：无商品清单](./docs/05-无清单识别需求变更.md)
- [Java 视频来源服务与启动](./docs/07-Java视频来源服务.md)
- [持久任务与调用预算协议](./docs/08-持久任务与调用预算.md)
- [Python 计算 Worker 与真实接入记录](./docs/09-Python计算Worker.md)
- [PRD](./docs/01-PRD.md)
- [技术路线](./docs/02-技术路线.md)
- [AI 开发任务书](./docs/03-AI开发任务书.md)
- [评测与验收](./docs/04-评测与验收.md)
- [结果契约与示例](./contracts/README.md)

## 技术方向

Java 21 / Spring Boot / MySQL 负责业务状态与持久调度；Python / FFmpeg / ASR / 文本与视觉模型负责计算；Vue / TypeScript 负责审核工作台。展示版再增加 RabbitMQ 与 Transactional Outbox。

首个实现目标：真实视频 → ASR 时间锚点 → 自动商品发现与区间 JSON → 至少三个可播放切片。模型不接收网页商品跳转点或人工时间标签。

## 开发环境与命令

使用 Python 3.11 和 uv 管理依赖，在仓库根目录执行：

```powershell
uv sync --dev
uv run livecut doctor
uv run livecut validate-result contracts/analysis-result.example.json
uv run livecut validate-dataset fixtures/dataset.example.json --metadata-only
uv run pytest
```

媒体命令（时间单位为原视频毫秒）：

```powershell
uv run livecut probe data/raw/sample.mp4
uv run livecut extract-audio data/raw/sample.mp4 --output data/runs/sample/audio.wav
uv run livecut clip data/raw/sample.mp4 --start-ms 1250 --end-ms 2750 --output data/runs/sample/clip.mp4
```

需要 FFmpeg/ffprobe；可通过 LIVECUT_FFMPEG_PATH、LIVECUT_FFPROBE_PATH 或命令参数指定。首版媒体处理支持已验证的 H.264/AAC MP4，非零或无法确定的音视频起点会明确拒绝，避免静默切错时间。

真实 ASR（后端环境配置 DASHSCOPE_API_KEY，结果留在本地 data）：

```powershell
uv run livecut transcribe data/runs/sample/audio.wav --output-dir data/runs/sample/asr
```

重复同一输入/输出目录会恢复既有 provider task_id 或读取已完成响应；提交结果未知时不会自动重提付费任务。临时上传仅用于 POC，稳定部署需配置持久对象存储。原始响应、转写和检查点不可提交到公开仓库。

无清单商品发现：

```powershell
uv run livecut discover data/runs/sample/asr/transcript.json --duration-ms 600000 --output-dir data/runs/sample --grouping category
```

默认按类别归组，不区分同类的包装颜色。结果为 DRAFT，文本基线仍可能在代词、ASR 错词和商品切换时漏检，须审核。

视频直接分析并生成待审核预览（默认使用 CPU 本地 OCR，不调用视觉模型 API）：

```powershell
uv sync --extra ocr --dev
uv run --extra ocr livecut analyze data/raw/sample.mp4 --output-dir data/runs/sample-ocr --max-calls 12
```

首次 OCR 运行可能下载识别模型。模型输入只有视频转写和采样帧 OCR，不需要商品清单。文本处理分为独立发现类别、分配讲解区间两步；模型只引用语句 ID，程序生成正式时间，并拒绝遗漏、重叠或未知引用。

`--max-calls` 是文本发现、分段及格式修复的合计请求上限，不是人民币费用上限。每 180 秒一个核心窗口，每窗口至少两次请求；10 分钟样本至少八次，12 次为修复留出余量。更长输入需显式提高预算，不能直接套用短样本配置。

可用 `--product 面膜` 筛选自动发现的商品名称/别名。相同输入与配置复用同一输出目录可恢复；改变模型、提示词或证据时使用新目录。`--asr-cache-dir` 可明确复用另一轮已完成 ASR，其音频哈希和模型配置仍须匹配。

输出包括 `analysis-result.json`、`run-manifest.json`、`preview-report.json`、`preview-clips/`，OCR 证据保存在同级的 `<输出目录>-ocr/`。预览不是审核通过的正式成片，结果始终为 DRAFT。空结果、无匹配、预算耗尽和 Provider 失败均明确返回，不会改用假数据。`--visual-provider none` 可关闭 OCR；`api` 是可选视觉接口，本账号免费额度耗尽时会明确失败，不会更改计费设置。

doctor 只报告凭证是否配置，不显示密钥；“已配置”不代表已验证有效。真实素材和模型输出保存在被 Git 忽略的 data 目录。

Java 服务使用仓库内 Maven Wrapper，无需全局安装 Maven；在独立测试库配置完成后运行 `./scripts/java.ps1 -Task Verify`，开发服务运行 `./scripts/java.ps1 -Task Run`。API 及 MySQL 启动步骤见 [Java 服务说明](./docs/07-Java视频来源服务.md)。当前视频状态 `HEADER_CHECKED` 只代表基本上传校验，完整媒体可用性仍需计算 Worker 探测。

## 功能交付规则

每个功能按“实现 → 相关验证 → 检查提交内容 → commit → push”交付，并在提交记录中保持功能边界清楚。功能分支采用 `codex/<feature>`；`main` 保存稳定基线。未完成的实验与 Mock 测试需要明确标识。

本地视频、模型原始响应、运行日志、临时资源和凭证不纳入 Git；小型合成测试数据放入可版本管理的 fixtures 目录。

## 契约校验

在支持 Test-Json 的 PowerShell 中，从仓库根目录运行：

```powershell
Get-Content -LiteralPath './contracts/analysis-result.example.json' -Raw |
  Test-Json -SchemaFile './contracts/analysis-result.schema.json' -ErrorAction Stop
```

这只检查 Schema，业务引用与时间规则见 [契约说明](./contracts/README.md)。
