# LiveCut AI

基于多模态理解的直播商品讲解定位、返场管理与审核导出系统。

面向 AI 应用开发与 Java 后端秋招作品，先完成有商品清单的中文单主播录像闭环，再逐步验证视觉策略与长任务恢复。

## 当前进度

- 已完成：PRD、技术路线、分阶段开发任务书、评测规范、初始结果 JSON Schema 与合成示例。
- 尚未实现：Java 业务服务、Python 计算流水线、Vue 工作台和真实模型端到端处理。
- 合成示例用于接口开发，不代表真实视频分析效果；性能与质量数据均待测。

## 设计文档

- [方案总览](./docs/README.md)
- [PRD](./docs/01-PRD.md)
- [技术路线](./docs/02-技术路线.md)
- [AI 开发任务书](./docs/03-AI开发任务书.md)
- [评测与验收](./docs/04-评测与验收.md)
- [结果契约与示例](./contracts/README.md)

## 技术方向

Java 21 / Spring Boot / MySQL 负责业务状态与持久调度；Python / FFmpeg / ASR / 文本与视觉模型负责计算；Vue / TypeScript 负责审核工作台。展示版再增加 RabbitMQ 与 Transactional Outbox。

首个实现目标：真实视频 + 商品清单 → ASR 时间锚点 → 商品讲解区间 JSON → 至少三个可播放切片。

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
