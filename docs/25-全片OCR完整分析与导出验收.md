# 全片 OCR 完整分析与正式导出验收

2026-10-26。在 `819efaf` 的全片采样实现上，完成真实 30 分钟开发录像的新一轮分析、开发审核、三段正式成片与重启核验。本轮证明新默认策略能够走完整流程，没有独立人工标注，不报告识别准确率提升。

## 来源与冻结输入

素材沿用 [此前开发录像](./17-30分钟完整验收.md)，时长 1,800,017 ms，159,834,576 字节，SHA256 为 `4868ad1821fb0887123416f3619abb80549fa93d7e309cc9d92dd1fa25618d32`。它与旧 10 分钟样本来自同场回放且有重叠，不是独立测试集。模型输入没有商品清单、SKU、网站跳转点或人工区间。

新任务 `0e1f1aad-d823-453a-99ae-799508df5a64` 为 generation 8，冻结 `ocr / uniform / qwen-plus / paraformer-v2`，TEXT 上限 30、ASR 新提交上限 0。MEDIA 正常提取音频；ASR 从 generation 2 已发布结果严格复用 285 条语句；SEGMENT 命中已验证的 16 帧 uniform 缓存，再进行真实文本分析；TIMELINE 校验并发布原始 DRAFT。四阶段均在 attempt 1 完成，最终 `REVIEW_READY`。

复用不等于原始转写免费：来源任务既有 ASR 调用仍保留。没有调用视觉模型 API，也没有更改计费设置。语音转写是主要时间锚点，OCR 文字辅助语义判断；本地 OCR 不识别商品外观，不能据此声称已核验音画商品一致性。

## 原始预测与请求对账

10 个语义窗口全部接受，285 条语句在窗口分类和最终音频证据中均恰好出现一次；最终边界逐项对应源 ASR 的连续时间锚点。官方结果契约、源视频哈希、四个已发布 manifest、全部产物摘要、SEGMENT/TIMELINE JSON 一致性、16 帧实际 PTS 与图像摘要均通过。

真实上游文本 POST 为 21 次，等于持久账本 21 条和任务已预留次数：10 次类别发现、10 次逐句分类、第二窗一次受控格式修复。全部 HTTP 200，格式修复后通过严格引用校验。输入 68,969、输出 15,345，合计 84,314 tokens。新增 ASR 为 0；审核、导出与只读验收没有新增模型请求。请求数和 usage 不是人民币账单，HTTP 成功也不是语义正确。

SEGMENT 单次执行为 351.031 秒；MEDIA 1.516 秒、ASR 复用 0.500 秒、TIMELINE 0.328 秒。已复用转写和本地 OCR 缓存，且流程中包含开发核查等待，不能把这些数字作为完整冷启动或吞吐性能。

对照只比较未经审核的机器预测，先校验同视频、时长及完整 ASR 内容摘要。两轮使用同一文本提示词版本，但模型响应、类别发现和视觉证据均可能影响结果，不能把全部变化归因于采样。

| 预测结构 | 旧 prefix，generation 3 | 新 uniform，generation 8 |
|---|---:|---:|
| 候选类别 | 16 | 14 |
| 总区间 | 88 | 90 |
| product_talk | 52 | 56 |
| non_product | 33 | 33 |
| mixed | 3 | 1 |
| 完整引用的源语句 | 285 | 285 |

109 条语句的内容类型、身份候选或标准化类别名称发生变化，其中 82 条位于旧末帧 675,009 ms 之后。两轮均无未知、缺失或重复语句引用，区间无重叠。这些是结构与行为变化，旧预测不是参考答案，类别或候选数量也不是正确数量。

## 开发审核与三段成片

revision 0 保留新原始预测。开发 Agent 查看三段对应 ASR 和分别抽取的源中点画面后，revision 1 保留全部 90 段，仅启用以下 3 段，其余 87 段禁用；没有改自动边界或商品归属。该核查不是完整观看、独立人工标注或质量评分。

确认 r1 后生成批次 `0689ee88-6033-4468-8768-bceca285667e`，三个 EXPORT_CLIP 均通过正常 Worker 的 attempt 1 完成。

| 商品 / 原预测 ID | 原视频起止 ms | 目标时长 | 成片时长 | 偏差 | 文件字节 |
|---|---|---:|---:|---:|---:|
| 乳液 / segment-0018 | 419900–462769 | 42.869 秒 | 42.851 秒 | −18 ms | 1,555,650 |
| 面膜 / segment-0065 | 1224748–1280544 | 55.796 秒 | 55.822 秒 | +26 ms | 2,156,275 |
| 面霜 / segment-0070 | 1305991–1347890 | 41.899 秒 | 41.913 秒 | +14 ms | 1,908,413 |

三文件均 H.264/AAC、yuv420p、360×640，下载字节与清单大小/SHA256 一致，FFmpeg 对全部音视频严格解码退出 0；时长偏差均在本次 100 ms 容差内。确认 JSON 与冻结 r1 逐字段一致，批次指纹按冻结输入重新计算通过。逐文件 HEAD、首中尾各 32 字节 Range 精确响应及越界 416 均通过。

浏览器核验了 uniform 标签、4/4 完成、TEXT 21/30 与 ASR 0/0、r1 的 3 个候选和 3/3 导出完成，并显示面霜 MP4 的实际首帧与 41 秒预览。浏览器预览不等于全片播放验证，完整解码另由 FFmpeg 完成。

## 重启、保留与复现边界

完成后只重启本项目 Java API，健康检查恢复 UP。新任务、原始 r0、确认 r1、确认附件、批次、清单共 6 个资源响应，以及 3 个成片 HEAD 元数据，重启前后字节/摘要相同。对运行前已存在的 31 个公开资源与 631 个源/执行/缓存文件再核对，全部保持原字节或摘要。

本轮不改算法代码；沿用上一实现的 Java 160、Python 699 及 98 个子测试、前端 36 项及构建通过记录，不把这些测试计为本轮重跑。本轮新增的是上述真实运行与独立只读产物核验。

私有证据保存在忽略目录 `data/runs/uniform-e2e/`：各阶段 proof、`analysis-verification.json`、未评分 `localization-audit/`、`comparison.json`、开发审核计划、`export-verification.json`、下载副本、重启和原数据保留证明、浏览器截图。原视频、转写、Provider 响应、执行凭据和媒体文件不发布到 Git。新环境应按 [启动说明](./13-MVP集成与验收.md) 上传自有素材、创建新任务；记录中的本机 ID 不是随代码分发的数据。

复现时先按启动说明配置 Java、Worker 与凭证，再使用新上传的 `$VideoId`。例如以下 PowerShell 创建同配置任务并逐阶段执行；`$SourceAsrJobId` 必须是同视频已成功发布且摘要匹配的 ASR 来源，不是任意旧任务。没有可复用来源时应另行创建允许真实 ASR 提交的任务，不能跳过校验。

```powershell
$requestBody = @{
    video_id = $VideoId
    config = @{
        visual_provider = 'ocr'; ocr_sampling = 'uniform'
        text_model = 'qwen-plus'; asr_model = 'paraformer-v2'
        max_text_calls = 30; max_asr_submissions = 0
    }
} | ConvertTo-Json -Depth 4
$createdJob = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8080/api/jobs' `
    -ContentType 'application/json' -Headers @{'Idempotency-Key' = [guid]::NewGuid().ToString()} `
    -Body $requestBody
./scripts/worker.ps1 -JobId $createdJob.id -StageType MEDIA -Once
./scripts/worker.ps1 -JobId $createdJob.id -StageType ASR -ReuseAsrJob $SourceAsrJobId -Once
./scripts/worker.ps1 -JobId $createdJob.id -StageType SEGMENT -Once
./scripts/worker.ps1 -JobId $createdJob.id -StageType TIMELINE -Once
```

每步成功后再执行下一步；SEGMENT 会产生真实文本调用，30 是次数上限。随后在工作台核查候选、保存并确认版本、创建导出，再按实际选择的片段数运行 EXPORT_CLIP Worker。独立质量评分仍需要另行完成标注。

## 尚未解决的问题

新结果在 21:34 附近将面膜/面霜切换保留为混合候选，后续面霜片段的 ASR 与开发画面检查一致；这是代表性开发观察，不是已测质量提升。精华部分仍出现保湿、美白、修护等类别的短距离切换；保湿面膜/面膜等同义与粒度归并仍依赖模型。尚无独立人工参考，检测 F1、边界质量、返场正确率和审核耗时收益均未评分。

并行代码审查复现了一个合成时间关联反例：长语音空白下，当前逻辑可能把很早的 OCR 帧分配给很晚的最近语句，且模型输入没有实际帧时间。以本轮真实输入和原路由代码重建的 21 个帧—窗口关联中，也有 1 个超出窗口真实上下文：08:26.273 的帧因最近语句跨界，被额外分到从 08:30 开始的上下文，早 3727 ms。该帧也正常进入前一窗口，尚未证明造成语义误判；重建审计不是额外捕获的上游请求正文。合成十分钟错位与本轮实际的轻度越界分别记录。后续优先独立修复时间关联，再补独立素材和质量核验。
