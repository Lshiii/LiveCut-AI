# OCR 证据复用

2026-10-26，已完成 Python 全量回归、真实本地 OCR 与正常 Worker 重试验收。

## 目的

SEGMENT 重试会创建新的执行目录，原来的 OCR 同目录检查点不能跨 attempt/job 复用。新增共享的、按输入内容标识的本地 OCR 证据缓存；Python 只管理计算文件，Java 继续唯一写入任务、租约、调用预算和正式产物状态。缓存命中不代表商品识别正确，也不包含 ASR、LLM 或视觉 API 响应。

本轮保持现有每 45 秒、最多 16 帧的前缀采样策略，以及真实帧时间戳、文本过滤和长度上限。30 分钟视频仍存在后半场未采样的限制，不把证据复用说成视觉覆盖或精度改进。

## 输入身份与完整性

身份绑定源文件实际 SHA256、时长、采样参数/策略版本、OCR 处理版本、RapidOCR/ONNXRuntime 版本、配置和实际模型权重摘要，以及 FFmpeg 的实际身份。仅模型包版本或文件名不足以识别输入；自定义测试引擎/抽帧器必须给出显式身份。

缓存保存受限路径下的完整 OCR 状态、观察、帧清单和 JPEG，发布标记包含绑定及逐文件大小/摘要。读取时严格验证 JSON、字段、文件集合、帧 ID 与时间/观察的一致性；损坏、缺文件、路径穿越、软链接或 junction 明确失败，不静默重跑覆盖。

首次生成在有界暂存中完成，验证后原子发布；同机跨进程通过稳定锁文件互斥，等待期间持续检查执行 guard。缓存容量和条目数有明确上限，无空间或锁长期占用时返回明确错误。仅锁等待超时 `OCR_CACHE_BUSY` 标记可恢复，Worker 交给 Java 已有的有限次数退避机制；完整性、路径、容量错误不自动重试。默认模型尚未就绪的冷环境，完成正常 OCR 后重新读取实际依赖身份才可发布，不以占位模型身份创建命中。

## Worker 与 CLI

Worker 的 OCR 分支默认使用共享存储中的 `cache/ocr-v1/`。命中后重新验哈希并复制证据到当前 attempt，不使用 hardlink 共享可变字节；观察中的引用仍绑定本次执行目录。租约检查贯穿生成、校验、复制与发布。

OCR 完成后，当前 attempt 写入安全 provenance；即使后续文本分析失败，本地完整 OCR 缓存仍可复用。成功 SEGMENT manifest 除 analysis_result 外，新增 OCR provenance、状态、观察、帧清单及每帧的独立哈希引用，沿用 Java 正常 manifest 校验和完成回调。

新增独立 `livecut ocr` 命令，仅做本地媒体探测和 OCR，可在不提交 ASR/文本模型请求的情况下准备、核验和复用证据：

```powershell
uv run --extra ocr livecut ocr data/raw/sample.mp4 --output-dir data/runs/ocr-first --cache-root storage/cache/ocr-v1
uv run --extra ocr livecut ocr data/raw/sample.mp4 --output-dir data/runs/ocr-second --cache-root storage/cache/ocr-v1
```

CLI `analyze` 可选 `--ocr-cache-root`；未指定时保留原同目录 OCR 检查点行为。纯语音分析不访问 OCR 缓存。

## 验收结果

Python 全量回归：462 项测试、98 个子测试通过，无跳过；`ruff check worker-python` 通过。新增单元与真实子进程测试覆盖跨目录命中、输入/依赖隔离、逐字节篡改、严格 JSON 与路径、并发与进程终止后的锁恢复、配额、guard 和证据复制。合成 Worker 测试验证模型输入仍引用当前 attempt、成功 manifest 逐项绑定复制后的证据、纯语音模式不访问缓存，以及只有锁竞争进入可恢复失败；这不是一次新增的真实成功 SEGMENT 模型运行。

### 真实本地 OCR

使用已有的 30 分钟公开开发录像，媒体时长 1,800,017 ms，沿用默认 CPU RapidOCR 3.10.0 / ONNXRuntime 1.31.0。模型权重已在本地；本轮没有验证首次联网下载模型。首次生成和第二目录命中分别在新进程运行，并禁止全部 socket 连接。

| 检查 | 首次生成 | 第二目录命中 |
|---|---:|---:|
| OCR 初始化次数 | 1 | 0 |
| 抽帧次数 | 16 | 0 |
| OCR 识别次数 | 16 | 0 |
| 外部连接次数 | 0 | 0 |
| 本次缓存函数耗时 | 8.672 s | 0.750 s |

两次 cache key 相同，16 条观察完全一致，状态、帧清单和观察 JSON 的字节一致，帧文件按 manifest 摘要重新验证。耗时仅为当前机器、模型已准备且文件系统热缓存下的单次观察，没有统计意义，不写成整体加速比例。`coverage_truncated=true`，最后一帧约在 675 秒，后半场覆盖仍未解决。

### 正常 Java / Worker 重试

创建独立验收任务 `cdf98ccc-3d8d-440e-bc76-3728cac3a10e`，同一原视频的新 generation。MEDIA 正常探测并抽音频；ASR 通过严格复用协议读取已发布转写，没有再次提交 ASR。文本上限设为 1，低于十窗两阶段分析的至少 20 次预检要求，ASR 提交上限为 0；所有阶段仅允许访问本机 Java 服务。

SEGMENT attempt 1 正常生成 16 帧证据并写入 provenance，随后按预期返回 `BUDGET_EXHAUSTED`。通过 Java 正常重试接口创建 attempt 2，新 Worker 进程命中同一缓存，抽帧、OCR 初始化和识别均为 0；两次证据 JSON 字节一致。第二次仍因原预算不足失败，说明重试没有增加预算。任务最终取消收尾，文本/ASR 账本预留均为 0，外部连接为 0。

这是有意设置不足预算的恢复验收，不能描述为重新完成了一次真实商品分析。先前 30 分钟完整分析与正式导出的结果见 [原验收记录](./17-30分钟完整验收.md)。本轮前后逐字节核对原有 25 个公开资源响应及 445 个源/执行文件，全部保持不变。

详细运行证明位于被 Git 忽略的 `data/runs/ocr-cache/`，真实媒体、OCR 文本和私有 Worker 日志不进入公开仓库。

## 当前边界

- 缓存只在共享本地文件系统内协调，不是跨主机分布式缓存。
- 全局进程锁串行化生成与复制；默认最多 128 条、512 MiB，按最坏帧大小保守预留容量。未实现自动淘汰，满额时明确失败，需检查缓存容量。
- 完整性异常明确失败，不自动删除或覆盖已发布缓存。
- 本轮不修改采样覆盖、商品分类、边界判断或 ASR / LLM 调用策略，不产生识别准确率结论。

默认运行时依据：安装环境中的 RapidOCR 配置/解析实现，并对照 [官方配置](https://github.com/RapidAI/RapidOCR/blob/main/python/rapidocr/config.yaml)、[官方初始化流程](https://github.com/RapidAI/RapidOCR/blob/main/python/rapidocr/main.py)。
