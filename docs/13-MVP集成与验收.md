# MVP 本机集成与验收

## 本机启动

以下命令在仓库根目录的 PowerShell 执行。Java、Worker 和 Vue 分别运行在三个终端；MySQL 使用独立后台进程或 [Compose](./07-Java视频来源服务.md)，当前没有将全部服务容器化。

依赖：Java 21、Python 3.11 和 uv、Node.js 22.18+ 或 24.12+、FFmpeg/ffprobe。前端具体 Node 约束以 `frontend/package.json` 为准。Windows 本机数据库辅助脚本要求 PowerShell 与 CIM/NetTCP 模块可用；不会管理 Windows 的 MySQL80 服务。

### 1. 选择独立 MySQL 实例

已有 Compose 环境可沿用 `infra/compose.yml` 的 MySQL 8.4.13。下述原生方式适用于已安装 MySQL 二进制、但 Docker 不可用的 Windows 本机。二者均使用 `127.0.0.1:13306`，不要同时启动。

原生辅助脚本 [mysql-local.ps1](../scripts/mysql-local.ps1) 只接受**已经初始化**、目录名以 `livecut-mysql-` 开头的本项目目录。它不创建/初始化/清空/迁移数据库，不调用服务管理器，也不强杀 mysqld。不要将 `MySQL80` 的 datadir 填入配置，不要将已有目录改名或搬入本项目。二进制应与该目录原有版本一致；脚本以 `--upgrade=NONE` 拒绝启动时自动升级。

```powershell
if (-not (Test-Path -LiteralPath .env.mysql-local)) { Copy-Item .env.mysql-local.example .env.mysql-local }
```

在私有 `.env.mysql-local` 填入实际二进制路径、独立 datadir 和管理账号密码。值是字面量，不是 PowerShell；不整体加引号、不写 `$env:` 或表达式。路径可含空格；MySQL 8.0 的 Windows 本机方式建议使用纯 ASCII datadir。已有实例可指向其原目录，**无需重复初始化**。

仅当是第一次部署且还没有独立目录时，由操作者明确选择一个**不存在的新目录**，按 [MySQL 官方初始化步骤](https://dev.mysql.com/doc/refman/8.0/en/data-directory-initialization.html) 准备，使用与后续运行相同的 binary。示例是可选的人工初始化，不是启动脚本的一部分：

```powershell
$mysqlBin = 'D:\MySQL\MySQL Server 8.0\bin'
$mysqlData = 'C:\livecut-local\livecut-mysql-local'
if (Test-Path -LiteralPath $mysqlData) { throw '目录已存在：请复用已有配置，或另选新目录；不要重新初始化。' }
New-Item -ItemType Directory -Path $mysqlData | Out-Null
& "$mysqlBin\mysqld.exe" --no-defaults --initialize "--basedir=$(Split-Path -Parent $mysqlBin)" "--datadir=$mysqlData" "--log-error=$mysqlData\initialize.err"
```

临时管理员密码只在本地 `initialize.err` 中，由操作者自行查看并保管，不贴到聊天、日志报告或仓库。将该临时密码填入私有配置后启动；首次登录必须先修改管理员密码，再更新私有配置。也可使用自己已配置好的独立实例，跳过全部初始化步骤。

```powershell
./scripts/mysql-local.ps1 -Task Status
./scripts/mysql-local.ps1 -Task Start
```

首次初始化生成的账号密码可能处于过期状态，`Start` 的身份查询会返回 `MYSQL_CLIENT_FAILED`，但不会强杀刚启动的独立实例。此时只在交互式客户端中修改密码：

```powershell
& "$mysqlBin\mysql.exe" --no-defaults --protocol=TCP --host=127.0.0.1 --port=13306 --user=root --password --connect-expired-password
```

在客户端执行 `ALTER USER 'root'@'localhost' IDENTIFIED BY '替换为你自己的管理密码';`，更新私有配置后再运行 `Status`。不要把真实密码放进命令行参数。

以管理账号登录后，**仅在这两个新库尚不存在时**人工创建开发库、独立测试库和应用账号；若已有同名库/账号，先核对并复用，禁止删除或重建它们：

```sql
CREATE DATABASE livecut_dev CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE livecut_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'livecut'@'127.0.0.1' IDENTIFIED BY '替换为应用账号密码';
GRANT ALL PRIVILEGES ON livecut_dev.* TO 'livecut'@'127.0.0.1';
GRANT ALL PRIVILEGES ON livecut_test.* TO 'livecut'@'127.0.0.1';
```

`Status` 核对监听 PID、二进制路径、真实命令行中的 datadir/port/loopback、服务归属和认证后的 `@@datadir/@@port/@@bind_address`。`Start` 对已匹配的在线实例返回 `ALREADY_RUNNING`。`Stop` 重验身份后只执行 `mysqladmin shutdown`，等待端口及对应 mysqld 进程全部退出；账号缺少 SHUTDOWN 权限、密码错误、进程变化或端口被占时拒绝操作，不以强杀兜底。密码通过仅当前 Windows 用户可读的临时客户端配置传递，随后删除；不会出现在命令行、状态 JSON 或脚本输出中。[MySQL option files](https://dev.mysql.com/doc/refman/8.0/en/option-files.html)、[mysqladmin](https://dev.mysql.com/doc/refman/8.0/en/mysqladmin.html)。

日常检查/停止：

```powershell
./scripts/mysql-local.ps1 -Task Status -EnvFile .env.mysql-local
# 先停 Worker 和 Java，确认无正在执行的任务，再正常关闭独立实例：
./scripts/mysql-local.ps1 -Task Stop -EnvFile .env.mysql-local
```

辅助元数据在忽略目录 `data/mysql-local-13306.json`，不作为停库依据；旧 pidfile 或旧 JSON 不会决定目标进程。脚本不读取旧的 `data/mysql-dev-process.json` 来操作实例。测试专用 `13316` 必须显式传 `-Scratch`，不能用它管理业务实例。

### 2. 配置并启动应用

```powershell
if (-not (Test-Path -LiteralPath .env.java)) { Copy-Item .env.java.example .env.java }
if (-not (Test-Path -LiteralPath .env.java-test)) { Copy-Item .env.java-test.example .env.java-test }
uv sync --extra ocr --dev
npm --prefix frontend ci
```

填写两个私有文件中的应用账号密码、随机 Worker API key；Verify/Test 默认读取 `.env.java-test`，Run/Package 默认读取 `.env.java`。测试 URL 必须指向 `livecut_test`，不能将开发库当测试库。模板使用原生方式的 `livecut_dev`；若使用 Compose，请将开发库名改为 Compose 建立的 `livecut`。Java 与 Worker 共用 `.env.java` 的 Worker key 和存储目录，相对 `../storage` 统一按 `backend-java` 解析。

终端一（Java）：

```powershell
./scripts/java.ps1 -Task Verify
./scripts/java.ps1 -Task Run
```

终端二（Worker）：

```powershell
# 分析需要真实 Provider 密钥，仅写入当前后端终端环境，不放进前端或聊天。
$env:DASHSCOPE_API_KEY = [Net.NetworkCredential]::new('', (Read-Host 'DashScope API key' -AsSecureString)).Password
./scripts/worker.ps1
```

只验证已有已确认版本的本地正式导出时，不需模型密钥，可使用 `./scripts/worker.ps1 -StageType EXPORT_CLIP`。Worker 的 journal 位于 `data/worker-journals/<worker_id>`，同一 journal 不得同时启动两份 Worker；重启时保留原目录。FFmpeg 不在 PATH 时，在 `.env.java` 填绝对 `LIVECUT_FFMPEG_PATH` 和 `LIVECUT_FFPROBE_PATH`。

终端三（Vue）：

```powershell
npm --prefix frontend run dev
```

打开 `http://127.0.0.1:5173`；开发代理指向 `127.0.0.1:8080`，没有将内部 Worker key 放入浏览器。用 `Invoke-RestMethod http://127.0.0.1:8080/actuator/health` 检查 Java/数据库健康，再上传、分析、审核、确认具体 revision 和导出。

### 3. 运行边界与证据

上传上限 1 GiB；首版处理经过验证的 H.264/AAC MP4，非零/未知音视频起点会明确拒绝。模型请求数预算与实际账单不同。当前两遍文本策略按 180 秒核心窗口执行，30 分钟至少需要 20 次文本请求；创建任务时显式设置更高上限并保留有限格式修复余量，不能沿用 10 分钟样本的 12 次默认值。

停止顺序是 Worker → Java → 本项目 MySQL，重新启动顺序反向；存储、journal 和 datadir 不要删除。正式导出绑定不可变确认 revision，不会被后续新草稿改写。进程被杀、外部调用结果未知时的恢复语义见 [Worker 说明](./09-Python计算Worker.md)；导出协议见 [正式导出说明](./12-正式导出与下载.md)。

### 本机辅助脚本验证

2026-10-24，PowerShell 7.6.5 / MySQL 8.0.44，执行以下显式隔离测试，共 15 项检查通过：

```powershell
./scripts/tests/mysql-local-smoke.ps1 -MysqldPath 'D:/MySQL/MySQL Server 8.0/bin/mysqld.exe' -RunScratch
```

该测试需要 Windows、PowerShell 7 与已安装的同版本 MySQL 二进制，先确认 13316 空闲。它只在系统临时目录创建新的 UUID 目录，初始化自己的空数据库，通过私有 init-file 为其设置强密码后测试：启停幂等、包含特殊字符的密码、错误密码/目录/端口与其他监听进程拒绝、旧 pidfile 隔离、正常关闭等待进程退出，以及重启后数据保留。测试结束后 13316 已无监听，现有 13306 和 MySQL80 的 PID 未变，辅助脚本临时客户端配置无残留；scratch 数据保留供检查，没有递归删除目录。

本地脱敏报告为 data/runs/mysql-local-helper/report.json。此次测试没有逐条执行上文普通首次 --initialize 和过期密码交互流程，不把文档说明当作已实测结果。

GitHub 的 PowerShell syntax 工作流只在 Windows Runner 解析脚本，不执行数据库初始化或启停；不能以该语法检查替代上述真实 MySQL 测试。

## 真实 30 分钟验收记录

> 待本轮实际运行后填写：素材来源/授权与 SHA256、实际源时长和编码、分析/审核/导出 ID、各阶段耗时及请求 usage、真实成片校验、恢复证据、失败与人工处理。当前占位不表示已完成验收。
