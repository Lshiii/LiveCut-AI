# 全片 OCR 采样与旧任务兼容

2026-10-26。新任务在相同帧数上限内，把本地 OCR 证据分布到整段视频。保留前段采样对照，不把采样时间分布改进解释成识别准确率提升。

## 持久配置

Java 新建任务新增 `ocr_sampling`，支持 `uniform` / `prefix`，默认 `uniform`，与其他分析配置一起写入 config_json / config_hash / 阶段 input_hash。重试、领取和服务重启沿用该任务的冻结配置，不按当前默认值重新解释历史任务。

历史任务缺字段时，Worker 明确按 `prefix` 执行；Java 读取不补字段、不改哈希。页面分别展示“整段视频均匀取样”和“前段固定间隔取样 · 旧策略”；语音模式仍只使用转写。

部署时同步更新 Java 和 Worker；当前本地展示版不提供混合旧版 Worker 的能力协商。

创建幂等也保持兼容：只有请求没显式给出策略、旧记录也确实缺字段时，才允许用移除新增字段后的原请求摘要匹配旧回执。匹配后原样返回旧任务；不同视频、预算或显式策略仍冲突。正常查回和 DuplicateKey 事务回滚后查回使用同一规则，不重建旧任务。

## 采样计划

- `prefix` 保留原规则：从 0 开始按 interval_ms 取样，到 max_frames 截止。
- `uniform` 的样本数为 `min(max_frames, ceil(duration_ms / interval_ms), duration_ms)`。默认间隔 45 秒、最多 16 帧；此处间隔用于确定样本数，长视频的相邻采样点不再固定为 45 秒。
- 多于一帧时，按等宽时间桶中心计算整数毫秒目标：`floor((2*i+1)*duration_ms/(2*N))`。仅一帧时取 0，避免请求极短单帧片尾。
- 目标时间仅决定请求位置；保存并传给后续语义分析的仍是 FFmpeg 实际源 PTS / time_base 对应的时间，不用目标时间伪造实际帧位置。没有合法帧时明确失败，不填补假证据。

均匀采样仍然稀疏，短暂展示可能落在两帧之间。它是固定预算基线，还不是风险触发的自适应加密。`coverage_truncated=false` 只表示该计划没有被前段预算截断，不表示整片每个时刻都经过视觉核验。

## 缓存、检查点与命令

Python 底层函数默认仍为 prefix，保持旧调用方兼容；新 Java 任务通过冻结配置显式选择 uniform，CLI 的本地 OCR 默认 uniform。uniform 使用独立的采样/观察版本、状态输入和 provenance 标记，缓存身份同时绑定真实源、模型和 FFmpeg；不会命中或覆盖 prefix 包。prefix 的旧状态形状、cache key 和 manifest 摘要保持不变。

同目录恢复检查配置；uniform 还校验采样目标、真实时间的严格整数、范围、PTS 映射及帧摘要。不允许在同一输出目录中静默更换策略。

```powershell
# 新默认：整片均匀采样，只做本地 OCR
uv run python -m livecut ocr data/raw/sample.mp4 --output-dir data/runs/ocr-uniform --cache-root storage/cache/ocr-v1

# 对照或恢复原前段检查点
uv run python -m livecut ocr data/raw/sample.mp4 --output-dir data/runs/ocr-prefix --cache-root storage/cache/ocr-v1 --ocr-sampling prefix

# 完整分析时同样可以显式选择，仍受原文本模型请求上限约束
uv run python -m livecut analyze data/raw/sample.mp4 --output-dir data/runs/analyze-uniform --ocr-cache-root storage/cache/ocr-v1 --max-calls 30 --ocr-sampling uniform
```

OCR 命令输出 `sampling_audit`，包含真实时间点、最大空档和 180 秒时间桶数量；不输出定位质量分数。复用离线审计的同一统计函数。`analyze --visual-provider api` 保留原视觉调用行为，不把本地采样优化转移到付费视觉；显式请求 api + uniform 会在处理前拒绝。语音模式不执行 OCR。

## 真实素材验收

在同一 1,800,017 ms 公开开发录像上，使用相同 CPU OCR、模型权重、FFmpeg 和 16 帧上限：

| 真实时间分布 | prefix | uniform |
|---|---:|---:|
| 帧数 | 16 | 16 |
| 首帧 ms | 0 | 56,257 |
| 末帧 ms | 675,009 | 1,743,777 |
| 含首尾最大空档 ms | 1,125,008 | 112,513 |
| 完整 180 秒无观察时间桶 | 6 | 0 |

两者均有最后 17 ms 的短尾桶，单独显示而不算完整窗口。此表只比较证据的时间分布，不证明商品定位、OCR 文本或剪辑边界更准确。

旧 prefix 直接命中既有缓存：原 key、manifest 摘要和 16 条观察不变，未重新初始化引擎或抽帧。uniform 首次真实抽帧/识别各 16 次；新进程再次读取时均为 0，证据 JSON 字节一致，全部网络连接禁止。首轮模型权重已经准备，本次不验证联网下载。

Java 默认配置创建独立验收任务 `5533ba7a-973c-4899-8fac-4d8692658972`：MEDIA 正常处理、ASR 严格复用已发布转写、SEGMENT 读取 uniform 缓存后因预设零预算返回 BUDGET_EXHAUSTED。页面重试后 attempt 2 保留 uniform 并再次命中；重启 Java 后公开响应字节一致。没有外部模型调用或预算预留，这不是一次新增的完整语义分析。

浏览器分别核验旧任务、新任务和新建说明；页面另创建一个独立任务，实际 API 配置保存为 uniform，未启动 Worker 即取消。两项验收任务最终均取消收尾。旧创建回执真实重放返回同一任务、响应字节一致，显式变更策略或预算被拒绝。

## 回归与保留数据

Java 160 项 HTTP/MySQL/单元测试通过；本机完整测试阶段后因开发进程占用 JAR 导致打包失败，停止该 API 后独立打包成功并恢复服务。Python 699 项及 98 个子测试、ruff、前端 36 项及构建均通过，无测试用例跳过。包括采样整数属性、真实与目标时间分离、缓存策略隔离、旧 key 精确兼容、非法检查点、旧幂等回执及其竞争恢复路径。

部署前后对原有 29 个公开资源响应、557 个源/执行/原缓存文件核对，字节或摘要全部不变。详细证明位于忽略目录 `data/runs/full-video-sampling/`，真实帧、OCR 文本和私有执行目录不提交到公开仓库。
