# 无清单分析结果契约 v2.0

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
