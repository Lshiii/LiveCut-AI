# 显式 ASR 转写复用与目标任务 Worker

2026-10-25。新分析任务可以显式复用同一视频已成功发布的 ASR 转写，便于继续验证[商品分类协议](./14-逐句分类协议.md)。该能力默认关闭；本次已完成代码、联合回归和真实 30 分钟素材的跨任务复用验证。长视频后续商品分段仍需独立验收。

Java 继续独占业务状态、领取、租约和预算。复用只改变目标任务 ASR 阶段的计算来源，仍通过正常 manifest 和 complete 回调提交目标任务自己的产物。基础流程见[持久任务与调用预算](./08-持久任务与调用预算.md)和[Python 计算 Worker](./09-Python计算Worker.md)。

## 参数与运行方式

| CLI 参数 | PowerShell 参数 | 行为 |
|---|---|---|
| `--job-id TARGET` | `-JobId TARGET` | 只领取指定父分析任务的阶段；也适用于该分析任务的 EXPORT_CLIP |
| `--reuse-asr-job SOURCE` | `-ReuseAsrJob SOURCE` | 从指定源任务读取并验证已成功的 ASR；默认不启用 |
| `--stage-type ASR` | `-StageType ASR` | 本次只处理 ASR |
| `--once` | `-Once` | 最多处理一次阶段执行或一次待补回调 |

`--job-id` 可独立用于任意阶段。使用 `--reuse-asr-job` 时，必须同时提供 `--job-id`、`--stage-type ASR` 和 `--once`，否则在创建 Java client 前返回 `INVALID_ASR_REUSE_CONFIGURATION`。任务 ID 使用 UUID；客户端将合法的大写 UUID 规范化为小写。

先通过正常任务 API 创建目标分析，再用真实 FFmpeg 完成目标 MEDIA 阶段。下例中的两个 UUID 均需替换，命令为操作示例，尚不是本次真实复用的执行记录：

```powershell
$targetJob = '<目标分析任务UUID>'
$sourceJob = '<源分析任务UUID>'

./scripts/worker.ps1 -WorkerId asr-reuse-demo -JobId $targetJob -StageType MEDIA -Once
./scripts/worker.ps1 -WorkerId asr-reuse-demo -JobId $targetJob -StageType ASR -ReuseAsrJob $sourceJob -Once
```

脚本沿用私有 `.env.java` 中的 Worker 凭证和共享存储配置，每个 Worker ID 使用独立 journal。执行时应避免其他持续 Worker 同时领取目标任务，否则目标 ASR 可能已被其他实例正常领取。`--once` 遇到已有待补回调会优先重放该回调，本次调用可能没有执行新 ASR；应结合 Java 阶段状态和命令返回状态判断。

直接 CLI 的对应形式：

```powershell
uv run --extra ocr livecut worker --java-url http://127.0.0.1:8080 --worker-id asr-reuse-demo --storage-root C:/LiveCutData --journal-dir data/worker-journals/asr-reuse-demo --job-id $targetJob --stage-type ASR --reuse-asr-job $sourceJob --once
```

## 复用前提与校验链路

源任务与目标任务必须不同，但 `video_id` 和冻结的 `asr_model` 必须相同。源任务整体可以因后续 SEGMENT 失败而处于 FAILED；要求其唯一 MEDIA 和唯一 ASR 阶段均为 SUCCEEDED。当前只支持既有单分片 ASR：`source_chunk_id=c_001`、`chunk_start_ms=0`、`adapter_version=1`。

Worker 在创建任何 Provider transport 或预留模型调用额度前依次完成以下检查：

1. 通过正常 `GET /api/jobs/{SOURCE}` 读取源任务公开快照，核对返回任务 ID。快照不暴露执行 token；Worker 根据源 stage 的 attempt 和公开 `manifest_hash`，在共享存储的该 attempt 下寻找唯一匹配 SHA256 的 manifest。
2. 检查 MEDIA、ASR manifest 的 schema、源 job/video/generation、stage/type/attempt、目录 token、输入视频 SHA256，以及 `model_result`、`DRAFT` 身份。generation 使用源任务值，不要求与目标 generation 相同。
3. 验证两份 manifest 的全部 artifact 引用：kind/key 不重复、路径位于各自产生结果的 attempt 内、文件存在且 SHA256 一致。拒绝路径穿越、符号链接和 Windows junction/reparse point；MEDIA 必须含 audio、media_info，ASR 必须含 transcript。
4. 比较目标任务已验证 MEDIA 音频与源 MEDIA 音频的 SHA256。仅相同视频 ID 或相同模型名不足以复用。
5. 只读源 ASR 的 `asr/checkpoint.json`，要求 version 1、SUCCEEDED 和有效 task ID，完整比较音频 SHA、model、有效 base URL、chunk ID、offset、adapter version。整数采用严格类型校验，不将 JSON 布尔值当作 0 或 1。base URL 使用与正常 Worker 相同的解析路径，`LIVECUT_ASR_BASE_URL` 优先于 DASHSCOPE fallback。
6. 只读 `asr/raw-result.json`，使用当前 `map_transcript` 重建转写，并与已通过 manifest 哈希验证的 normalized transcript 作完整 JSON 内容比较。再次验证读入的 transcript 原始字节 SHA，避免验证后重新读取另一份内容。
7. 校验租约与目标输出路径，把已读取、已验证的 transcript 字节原样原子写入目标 attempt。生成额外的 `cache_provenance` artifact，随后沿用正常目标 manifest、artifact 哈希和 complete 协议。

JSON 拒绝重复键和非有限数字，并限制 manifest 为 1 MiB、checkpoint 为 4 MiB、raw 为 16 MiB、transcript 为 8 MiB。音频等 artifact 使用流式哈希。当前实现会保守拒绝同一源 attempt 下损坏的候选 manifest JSON，即使该候选最终不会匹配公开哈希。

目标 attempt 的 manifest 使用目标 job、generation、stage、attempt 和 token，原始 source 文件只读。复用不会调用 `transcribe_file` 去恢复或改写源缓存，也不会复制源 raw/checkpoint 到目标目录。由此生成的目标转写不能仅凭 transcript 自动成为下一次复用源；缺少所要求的 raw/checkpoint 会被拒绝。

## 产物、计费与信任边界

目标 ASR 阶段增加两项产物：

| kind | 当前 attempt 内文件 | 内容 |
|---|---|---|
| `transcript` | `asr/transcript.json` | 已验证源转写的原始字节 |
| `cache_provenance` | `asr/cache-provenance.json` | 源 job/video/generation、MEDIA/ASR stage ID、两个源 manifest 哈希、转写与音频哈希、模型、adapter version、当前 raw/checkpoint 审计哈希及 `provider_submissions=0` |

provenance 使用 `reuse_mode=published_transcript_equivalence`。其中不保存本地路径、存储 key、执行 token、临时 URL、Provider task ID 或凭证。Java ArtifactVerifier 允许这个额外 kind，仍验证目标 manifest 和两个目标 artifact；没有绕过正常结果校验。

**历史源 ASR manifest 只锚定 normalized transcript，并未把原 raw-result/checkpoint 全部字节列为受保护 artifact。** 当前 raw/checkpoint 的 SHA 记录本次实际读取内容；它们与当前 mapper 共同证明可以重建出已发布转写。这不等于证明历史 raw/checkpoint 的所有字段从未被修改，未参与转写映射的原始元数据不能据此取得历史完整性保证。

成功复用不会新建 ASR Provider transport、发送 ASR 请求、预留 ASR 请求额度，亦不会伪造新的 Provider 调用账本或提交确认。源任务已发生的调用继续保留在源账本；目标的其他阶段仍正常使用各自预算。这里的“零新增 ASR 请求”已核对本次真实任务账本和网络观测，不是人民币账单或识别效果结论。

任何条件不匹配都会明确失败，**不会自动回退付费 ASR**。常见错误包括 `ASR_REUSE_SOURCE_NOT_SUCCEEDED`、`ASR_REUSE_MODEL_MISMATCH`、`ASR_REUSE_MANIFEST_MISMATCH`、`ASR_REUSE_ARTIFACT_MISMATCH`、`ASR_REUSE_AUDIO_MISMATCH`、`ASR_REUSE_CHECKPOINT_MISMATCH`、`ASR_REUSE_TRANSCRIPT_MISMATCH` 和 `ASR_REUSE_UNSAFE_PATH`。Worker 仍通过正常 fail 回调报告安全错误码，Java 决定任务状态。

## 指定目标任务的领取与恢复协议

`GET /internal/stages/ready` 新增可选 `job_id` 查询参数；`POST /internal/stages/claim` 新增可选 `job_id` JSON 字段。两者指父 analysis_job ID，即使领取的是其导出阶段也如此。未传该字段时保留原协议行为。

Java 在 claim 的父任务锁内、生成领取结果和增加 attempt 前，重新核对实际 stage 归属。请求目标不匹配返回 HTTP 409 / `CLAIM_TARGET_CHANGED`，不生成领取 receipt，也不改变 stage attempt。ready 的筛选只是减少候选，不能替代 claim 的锁内检查。Python 同时核对 ready item 和 claim 回包的 job ID、原有 stage ID 与 stage type。

目标模式的本地 `pending-claim.json`、`worker-result.json`、`pending-callback.json` 都增加顶层 `job_id`：

- pending claim/callback 缺少 job ID 或属于其他 job 时，在发送任何 HTTP 前以 `WORKER_JOB_TARGET_MISMATCH` 拒绝。
- worker-result 位于 attempt 目录，只有取得目标 claim 回包后才知道本次目录，因此在 ready/claim 之后检查；不匹配时仍在任何 callback 或 Provider 调用前拒绝。
- 未设置目标的旧模式继续兼容缺少 job ID 的原 journal。目标模式不会自动把旧 journal 标记成目标任务；需要独立 Worker ID/journal，或先在原运行模式完成原任务的回调恢复。

这里的顶层 job ID 检查防止误用旧 journal 推进其他任务，**仍信任本地 journal 的其余 payload**，不声称能抵御恶意改写本地 journal。共享卷、公开任务快照来源、当前执行程序及本地 journal 都属于部署信任边界。已有的固定 claim_request_id、回调原样重放、attempt/token 隔离和 Java 文件检查继续生效。

## 当前验证记录

2026-10-25，联合工作树 Python 回归 **381 项及 98 子测试通过，耗时 16.08 秒，Ruff 通过**；Java 统一集成回归 **96 项通过，零失败、错误、跳过**。Python 数量包含同时开发的逐句分类协议改动，不能全部归为本功能新增测试。

功能提交 `10799d4` 的 GitHub CI 独立验证为 Python **365 项及 98 子测试**、Java **96 项无跳过**，PowerShell 语法通过；后续包含 v11 的 `5bfe688` 完整 Python CI 为 **381 项及 98 子测试**。本地与 CI 数量对应各自代码版本。

新增合成文件测试使用真实计算的 manifest/artifact SHA，覆盖成功原字节复制、空转写、零 Provider transport、源文件 SHA 保持、模型/音频/阶段状态/manifest 身份与内容篡改、额外 artifact 校验、checkpoint 各输入字段与严格整数、raw 语义不一致、重复 JSON 键、链接/junction 越界、重复匹配 manifest，以及 endpoint 优先级。目标协议测试覆盖 API 参数、错误任务回包、旧/异任务 journal 拒绝、领取/完成响应丢失恢复、CLI 参数限制和源快照读取中断。Java 集成检查覆盖目标 ready 筛选及错误目标 claim 的无副作用拒绝。

真实验证通过正常 POST 创建 generation 3 任务 `2e32acf5-ee52-4fc3-a8ce-524207456edd`，同一 1800.017 秒视频先完成自己的 MEDIA，再从 generation 2 任务 `7e2c6b9a-0885-4fb7-9d06-c0b33c9a0098` 显式复用 ASR。目标 ASR attempt 1 为 SUCCEEDED，CLI 退出码 0，执行耗时 0.735 秒（不含前后只读审计）；285 条转写原字节 SHA256 为 `473a3b630edc4eecee5f807d625faa81cf633eca6a862edcbacb95c9e48804fb`，与源 manifest 锚定的转写一致。目标 manifest、transcript 和 cache_provenance 均通过 Java 与独立磁盘哈希核对。

本次目标 ASR 预算为 0/1，文本为 0/30，model_call 表记录数为零；验证进程禁止所有非 loopback HTTPTransport 请求，拒绝记录数也为零。升级开发 API 前后的原三份任务快照逐字段一致；两个旧长样本任务在新 MEDIA/ASR 完成后仍为 FAILED，原预算 16/24 与 17/24、各自 ASR 1/1 不变。源 MEDIA/ASR 目录 14 个文件逐项 SHA 保持不变。

私有观测脚本首次分别在 CLI 完成后遇到 Windows 扩展路径比较、把转写数组当对象读取的错误，均只修复观测并重新执行只读核对，没有重新执行阶段。MEDIA 的精确计算耗时未可靠保存，不补造数值；ASR 子进程结果已在观测之前保存。证据留在忽略目录 `data/runs/mvp-30min-v11/`（asr-timing、两阶段 manifest-verification、source-files-before、原任务快照及只读账本）。这些检查不能证明商品分段、成片或定位质量已经通过。
