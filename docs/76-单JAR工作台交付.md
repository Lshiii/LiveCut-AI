# 单 JAR 工作台交付

## 得到什么

将生产网页和 Java API 放进同一份 Spring Boot JAR。运行时浏览器直接访问 Java 的根地址，API、截图、视频、下载和 SSE 使用同源路径，不再需要 Vite 代理或单独启动 Node 网页服务。

打包命令只构建，不启动 Java 服务、数据库或 Python Worker，不加载 `.env.java`，不发模型请求。它不是包含数据库与模型的桌面安装包；实际视频分析仍需要单独运行 Python Worker。

原来的三终端开发方式继续保留。已有普通 API-only JAR 与工作台包使用不同构建目录。

## 从源码构建

准备仓库要求的 JDK 21、Node.js/npm。在仓库根目录运行：

```powershell
$package = .\scripts\package-workbench.ps1
```

脚本依次执行锁文件安装、网页生产构建、冻结网页资源、Maven `workbench` profile 打包及包内字节校验。构建不需要数据库或模型密钥。已明确准备好匹配的 `node_modules` 时，可用 `-SkipInstall`；清单会标记使用现有依赖，不冒充刚完成锁文件安装。

输出为 `data/distributions/<本次唯一标识>/package/`，包括：

| 文件 | 用途 |
| --- | --- |
| `livecut-workbench.jar` | 网页与 Java API 的可执行包 |
| `package-manifest.json` | 源版本、是否存在未提交改动、资源及 JAR 的大小和 SHA-256 |
| `source-files.json` | 实际构建输入的相对路径、大小与哈希 |
| `run-workbench.ps1` | 验包并加载外置配置后启动 Java |
| `.env.java.example` | 不含凭证的运行配置示例 |
| `README.md` | 可随包携带的运行说明 |

构建日志、源码快照和中间 Maven 目录留在 `package/` 的上一层，不放进可交付目录。构建失败会保留 `build-status.json` 和日志，不输出成功清单或覆盖已有包。

## 在本仓库使用现有配置

先按主 README 准备并启动数据库，核对 `.env.java` 的数据库、存储和 Worker 身份配置，然后运行：

```powershell
# 使用本次构建返回的目录；新终端也可填写实际 package 路径
.\scripts\run-workbench.ps1 -PackageDirectory $package.PackageDirectory -EnvFile '.env.java'
```

打开 **http://127.0.0.1:8080/**。默认绑定本机 `127.0.0.1`；可通过 `-Port` 或配置中的 `LIVECUT_API_PORT` 选择端口。

仓库中的启动脚本继续按旧 Java 工作目录解释相对存储与字体路径，例如 `.env.java` 的 `../storage` 仍是仓库的 `storage/`。启动前先核 JAR 大小和 SHA-256；配置留在 JAR 外，不把密钥作为命令行参数打印。

启动脚本不会运行 Python Worker。需要新分析或 AI 问答时，另开终端按原指南运行普通 Worker。仅处理已经收到的首答恢复时，可启动独立的专用执行器：

```powershell
.\scripts\worker.ps1 -WorkerId worker-cached-replay -StageType AGENT_EVIDENCE -CachedAnswerReplayOnly
```

这个专用执行器不要求视觉模型密钥，也不能领取普通模型任务；来源文件和 Java Worker 身份仍需正确配置。它与普通 Worker 使用不同 ID 和 journal。

## 将 package 目录交给另一台机器

运行网页/API 只需 JDK 和可访问的 MySQL，不需 Node/npm 或 Maven。复制整个 `package/` 目录，在目录内复制示例并填入自己的数据库配置：

```powershell
Copy-Item .env.java.example .env.java
# 在本地编辑 .env.java 后运行
.\run-workbench.ps1
```

随包启动脚本将相对路径解释为包目录，例如示例的 `./storage` 在包目录内；它与仓库脚本沿用的旧相对路径基准明确区分。自己的视频、存储、数据库、私有配置、Python 环境与模型凭证都不随包分发。Worker 需使用可访问的同一存储与 Java 地址，具体配置仍遵循 Worker 文档。

Java 首次运行会执行数据库迁移，默认维护扫描器也会推进过期执行状态和处理上传恢复。`-DisableScanners` 仅关闭这三个扫描器，不是只读模式，不阻止数据库迁移或用户显式操作，也不阻止外部 Worker 领取任务。

## 保留浏览器中的原草稿与回执

浏览器本机记录按 **协议、主机、端口** 隔离。从 `127.0.0.1:5173` 改到 `127.0.0.1:8080`，旧草稿、备份及未知提交不会自动搬过去，也不能因此认为原请求不存在。

切换前先在原入口核对未决提交，或下载保留原回执和草稿；不要因为新入口为空便另建同一请求。原开发入口和本机数据不会被脚本清除。

如果决定继续使用相同的 `http://127.0.0.1:5173`，先关闭 Vite，再明确用 `-Port 5173` 启动 JAR，即可保留同一 origin。仍须让新 Java 配置指向正确的原数据库和存储；同一 origin 不代表相同业务来源。

## 构建约束与验收边界

- 根路径部署：网页使用根相对资源及 API 地址，不支持直接放在 `/app/` 之类子路径。
- 不增加 SPA catch-all。未知 API、能力接口与缺失资源仍是真实 JSON 404；`/internal` 继续先鉴权，不用首页 HTML 冒充接口响应。
- 包含完整的静态资源，尤其上传本机哈希使用的 `fileHash.worker-*.js`；逐项核实际大小和 SHA，不把 Vite 文件名里的 hash 当字节证明。
- 资源图校验面向当前 Vite 的常量引用；缺失、外链、路径越界或未支持的动态资源形式明确失败，不宣称解释任意手写 JavaScript/HTML。
- 输出目录必须全新且独立。profile 的 `clean` 固定跳过，不能用来清理旧输出；重新构建由脚本创建新的目录，普通 `backend-java/target` 与源码资源不被打包流程清理。
- Windows 构建路径支持空格和中文；CMD 元字符路径会在调用 Maven Wrapper 前明确拒绝，避免参数被当作命令执行。
- 网页和源码在构建前后冻结核对。构建过程中发生变化会失败，不将“源码已变、网页仍旧”报告为成功。

工程验收包括无监听端口的 MockMvc 路由、真实生产资源与包内字节核对、构建边界负例和 PowerShell 模拟启动。它们不等于已经为用户启动了网页服务；实际启动以本机命令结果为准。

## 本轮验收（2026-11-12）

- 工作台 profile 定向 18 项通过，含 13 项资源图/目录校验与 5 项真实静态处理器、鉴权和错误响应测试；无数据库业务 Bean、监听端口或模型请求。普通后端完整 `verify/package` 579 项通过，无失败、错误、跳过。
- PowerShell 5.1 与 7 各 27 项检查通过：正常 stderr 警告、非零退出、参数边界、HTML 输入冻结、构建前链接检查、失败时环境恢复、仓库与便携路径基准、包内缺失/篡改/私有文件/重复/大小写错误均覆盖。
- 实际默认打包命令完成 `npm ci`、生产网页构建及 Maven 打包，特意使用包含空格和中文的输出目录。完整 5 个网页资源逐项与冻结 dist、JAR 及清单比对 SHA/字节一致，普通后端 JAR 未被污染。
- 用实际 Spring Boot 嵌套类加载器读取发行 JAR，加载应用类和 Spring 类但不初始化应用、不调用 main、不启动 Spring 上下文；5 个资源逐字节验证通过。这是包加载验证，不是实际网页启动。
- 独立审阅确认交付目录仅 6 个文件，环境示例中密码与 Worker 口令为空，不包含构建日志、数据库、视频或真实私有配置。初次未提交构建正确标记 `source_dirty=true`，不会冒充已提交版本。
- CI 除普通后端与脚本检查外，增加工作台专用流程：当前端、后端或启动脚本变化时，真正构建交付包，再使用冻结网页与另一个全新目录运行含 UI 的 profile 测试，不以普通 API-only 验证代替打包验证。

此前自动启动网页服务被自动审批拒绝，返回原因仅为 `blocked by policy`。本轮没有换端口或借 Java 服务绕过该限制，未启动实际网页监听；完成包检与无监听测试后，由用户按上述命令手动启动。
