# 人工审核 API 与不可变修订

2026-10-24｜T5，单人本机工作台。

审核建立在完成的 `REVIEW_READY` 任务上。原始 `model_result` 始终是机器预测；人工命名、补漏和改边界另存为审核修订，不修改 Worker 文件，也不把人工修正当作模型识别效果。

## 读取与初始化

`GET /api/jobs/{job_id}/review` 读取最新修订；`?revision=0` 可读取指定历史修订。第一次读取在事务内锁定任务，验证已完成 TIMELINE 的 manifest、analysis_result 文件 SHA-256，以及结果中的 `model_result / DRAFT / job_id / video_id / analysis_generation` 绑定关系，然后保存来源快照与 revision 0。两个并发首次读取只初始化一次。

```json
{
  "job_id": "UUID",
  "video_id": "UUID",
  "generation": 1,
  "data_kind": "model_result",
  "duration_ms": 600000,
  "revision": 0,
  "status": "DRAFT",
  "source_hash": "analysis_result文件的SHA-256",
  "confirmed_revisions": [],
  "products": [{"id": "p1", "name": "面膜", "identity_source": "model"}],
  "segments": [{
    "id": "s1", "start_ms": 1000, "end_ms": 10000,
    "product_id": "p1", "enabled": true,
    "origin": "model", "boundary_source": "model", "identity_source": "model",
    "original_prediction_id": "s1"
  }],
  "utterances": [{
    "utterance_id": "u1", "start_ms": 1000, "end_ms": 10000,
    "text": "主播讲话转写", "source_chunk_id": "c0"
  }],
  "predictions": [{
    "id": "s1", "content_kind": "product_talk", "identity_status": "identified",
    "visual_status": "not_checked", "review_reasons": [],
    "audio_evidence_utterance_ids": ["u1"]
  }]
}
```

`data_kind` 表示原始来源类型；`status` 表示本次人工审核状态。人工来源字段进一步区分模型建议与修改。初始仅 `product_talk` 自动勾选；其他内容保留在列表中并默认禁用。未分配商品的启用候选可保存为草稿，确认时必须处理。

ASR 语句和简化预测是原始只读证据。接口不返回 Worker run_token、manifest/object key、私有帧路径或原始 Provider 响应。原始 JSON 的私有快照仅在数据库内部保存。初始化后读取审核使用该已验证快照，不随原文件后续改动漂移。

## 保存编辑

`PUT /api/jobs/{job_id}/review` 使用全量替换，准确提交当前 `base_revision`。只发送以下可编辑字段，不回传响应中的来源、证据和确认元数据。

```json
{
  "base_revision": 0,
  "products": [
    {"id": "p1", "name": "面膜"},
    {"id": "manual_bag", "name": "垃圾袋"}
  ],
  "segments": [
    {"id": "s1", "start_ms": 1000, "end_ms": 5000, "product_id": "p1", "enabled": true},
    {"id": "split_1", "start_ms": 5000, "end_ms": 10000, "product_id": "p1", "enabled": true},
    {"id": "manual_1", "start_ms": 20000, "end_ms": 24000, "product_id": "manual_bag", "enabled": true}
  ]
}
```

成功返回完整新快照，revision 加一，状态为 `DRAFT`。遗漏的片段或商品从该修订删除，历史修订保留。拆分用两个合法独立区间表示；手动补漏直接新增稳定 ID，不伪造 ASR 锚点。客户端应为新增片段分配新 ID，避免把人工新片段复用成原预测 ID。

服务端校验：

- 所有时间为 JSON 整数毫秒，字符串或浮点数不接受；`0 <= start_ms < end_ms <= duration_ms`。
- 商品、片段 ID 各自唯一，只包含字母、数字、点、下划线、冒号和连字符，以字母或数字开始，最多 128 字符。
- 商品名为非空可显示文本，最多 160 字符；单次最多 1,000 商品、10,000 片段。
- `product_id` 必须显式为 `null` 或引用本修订商品；`enabled` 必须为布尔值。
- 启用片段不得重叠；半开区间允许首尾相接。禁用片段仍校验时间和引用，可与启用区间重叠。
- 不接受未约定的可编辑字段。来源信息由 Java 依据原始预测重新计算。

`origin=manual` 表示原始预测不存在该片段 ID；原始片段改边界后 `boundary_source=manual`，改归属后 `identity_source=manual`。人工新增或重命名商品的 `identity_source=manual`。这些字段表达与原始预测的差异，不宣称核验了外部 SKU。

并发保存使用任务行锁和 `base_revision` 比较；旧版本返回 HTTP 409 `REVIEW_REVISION_CONFLICT`，不覆盖另一页面的更新。客户端应显示冲突并重新读取，不自动静默覆盖。重复 PUT 仍带旧 base 时也返回冲突。

## 确认精确版本

`POST /api/jobs/{job_id}/review/confirm`：

```json
{"revision": 1}
```

只确认当前最新精确版本。存在启用但未分配商品的片段时返回 409 `REVIEW_UNASSIGNED_SEGMENT`。成功快照为 `CONFIRMED`，同一当前版本重复确认幂等。确认后可继续编辑，创建新的 DRAFT；旧确认记录和修订内容不变。历史版本可通过 GET 查询，后续导出需显式绑定已确认版本。

`review_document` 保存来源 JSON、来源文件哈希、时长和最新版本；`review_revision` 只插入不可变内容；`review_confirmation` 独立保存确认。`confirmed_revisions` 是该任务现有确认版本集合，属于当前元数据，而非历史修订内容的一部分。

## 错误与验证范围

错误沿用 `{error_code,message,retryable,request_id}`，不包含存储路径或执行凭据。主要业务错误：

| HTTP | error_code | 含义 |
|---|---|---|
| 400 | INVALID_REVIEW | 编辑字段、类型、时间或引用不合法 |
| 400 | INVALID_ARGUMENT | revision 查询参数不合法 |
| 404 | JOB_NOT_FOUND / REVIEW_REVISION_NOT_FOUND | 任务或版本不存在 |
| 409 | REVIEW_NOT_READY | 分析尚未完成 |
| 409 | INVALID_REVIEW_SOURCE | 原始结果类型、身份、时间或引用不合法 |
| 409 | REVIEW_REVISION_CONFLICT | 当前修订已变化 |
| 409 | REVIEW_UNASSIGNED_SEGMENT | 仍有启用候选未归属商品 |
| 409 | ARTIFACT_HASH_MISMATCH | 私有原始文件与登记哈希不符 |

`ReviewIntegrationTests` 使用真实 HTTP 和独立 MySQL 测试库验证初次读取、全量修订、人工来源、类型与区间校验、同时保存、精确版本确认、保留历史和私有路径隔离。测试媒体和结果为合成内容，不调用 Provider，不代表真实识别精度。

在私有 `.env.java-test` 指向独立 `livecut_test` 数据库后，仓库根目录运行 `./scripts/java.ps1 -Task Verify`。没有数据库环境时集成测试会跳过；交付要求检查报告中实际执行且零跳过。
