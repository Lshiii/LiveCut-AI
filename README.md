# LiveCut AI

基于多模态理解的直播商品讲解定位、返场管理与审核导出系统。

面向 AI 应用开发与 Java 后端秋招作品，无需商品清单，自动发现视频中的商品讲解、组织返场片段并支持审核导出。

## 当前进度

- 已实现：无清单结果契约 v2.0、数据/引用校验、媒体探测、准确切片、真实文件 ASR、文本商品发现、本地 OCR 和可恢复的 Python 分析 CLI。
- Java 已实现视频上传、MySQL 来源注册、Range 播放、持久阶段调度和模型调用次数预算；Python Worker 已用真实 10 分钟录像跑通四阶段。Vue 工作台支持任务管理、时间轴定位、语音证据、商品与边界编辑、并发修订及确认，以及按已确认版本创建正式 MP4 导出、预览和下载。
- Java 统一返回任务可用的取消/重试操作及执行次数上限；页面在次数耗尽时显示原因，迟到快照不会回退同任务的状态。实际操作仍在事务内重新校验，重试不增加模型请求预算。
- 支持下载指定确认版本的审核 JSON 和导出批次文件清单；清单记录逐项状态、大小与 SHA256，成功文件在生成清单前重新核验。
- Worker 支持指定父任务领取，并显式复用同视频已验证的 ASR 转写；音频、模型、manifest 和转写内容不匹配时明确失败，不自动重新提交付费 ASR。见 [复用协议](./docs/16-ASR转写复用.md)。
- 工作台支持持久分片上传与暂停续传：完整/分片 SHA256 校验、幂等回执、暂存配额和合并租约恢复。约 160 MB 真实录像通过页面刷新与 Java 重启后仅补传缺片的验收，合并中终止进程后也恢复至同一预留视频；见 [上传协议与验收](./docs/19-持久分片上传.md)。
- 当前分析任务和导出批次支持 SSE 完整状态更新、退避重连与 HTTP 刷新兜底，按资源/版本隔离迟到响应；真实浏览器已验证 Java 重启、SSE 单独阻断、重试恢复和实际成片状态推送。服务器从 MySQL 补当前状态，不承诺历史事件重放；见 [SSE 协议与验收](./docs/20-SSE任务快照与恢复.md)。
- Worker 支持跨执行目录复用本地 OCR 证据：绑定视频、采样配置、实际模型和 FFmpeg 摘要，校验完整后复制到本次执行目录。真实 30 分钟素材及正常 Worker 重试均验证命中时不再抽帧或初始化 OCR；见 [OCR 证据复用与验收](./docs/21-OCR证据复用.md)。
- 阶段和导出条目支持持久失败诊断：展示受控原因、失败窗口、错误计数与恢复建议，重试/领取/成功时清除旧诊断；验证过真实零预算 Worker、服务重启、过期回调拒写和导出重试后生成真实成片。详情见 [失败诊断与恢复建议](./docs/22-失败诊断与恢复建议.md)。
- 新增离线定位评测与证据审计：源文件/预测/标注绑定，精确一对一 Temporal IoU、误检漏检和边界报告；未标注数据禁止评分。真实 30 分钟结果已完成未评分采样审计，未产生新的识别准确率结论；见 [评测工具与验收](./docs/23-离线定位评测与证据审计.md)。
- 合成示例用于接口开发，不代表真实视频分析效果；性能与质量数据均待测。
- 真实文件 ASR 已接通：公开 10 分钟开发样本生成 97 条时间戳转写；这证明接口链路，不代表转写或商品定位精度已达标。
- 真实识别仍按开发样本逐轮核验；结构合法和单元测试通过均不代表定位准确，详见 [真实验证记录](./docs/06-首轮真实视频验证.md)。
- 真实 30 分钟开发片段已完成十窗分析、285 条语句完整覆盖、开发审核和乳液/精华液/面膜三段正式导出，浏览器下载及 Java 重启持久化核验通过。结果仍有类别偏碎和 OCR 后半场缺覆盖等限制；不是识别精度结论。完整记录见 [30 分钟验收](./docs/17-30分钟完整验收.md)，旧失败记录继续保留。

## 设计文档

- [方案总览](./docs/README.md)
- [当前执行需求：无商品清单](./docs/05-无清单识别需求变更.md)
- [Java 视频来源服务与启动](./docs/07-Java视频来源服务.md)
- [持久任务与调用预算协议](./docs/08-持久任务与调用预算.md)
- [Python 计算 Worker 与真实接入记录](./docs/09-Python计算Worker.md)
- [人工审核 API 与修订协议](./docs/10-review-api.md)
- [Vue 工作台与浏览器验证](./docs/11-审核工作台.md)
- [正式导出、版本冻结与下载](./docs/12-正式导出与下载.md)
- [MVP 本机启动、环境模板与集成验收](./docs/13-MVP集成与验收.md)
- [逐句分类协议与长样本恢复边界](./docs/14-逐句分类协议.md)
- [确认结果与文件清单下载](./docs/15-确认结果与文件清单下载.md)
- [显式 ASR 转写复用与目标任务 Worker](./docs/16-ASR转写复用.md)
- [30 分钟真实开发素材完整流程验收](./docs/17-30分钟完整验收.md)
- [参考项目、差异化与分片上传路线](./docs/18-参考项目与分片上传路线.md)
- [持久分片上传、容量保护与恢复验收](./docs/19-持久分片上传.md)
- [SSE 任务快照、断线恢复与资源控制](./docs/20-SSE任务快照与恢复.md)
- [OCR 证据缓存、完整性与重试复用](./docs/21-OCR证据复用.md)
- [持久失败诊断、受控摘要与恢复验收](./docs/22-失败诊断与恢复建议.md)
- [离线定位评测、标注契约与采样审计](./docs/23-离线定位评测与证据审计.md)
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

首次 OCR 运行可能下载识别模型。模型输入只有视频转写和采样帧 OCR，不需要商品清单。文本处理分为独立发现类别、按源 ASR ID 逐句分类两步；分类对象的键必须精确对应核心语句，每项只返回内容类型和候选标签，身份按合法候选集合推导。程序按原始顺序读取并生成正式时间；缺项、未知或重复键、非法类型/标签均失败，并同时反馈已知核心项的问题。格式合法仍不代表商品判断正确，见 [协议说明](./docs/14-逐句分类协议.md)。

单独准备 OCR 证据、不提交 ASR 或文本模型请求，可运行 `uv run --extra ocr livecut ocr data/raw/sample.mp4 --output-dir data/runs/ocr-first --cache-root storage/cache/ocr-v1`；换输出目录会校验并复用匹配缓存。`analyze` 可加 `--ocr-cache-root storage/cache/ocr-v1`，Worker 默认使用该共享目录。默认仍每 45 秒采样、最多 16 帧，长录像后半场覆盖问题尚未优化。

`--max-calls` 是文本发现、分段及格式修复的合计请求上限，不是人民币费用上限。每 180 秒一个核心窗口，每窗口至少两次请求；10 分钟样本至少八次，12 次为修复留出余量。更长输入需显式提高预算，不能直接套用短样本配置。

可用 `--product 面膜` 筛选自动发现的商品名称/别名。相同输入与配置复用同一输出目录可恢复；改变模型、提示词或证据时使用新目录。`--asr-cache-dir` 可明确复用另一轮已完成 ASR，其音频哈希和模型配置仍须匹配。

输出包括 `analysis-result.json`、`run-manifest.json`、`preview-report.json`、`preview-clips/`，OCR 证据保存在同级的 `<输出目录>-ocr/`。预览不是审核通过的正式成片，结果始终为 DRAFT。空结果、无匹配、预算耗尽和 Provider 失败均明确返回，不会改用假数据。`--visual-provider none` 可关闭 OCR；`api` 是可选视觉接口，本账号免费额度耗尽时会明确失败，不会更改计费设置。

doctor 只报告凭证是否配置，不显示密钥；“已配置”不代表已验证有效。真实素材和模型输出保存在被 Git 忽略的 data 目录。

Java 服务使用仓库内 Maven Wrapper，无需全局安装 Maven；在独立测试库配置完成后运行 `./scripts/java.ps1 -Task Verify`，开发服务运行 `./scripts/java.ps1 -Task Run`。API 及 MySQL 启动步骤见 [Java 服务说明](./docs/07-Java视频来源服务.md)。当前视频状态 `HEADER_CHECKED` 只代表基本上传校验，完整媒体可用性仍需计算 Worker 探测。

工作台在 frontend 目录执行 `npm ci` 和 `npm run dev`，打开 `http://127.0.0.1:5173`。Java API 使用 8080 端口，Worker 在另一终端执行 `./scripts/worker.ps1`。模型密钥仅由 Worker 读取，前端不保存凭证。完整本机操作见 [工作台说明](./docs/11-审核工作台.md)。

首次配置与重启步骤见 [本机集成说明](./docs/13-MVP集成与验收.md)。仓库提供 `.env.java.example`、`.env.java-test.example` 与 `.env.mysql-local.example`；已有私有配置应保留。Docker 不可用的 Windows 环境可用 `./scripts/mysql-local.ps1 -Task Status` 检查已准备好的本项目独立 MySQL 实例，按需改用 Start 或 Stop；该辅助脚本不会初始化或强杀数据库，真实隔离启停检查 15 项通过。

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
