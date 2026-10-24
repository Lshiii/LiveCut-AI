# Python 计算 Worker

Java 负责状态、租约和请求数预算，单槽 Python Worker 执行媒体、ASR、OCR 和商品分段。复用已有算法，没有为接入后台改写识别策略。固定流程是 MEDIA → ASR → SEGMENT → TIMELINE，成功后进入 REVIEW_READY，输出仍为 DRAFT。

审核确认后，独立的 EXPORT_CLIP 阶段从冻结修订生成正式 MP4。它复用领取、心跳和完成协议，与分析状态隔离，不调用模型。持续 Worker 默认也领取导出；只处理导出可运行 `./scripts/worker.ps1 -StageType EXPORT_CLIP -Once`，每次处理一个片段。详细验证见 [正式导出](./12-正式导出与下载.md)。

## 启动

先启动 [Java 服务](./07-Java视频来源服务.md)，私有 `.env.java` 中配置相同的 `LIVECUT_WORKER_API_KEY` 和共享 `LIVECUT_STORAGE_ROOT`。Java 与 Worker 必须指向同一个目录。模型凭证继续从进程环境变量 `DASHSCOPE_API_KEY` 读取，FFmpeg/ffprobe 可用对应 LIVECUT 环境变量指定。不要把密钥放进命令行或前端。

```powershell
uv sync --dev --extra ocr
./scripts/worker.ps1
```

此命令会持续轮询；另一个终端运行 Java。按 Ctrl+C 停止 Worker。仅验证媒体阶段、不发起模型提交：

```powershell
./scripts/worker.ps1 -StageType MEDIA -Once
```

脚本只加载 LIVECUT 配置且不打印其值，退出后恢复进程环境；相对存储路径与 Java 的 backend-java 工作目录解析规则一致。直接 CLI 也可运行：

```powershell
uv run --extra ocr livecut worker --java-url http://127.0.0.1:8080 --storage-root C:/LiveCutData --journal-dir data/worker-journals/local --worker-id local
```

创建任务见 [任务 API](./08-持久任务与调用预算.md)。同一个 journal 目录有进程锁，不能同时运行两个 Worker；每个实例使用独立 worker_id 和 journal。内部 API 默认禁止远程明文 HTTP，外部部署需 HTTPS；当前仅交付本机共享卷方式。

## 恢复与文件验证

- 领取前先持久化 claim_request_id，响应丢失重试原领取请求。
- 计算时独立线程心跳，失去租约或超过 hard deadline 后拒绝继续发起 Provider 请求。
- 每个 attempt 写入独立目录。Worker 校验前序文件哈希，路径的真实位置也必须留在相应 attempt 内。
- manifest 写完后先持久化完成回调。回调响应丢失时重放原回调，计算不重新执行。
- Java 收到 complete/checkpoint/provider response 引用后检查文件存在、SHA256、身份、必要产物和路径边界；重复完成回调也重新检查文件，不能用已成功状态掩盖后改文件。
- manifest 最大 1 MiB、checkpoint 4 MiB、Provider 响应 16 MiB、审核分析 JSON 8 MiB。大音频采用流式哈希，不整体载入内存。

真实模型调用先预留预算，再取得一次性 DISPATCHING 许可。成功响应原文保存后记录哈希；重跑 attempt 可复用原始响应，ASR 优先恢复已知 task_id。提交结果不明且没有可验证恢复证据时停止，不自动重新提交。

Windows 文件 I/O 使用扩展路径保存深目录；付费派发前探测响应文件位置可写。若主响应落盘失败，尽力写短名应急 journal 并将已知 ASR task_id 单独记入 Java。磁盘和网络故障仍可能同时发生，这不是供应商侧 exactly-once 保证。

## 真实验证记录

2026-10-24，已有公开 600 秒开发录像通过 Java 来源注册后，由 Worker 完成四阶段；所有 manifest 和 artifact 磁盘哈希、job/video/generation/source 绑定核对通过，升级 Java 强文件校验后四阶段 complete 重放均返回 ALREADY_APPLIED。

本次得到 97 条 ASR、14 个 OCR 采样帧、15 个区间，3 个候选类别为面膜、面霜、妆前乳。9 个区间为已归属 product_talk。妆前乳仍存在把“面霜用于妆前”当成独立品类的风险；这些是开发样本结果，不是独立测试集精度。

本次预算实际预留 ASR 1/1、文本 9/12，含一次格式修复。文本响应 usage 合计输入 28,085、输出 3,595，共 31,680 tokens；ASR Provider 返回 duration 600 秒。它们是调用记录，未换算为实际账单。

首次 ASR 收到响应后遭遇 Windows 长路径保存失败，Java 保留 DISPATCHING、额度没有释放。依据 [供应商任务查询 API](https://help.aliyun.com/en/model-studio/manage-asynchronous-tasks)，只读找到原任务，通过完整音频对象 key 与返回 file_url 一致性核实归属，下载真实结果恢复。原 attempt 保留失败记录，新 attempt 读取已知真实结果；没有伪造提交 ACK，没有重新提交 ASR。该次恢复包含明确记录的人工对账，不能声称这次异常已全自动恢复。

本机全 Python 验证 252 项及 98 子测试通过，Ruff 通过；其中 60 项新增测试涵盖协议与故障。真实 FFmpeg 的 300 字符以上 attempt 路径回归也实际执行。Java 同期 Verify 77 项通过、无跳过，其中包括并行开发的审核功能 11 项，T4 及其既有依赖为 66 项。Mock Provider 测试证明调用控制，不证明识别效果。

另一个独立零模型预算任务完成真实进程中断恢复：通过故障 harness 在真实 claim 后、媒体计算前设置暂停点，确认进程归属后终止该 Python 进程；Java 在 90 秒租约自然到期后回收，旧 heartbeat 返回 STALE_ATTEMPT。正常 CLI 的 attempt 2 使用真实 FFmpeg 完成 MEDIA，输入哈希保持不变，原暂停点证据保留，预算始终为 0。该任务随后通过 API 取消，避免进入 ASR。此验证证明领取后死亡的恢复，不扩大为 ASR 推理中断的实测结论。证据在 `data/runs/java-worker-fault/`。

本地证据保存在忽略目录 `data/runs/java-worker-smoke/`：end-to-end-verification、model-ledger-verification 和 asr-reconciliation-evidence。素材、原始响应与转写不提交公开仓库。
