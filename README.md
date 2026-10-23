# LiveCut AI

基于多模态理解的直播商品讲解定位、返场管理与审核导出系统。

面向 AI 应用开发与 Java 后端秋招作品，无需商品清单，自动发现视频中的商品讲解、组织返场片段并支持审核导出。

## 当前进度

- 已完成：无清单结果契约 v2.0、数据/引用校验、开发 CLI、MP4 探测、音频提取、非关键帧准确转码切片。
- 尚未实现：Java 业务服务、Python 计算流水线、Vue 工作台和真实模型端到端处理。
- 合成示例用于接口开发，不代表真实视频分析效果；性能与质量数据均待测。

## 设计文档

- [方案总览](./docs/README.md)
- [当前执行需求：无商品清单](./docs/05-无清单识别需求变更.md)
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

doctor 只报告凭证是否配置，不显示密钥；“已配置”不代表已验证有效。真实素材和模型输出保存在被 Git 忽略的 data 目录。

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
