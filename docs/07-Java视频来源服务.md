# Java 视频来源服务

本功能提供持久化视频来源、上传与 Range 播放，为分析任务和审核工作台准备输入。后续的任务协议见 [持久任务与调用预算](./08-持久任务与调用预算.md)。

## 环境与启动

Java 21、Spring Boot 4.1.2、官方 Maven Wrapper 3.10.0。MySQL Compose 固定 8.4.13 及已从官方镜像仓库核验的 manifest 摘要。

依赖依据：[Spring Boot 系统要求](https://docs.spring.io/spring-boot/system-requirements.html)、[Maven 官方下载](https://maven.apache.org/download.cgi)、[MySQL 发布说明](https://dev.mysql.com/doc/relnotes/mysql/8.4/en/news-8-4-13.html)。Spring MVC 的 Resource 响应支持 [HTTP Range](https://docs.spring.io/spring/reference/web/webmvc/mvc-range.html)，视频播放不应将完整文件载入内存。

在仓库根目录创建私有 `.env.compose`，参考 `infra/.env.example` 填入本机密码：

```powershell
docker compose --env-file .env.compose -f infra/compose.yml up -d
```

再创建 `.env.java`，其中密码与 Compose 中的应用账户一致：

```text
LIVECUT_DB_URL=jdbc:mysql://127.0.0.1:13306/livecut?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC
LIVECUT_DB_USER=livecut
LIVECUT_DB_PASSWORD=填写本机密码
LIVECUT_STORAGE_ROOT=../storage
```

```powershell
./scripts/java.ps1 -Task Verify -EnvFile .env.java-test
./scripts/java.ps1 -Task Run
```

首次启动 Compose 会另外建立空的 `livecut_test` 数据库供集成测试使用。复制 `.env.java` 为 `.env.java-test`，仅将 URL 中的数据库名改为 `livecut_test`。Verify/Test 默认读取测试配置，Run/Package 默认读取开发配置；集成测试要求独立空库，只清理自己生成的 UUID 记录。

脚本仅将配置传给当前子进程，不执行环境文件内容、不显示密码；结束后恢复当前 PowerShell 环境。服务默认只绑定本机地址。生产存储、身份认证和公网部署不在这一功能内。

真实视频、数据库、运行输出及私有环境文件都不提交。Compose 的数据卷用于本项目，停止时使用 `docker compose --env-file .env.compose -f infra/compose.yml stop`；日常停止无需删除数据卷。

## 验证记录

2026-10-23，本机 Java 21.0.9 / MySQL 8.0.44 独立实例下，Maven `verify` 共 20 项测试通过、无跳过，包括上传留档、应用重启、首尾 Range、416、HEAD、列表、错误响应和事务故障注入。未使用 H2 替代数据库。

另外使用首轮真实 600 秒录像上传：返回 201、25329992 字节，SHA256 与原视频一致。从开头、20000000 字节偏移、文件尾各请求 1024 字节，均返回 206 并与源文件逐字节一致；HEAD 返回正确完整长度，无效 Range 返回 416。证据保存于本地忽略目录 `data/runs/java-video-smoke/`。

本机 Docker Desktop 启动出错，因此本机未运行 Compose/MySQL 8.4.13；GitHub CI 已在 MySQL 8.4.13 上通过本功能的 20 项测试，见 [Java checks](https://github.com/Lshiii/LiveCut-AI/actions/runs/39968407763)。本机与 CI 分别验证，不将本机 8.0.44 的结果写成 8.4 测试结果。

## API

| 请求 | 行为 |
|---|---|
| `POST /api/videos` | multipart 字段 `file`，返回 201、UUID 和元数据 |
| `GET /api/videos?limit=20&offset=0` | 返回 `items/limit/offset`，limit 上限 100 |
| `GET /api/videos/{id}` | 查询元数据，不返回本地绝对路径或存储 key |
| `GET /api/videos/{id}/content` | 流式 MP4；支持单段及多段 Range |
| `HEAD /api/videos/{id}/content` | 返回长度，不发送视频内容 |

```powershell
curl.exe -F "file=@data/raw/sample.mp4" http://127.0.0.1:8080/api/videos
curl.exe -H "Range: bytes=0-1023" http://127.0.0.1:8080/api/videos/替换为返回的UUID/content --output data/range.bin
```

元数据采用 snake_case；错误包含 `error_code/message/retryable/request_id`。默认最大 1 GiB，只接受 MP4 文件名及支持的基本 ftyp 品牌头，保存为 `HEADER_CHECKED`，尚不声称该视频已完整解码或完成 AI 分析。

文件使用生成的 UUID key，流式哈希与同目录临时文件原子发布；数据库提交结果不明确时保留源文件供核对，尚未实现自动孤儿文件回收。后续任务必须核对来源哈希并进行完整媒体探测。

事务故障测试会先在真实 MySQL 上提交成功，再故意抛出提交响应异常，验证已提交记录的源文件仍在。不能仅按异常类名或 Spring 的回滚回调判断是否可删除：提交阶段开始后即保守保留源文件；未进入提交阶段且已确认回滚时才补偿清理。
