# 数据契约说明

`analysis-result.schema.json` 定义初始自动预测包，`analysis-result.example.json` 是完全合成的接口示例，不对应真实录像、真实模型结果或实际帧文件。

示例中 s_001 和 s_003 属于同一已确认 SKU，但保留为两个时间区间；s_002 展示身份模糊和视觉不足，不自动合并。risk_score 是虚构的规则排序分，不是概率。

根节点 review_status 固定为 DRAFT。review_required 只表示是否优先审核；false 仍需要用户确认后才能导出。人工编辑与 APPROVED 快照由单独的 timeline_revision 保存，不能反写初始自动预测。

统一校验分两层：

1. JSON Schema 检查字段类型、必填项、状态枚举和基本条件。
2. 业务校验检查时间和引用关系。至少包含以下规则：

   - 所有 start_ms < end_ms <= duration_ms；帧满足 0 <= timestamp_ms < duration_ms。
   - 各类 ID 唯一；segment 引用的语句、帧、SKU 必须存在。
   - matched 的 sku_id 必须在 candidate_sku_ids 及目录内；其余状态 sku_id 必须为空。
   - ambiguous/unlisted、mixed/uncertain、音画冲突应标优先审核并给对应原因；non_product 使用 not_applicable 身份。
   - supported/conflict 必须有足够清晰且可追溯的视觉证据；not_checked 没有观察记录。
   - 机器初始边界由 start_utterance.start_ms 和 end_utterance.end_ms 映射，起止及证据语句有序、处于相应区间；后续人工边界另存 revision。
   - 视觉帧可来自区间内部或局部边界观察范围；边界观察范围和采样配置保存在运行 manifest。
   - 单主播正式讲解区间不非法交叠；歧义重叠先解决或作为未决候选，不直接批准导出。

时间单位始终是全局毫秒。JSON Schema 无法独自验证跨对象引用和 start/end 大小关系，所以“通过 Schema”不能等同于业务合法或识别正确。

为降低初期集成量，转写、目录和帧元数据内嵌在结果包；未来拆成独立对象引用时升级 schema_version，并同步 Java/Python/前端。不要在同一个 1.0 格式里让某个模块悄悄改字段。

本次在 PowerShell 的 Test-Json 中通过了正例 Schema 校验，并检查了 4 个应拒绝的错误变体。可在 contracts 目录中重复运行正例检查：

```powershell
Get-Content -LiteralPath './analysis-result.example.json' -Raw |
  Test-Json -SchemaFile './analysis-result.schema.json' -ErrorAction Stop
```

这个命令只做 Schema 校验，不能替代上面的业务校验规则，也不代表任何模型已实际运行。
