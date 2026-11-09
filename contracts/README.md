# 视频分析结果契约

## 视频对话画面观察 v1.1

[agent-answer-visual.schema.json](./agent-answer-visual.schema.json) 仅用于显式 frames 模式的 AGENT_VISUAL 轮次，保留旧1.0协议。原句与视觉证据分别引用；VIDEO 至少有一类真实本轮引用。Schema 限定工具参数、帧元数据和引用结构，Java/Python/前端另外核验实际时间、源 PTS、工具与帧集合、连续 ID、范围及来源，Java/Worker 再核实际 JPEG 字节与 manifest。仅凭 JSON Schema 不能证明模型确实看图或解释正确。

`inspect_frames` 最多4个位置，`inspect_clip` 为12秒内每500ms抽帧，非连续视频或音轨理解；每轮最多32个不同帧、4次工具步骤。视觉模型直接执行本轮决策，全部 completion（含重试/修复）共用本轮请求上限；不同帧数量不是图像 token/金额上限。只读截图端点、独立配置与旧提交兼容见[完整协议](../docs/63-Agent按需画面观察.md)。[共享示例](../fixtures/agent-visual.synthetic.json) 是合成协议样例，其哈希不代表真实媒体。

## 视频对话 Agent v1.0

[agent-answer.schema.json](./agent-answer.schema.json) 定义独立会话轮次的回答、原句引用、工具轨迹与来源身份。JSON Schema 检查固定结构；Java/Python 另行核验本轮实际观察、唯一源顺序、原文与毫秒时间、工具连续步骤、允许的来源及主模型。VIDEO 必须有原句引用，GENERAL/INSUFFICIENT 可无引用；只有实际调用 search_transcript 才记录检索模式。剪辑建议为纯文本，不能自动改审核或导出。接口、限制与恢复见[视频对话 Agent](../docs/45-视频对话Agent.md)。

视频对话提交结果可按原 Idempotency-Key 只读查询，返回既有 Session / Turn 形状，不增加 Worker 字段。仅明确的业务不存在允许原 key/body 重试；未决本机 entry 不因改问题、预算或跨页面操作而覆盖。两个查询路由、旧槽迁移和恢复边界见[视频对话提交结果核对](../docs/62-视频对话提交结果核对.md)。

## 普通分析的准备产物复用

`POST /api/jobs/{id}/resume-from-asr` 为已完成 MEDIA/ASR 的 topics 任务建立独立新轮次。公开快照的可选 `resumed_from_job_id` 表示来源；私有 `preparation_reuse` 只随认证 claim 下发，结构见 [preparation-reuse.schema.json](./preparation-reuse.schema.json)。Worker 另行核验源与目标身份、manifest、路径和字节哈希，失败不回退付费 ASR。完整状态、预算与并发约束见[执行说明](../docs/40-普通分析复用转写重新执行.md)。

## 按需求剪辑 v1.0

人工核对批次使用 [clip-manual-review.schema.json](./clip-manual-review.schema.json)。自动 clip-plan 1.0 不变，NEEDS_REVIEW 始终 enabled=false；独立人工集合绑定实际方案、来源、r0 及新修订，确认与导出额外绑定 review_hash。普通保存不继承核对，摘要不授人工权限，Worker 私有 claim 不增加字段。输入幂等、丢回执核对及消费端失效约束见 [人工选用协议](../docs/61-待核对候选人工选用.md)。Schema 检查结构，Java/前端另查整批顺序、UTF-16、摘要和来源。

只读分页历史使用 [clip-request-history.schema.json](./clip-request-history.schema.json)，独立于完整请求快照。摘要仅供浏览与刷新，不包含 ASR、方案、回答引用，也不能作为编辑/确认凭证；同任务 keyset 游标、微秒排序及兼容边界见 [轻量分页历史](../docs/60-轻量分页剪辑历史.md)。

回答引用联动剪辑可在创建请求时携带 `source_answer:{turn_id,utterance_ids}`。Java 冻结的公开 `source_answer` 与私有 claim 的 `clip_request.answer_source` 使用 [answer-source.schema.json](./answer-source.schema.json)，旧普通请求为 null。冻结对象只接受同任务成功 VIDEO 回答的原引用，时间由原 ASR 映射；JSON Schema 之外另查 UTF-16 总长、摘要、顺序及完整原句。锚定使用专用 ANCHOR_CLIP/TEXT，`clip_plan` 仍为 v1.0、prompt_version 为 anchor-clips-v1，单目标候选完整保留全部锚句。普通请求仍用 intent-clips-v1 与 SEGMENT。恢复、人工修订及验收边界见 [回答原句联动剪辑](../docs/58-回答原句联动剪辑.md)。

独立请求方案使用 [clip-plan.schema.json](./clip-plan.schema.json)，不覆盖原话题预测与审核修订。每个目标一个输出，包含有序的多个源区间、证据和排除原句。JSON Schema 检查结构；Java 与 Python 另行核验来源哈希、目标完整覆盖、语句存在与顺序、时间映射、区间不重叠和排除证据互斥。自动方案所有输出初始不勾选，只有 FOUND 可人工启用；确认版本才能导出。接口及边界见 [按需求检索与剪辑方案](../docs/37-按需求检索与剪辑方案.md)。

请求快照可包含 `resumed_from_request_id`，仅表示继续操作创建的新请求与旧请求的关系，不反写剪辑方案的 request_id。取消和继续的状态、幂等、来源核验及计费边界见[协议](../docs/39-剪辑请求取消与继续.md)。旧快照缺少该字段仍合法。

## 执行器状态与领取协议

执行器注册、独立心跳、停机、带会话的领取及公开容量快照见 [执行器感知与资源调度](../docs/36-执行器感知与资源调度.md)。该协议补充执行控制，不修改历史分析结果、人工修订或导出契约。

## 话题解析 v1.0

新任务使用 `analysis_mode=topics` 和独立的 [topic-analysis-result.schema.json](./topic-analysis-result.schema.json)。每段包含标题、摘要、关键词、首末语句 ID、完整语音证据及原视频毫秒边界；不生成商品字段。程序要求核心语句完整、连续、按源顺序分配，时间由首末源语句映射。

话题审核使用 `/api/jobs/{id}/topic-review`。初始段落均未勾选，人工修改写入独立修订；确认后才允许导出。人工时间不要求仍落在自动预测语句边界，但必须在源视频范围内，所选段落不可重叠。完整行为见 [当前需求与验收](../docs/32-通用视频话题解析.md)。

## 历史无清单商品分析 v2.0

输入不要求商品清单或 SKU。products 是从视频中发现的商品类别/可辨识款式，product_id 仅在本次任务有效。identity_granularity=category 不能解释为外部 SKU 已核验。

analysis-result.example.json 完全合成，不对应真实录像、真实模型结果或实际帧文件。第一、第三段属于同一产品并保留两个区间；第二段身份模糊。

review_status 固定 DRAFT。review_required 仅代表审核优先级；false 仍需要用户确认后才能正式导出。人工审核快照另存，不反写自动预测。

livecut validate-result 同时运行 JSON Schema 与业务校验：

- 全局整数毫秒和半开区间；0 <= start < end <= duration，帧时间小于 duration。
- ID 唯一，语句、帧、产品引用存在；identified 的 product_id 在候选及 products 中。
- 自动边界引用 ASR 语句并映射；证据语句有序且在片段内。
- ambiguous/unknown、混合内容、音画冲突有相应审核原因。
- supported/conflict 有清晰视觉观察；未视觉校验不伪造一致性。
- 正式讲解区间不得非法交叠；明确标记的未决候选可以保留。
- 空 products/segments 合法，表示没有发现可定位商品，不代表测试成功。

帧可处于 manifest 许可的边界采样范围，校验器不会凭空固定窗口。结构校验不证明模型识别正确或媒体存在。

运行（仓库根目录）：

```powershell
uv sync --dev
uv run livecut validate-result contracts/analysis-result.example.json
uv run livecut validate-dataset fixtures/dataset.example.json --metadata-only
uv run pytest worker-python/tests/test_contracts.py worker-python/tests/test_inputs.py
```

数据集示例的媒体路径为占位。去掉 --metadata-only 后会检查本地文件，不能把元数据模板当真实素材验收。

## 独立定位参考

`caption-review.schema.json` 定义独立成片字幕修订：绑定导出/成片/转写 hash 和冻结 parts，时间为成片相对毫秒；不可变 partial 标记与明确 reviewed 状态推导 requires_review。保存只接收 id/start_ms/end_ms/text/reviewed，确认后才能交付。格式、下载和素材包协议见 [字幕校对与素材包](../docs/47-字幕校对与素材包.md)。该字幕为句级源 EDL 映射，不代表词级或实测接缝 PTS。

`localization-annotation.schema.json` 定义无 SKU 的离线参考标注，要求实际源 SHA256、时长、原场次、明确的标注范围和来源声明。`fixtures/localization-annotation.synthetic.json` 仅用于合成手算验证；旧 `annotation.example.json` 的空模板仍不代表背景。

`livecut evaluate-localization` 对未经人工修改的 DRAFT 预测做来源绑定及一对一定位评测；没有参考时使用 `livecut audit-localization`，不会输出质量分数。完整命令和部分标注口径见 [离线定位评测](../docs/23-离线定位评测与证据审计.md)。

## 确认字幕烧录输入 v1

[caption-render-spec.schema.json](./caption-render-spec.schema.json) 定义独立派生 EXPORT_CLIP 的小型冻结输入，包含已验证 clean MP4、确认字幕修订与 cues 文件、固定字体、样式和输出几何。canonical hash 绑定 claim、checkpoint 与新 captioned_export manifest；旧 reviewed_export 分支继续保持。本文 Schema 检查结构，三端另核验来源/相对 key/真实字节哈希、时序、非重叠、允许的固定参数与租约。API、进度及验收见 [带字幕成片](../docs/49-带字幕成片导出.md)。

字幕渲染专用 [cues 文件](./caption-render-cues.schema.json) 与 [公共快照](./caption-render.schema.json) 分别约束纯文本输入和独立状态；示例 fixtures/caption-render.example.json 完全合成。UTF-16/UTF-8字节限制、半开区间非重叠、URL与本记录ID绑定及进度不回退仍需业务校验。

`PORTRAIT_BLUR_720` 为固定 720×1280 的[竖屏柔化背景](../docs/50-竖屏柔化背景.md)扩展；新枚举进入原有冻结哈希，旧普通竖屏保持补黑。新旧 Worker 需要协调发布。
