# 旅行动态规划助手

基于 Java 21、Spring Boot 4.1.1、AgentScope Java 2.0.4 与 Vue 3 的旅行助手。目前处于 M0 工程接线阶段，业务功能尚未实现；需求 v1.0、技术设计 v1.5 已冻结。

## 工程结构

| 目录 | 职责 | 依赖方向 |
| --- | --- | --- |
| api | HTTP 接口、请求响应对象、Web 安全配置 | application |
| application | 用例编排、应用服务、对外端口 | domain |
| domain | 业务模型、状态转换、核心规则 | 不依赖其他项目模块和框架 |
| agent | Harness 适配、Agent 角色及上下文 | application、domain |
| infrastructure | 持久化、Redis、外部工具及执行基础设施 | application、domain |
| bootstrap | 唯一后端启动入口与依赖装配 | 其他五个后端模块 |
| web | Vue 3＋TypeScript 前端 | HTTP 接口；独立于 Maven |
| docs | 需求、设计、计划、规范和日志 | 见[文档导航](docs/README.md) |

Controller 和 Agent 工具通过应用服务或端口协作，不能直接访问任意 Mapper。业务领域在各层内部按包组织，不继续拆成大量 Maven 模块。

## 本地准备

- JDK 21、Node.js 24 LTS（当前验证版本 24.14.0）、npm。
- Maven Wrapper 固定 Maven 3.9.11，首次构建需要联网下载依赖。
- Docker Desktop / Compose 用于 PostgreSQL、Redis 和完整容器联调。
- 当前骨架不需要任何模型、地图或检索服务密钥，不会发起这些服务调用。

## 无外部服务的快速验证

在项目根目录执行：

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress verify
java -jar bootstrap/target/traveling-agent.jar --spring.profiles.active=smoke
```

另开终端启动前端：

```powershell
cd web
npm ci
npm run dev
```

访问 `http://127.0.0.1:5173`。开发代理将 `/api` 转到 `127.0.0.1:8080`。后端提供：

- `GET /actuator/health`：进程健康状态。
- `GET /api/v1/system/info`：骨架装配状态，不包含凭证或用户数据。

`smoke` 禁用数据库、Flyway、Redis 和服务端会话自动配置，仅验证 Web 层、应用服务与 Harness 适配类装配。它不代表数据库、登录、真实模型、工具调用或恢复机制已验收。当前未提供可登录账号，其余接口默认拒绝访问，CSRF 防护保留。

Windows 下重新打包前先停止运行中的同一 JAR，避免文件占用使 Boot repackage 失败。

## 本地 Docker Compose

先启动 Docker 引擎，复制配置并修改两个示例密码：

```powershell
Copy-Item .env.example .env
```

只启动中间件，供 IDE 调试：

```powershell
docker compose up -d --wait postgres redis
```

在 IDE 中启动 `TravelingAgentApplication`，启用 `local` profile，并设置与 `.env` 一致的 `POSTGRES_PASSWORD`、`REDIS_PASSWORD` 环境变量；其他连接参数默认指向本机。**Spring Boot 不会自动读取 Compose 的 `.env` 文件**。如修改数据库名、端口或用户，也需同步设置对应环境变量。启动会执行 Flyway；V1 创建应用 schema，V2 建立请求预算和计量表；旅行及账号业务表在后续迭代追加。

完整容器方式（先在宿主机生成后端 JAR）：

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress verify
docker compose --profile app up -d --build --wait
docker compose --profile app ps
```

访问 `http://127.0.0.1:8088`，Nginx 同源转发 API。PostgreSQL、Redis、后端和前端均有健康检查，web 在后端健康后启动；`--wait` 等待就绪。Nginx 仅转发健康检查，其他 `/actuator/` 路径返回 404。

```powershell
docker compose --profile app logs --tail 100 backend
docker compose --profile app down
```

`down` 保留命名卷。PostgreSQL 卷保存业务状态，Redis 卷保存会话数据；`agent-files` 预留给附件及确需保留的框架文件，不承担文本会话、检查点和 JSON 产物的权威存储。数据库和文件的备份恢复流程在后续阶段验证。

镜像标签写在 Compose / Dockerfile 中；JRE 当前使用 Java 21 系列标签，本轮实际解析的镜像 digest 已记录在 Docker 验证日志中。配置仅供本地开发，云端 HTTPS、密钥托管及发布方案另行落实。

## 真实本地基础设施验证

```powershell
.\scripts\verify-local.ps1
.\scripts\verify-persistence.ps1
```

第一个脚本临时读取 `.env` 的连接配置，验证 Flyway、PostgreSQL JSONB 与事务回滚、Redis Session 以及 MyBatis 请求计量、并发预占和未知用量，执行后恢复当前进程环境变量。普通 Maven verify 默认跳过需显式启用的容器测试；实际测试数以当次报告为准。

第二个脚本会重新创建本项目四个容器，验证 PostgreSQL 和 Redis 数据在命名卷中保留，随后清理本轮随机探针，不删除卷或业务数据。运行时会短暂中断本地访问。它验证基础设施持久化，不代表应用阶段恢复和登录业务已经实现。

本轮实际结果及镜像摘要见 [Docker 验证日志](docs/work-logs/0002-2026-10-15-Docker验证.md)。

## 检查与进度

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress verify
docker compose --env-file .env.example --profile app config --quiet
cd web
npm run build
```

后端验证包含模块依赖边界和无需外部服务的 HTTP 启动测试；前端构建包含 TypeScript 类型检查。数据库、Redis、模型兼容性等验证状态见[开发实施计划](docs/plans/开发实施计划.md)和[工作日志](docs/work-logs/0001-2026-10-15-工程骨架.md)。

## 开发与代理约束

模型调用准入默认关闭。计量代码不创建 SDK，也不会发送真实请求。`config/free-quota.example.yml` 仅是过期、零额度且未确认停用的安全示例；真实非凭证快照可放入被忽略的 `config/free-quota.local.yml`，从项目根目录启动时可选加载。不要覆盖已有本地文件，不要将 Key 写入快照。

准入检查精确角色、型号、地域、接口、快照有效期、额度和供应商停用保护，再在短事务中预占原运行预算。记录已知 Token／耗时，未知或未结束请求保留保守预占；重建仓储或重复建账不重置预算。单实例实际请求最多 4 个；许可提供的超时须在下一阶段接入真实 SDK，不能仅因超时或浏览器断开就提前释放仍在途请求。

本地计量只覆盖本项目，不是账户实时余额，也不保证其他程序不会消耗额度。供应商保护、实际端点、权限及用量回调要在 Harness 接线时实测；当前尚未实现完整运行状态机、活跃区间驱动和故障补偿。不得在未完成这些对应门槛时将参数存在误称为全套运行恢复已实现。

开始工作前阅读根目录 [AGENTS.md](AGENTS.md)。开发阶段必须按计划串行执行，测试及推送前 Review 通过、日志补齐并核对远程 main 包含交付提交后，才可进入下一阶段。所有真实密钥禁止进入对话、日志或 Git；.env 永不提交。

用户指定 docs/ 全部只保留在本地，因此仓库页面中的 docs 链接仅在具备本地资料的工作区可用。编码、环境、技术栈和流程详见本地 [standards](docs/standards/README.md)。新克隆环境若缺少冻结方案或阶段资料，需先由用户在本机补齐；不能根据 README 摘要擅自开始实现。

## 离线 SSE 与执行资源探针

使用 smoke profile 启动后，可访问 `/api/v1/system/probe-events`：固定发送 started 和 completed 两个事件，五秒超时、最多两条连接；断开连接不会取消固定后台任务。该接口只验证 MVC SSE 接线，不接收旅行需求，不访问模型或工具，不支持历史事件补读；非 smoke 环境不装配且默认拒绝访问。

执行资源端口提供全局活跃运行与每运行调查分支限制，当前配置分别为 2 和 2。分支使用独立虚拟线程资源，父流程以 CompletionStage 组合结果；真实模型请求仍单独使用全局 4 个请求槽。关闭运行范围后，尚未退出的分支继续占用容量。资源实现不替代 PostgreSQL 阶段调度、租约、预算及业务取消；这些按后续阶段实施。

DEV-0008 已增加受门禁保护的模型 SSE 传输，运行适配器采用流式 SDK；本地可控 HTTP 响应覆盖真实 SDK／Harness 的工具与结构化输出流程。终帧累计用量在响应关闭后结算，取消、截断或用量缺失仍保留 UNKNOWN；单行／事件、总流及帧数均有限制，不缓存完整生成结果。

按用户要求，本阶段仅本地验证，三个型号的真实流式兼容性仍未运行，不将本地替身结论写为供应商验证通过。M0 真实验证门槛保留，未经针对当前范围的明确允许，不执行真实调用或额度查询，不反复询问余额。

## 领域模型与契约

DEV-0009 增加纯 Java 任务／需求版本、运行／阶段／尝试、执行资格、候选快照依赖、证据及偏好版本、决策／回答规则。新修改先提升执行代次，未完成输入按消息顺序处理；等待不重置预算，终态不可复活；租约绑定用户、任务、运行及阶段。问题推荐、回答接收与用户采纳分开建模。

application 的阶段提交及候选接受类型仅定义输入与持久化契约，尚无阶段提交仓储实现。领域本地测试不代表登录、数据库并发提交、正式行程校验或恢复已经实现；这些继续按本地实施计划推进。

## 后端账号认证

local profile 提供 `/api/v1/auth/csrf`、`register`、`login`、`me`、`logout`。先 GET csrf，使用同一会话 Cookie 和返回的请求头提交注册／登录；登录后重新取得 CSRF 再提交后续写请求。登录名 3～32 位字母数字下划线、大小写不敏感；密码 8～64 个字符且 UTF-8 最多 72 字节。密码不放命令行或日志。

PostgreSQL 保存账号及 BCrypt 哈希，Redis 保存不含密码的会话及临时认证限流计数。登录轮换会话 ID／CSRF，退出使服务端会话失效；Cookie HttpOnly、SameSite=Lax，只有本地 HTTP 配置关闭 Secure。可用 `scripts/verify-local.ps1 -Scope Identity` 验证本地账号流程，不调用外部服务。登录和注册限流参数位于 `travel.identity`，默认每地址登录 30 次／分钟、每账号 10 次／分钟、注册 10 次／分钟。

本阶段仅交付后端认证，前端登录页面及旅行资源接口后续接入。smoke 不提供真实登录；已有 app 容器未自动重建，不代表运行中镜像已更新。

## 必要产物与本地恢复

普通工具响应、网页文本、会话、检查点和JSON阶段产物继续以PostgreSQL为权威存储。独立需展示的文本／JSON产物保留原始UTF-8字节及哈希；图片、PDF和实际产生的必要框架文件才存文件卷。框架文件不可下载，不增加附件上传业务。`GET /api/v1/artifacts/{id}`要求所属用户会话，限10MiB、限定类型、附件下载、禁止缓存，读取时核对大小和SHA256；缺失或损坏明确失败。

宿主机文件根默认`.runtime/artifacts`，Compose后端为`/var/lib/traveling-agent/artifacts`，与可丢弃的`.runtime/harness`分离。文件落盘及索引登记与孤儿回收使用PostgreSQL事务锁协作；仅回收24小时之前的无引用受管文件，不自动删除未知文件或有效附件。回收仅为受信维护应用入口，无HTTP／Agent工具入口。

停止所有宿主机执行器后，可用PowerShell 7运行`./scripts/backup-local.ps1 -Action Backup -Directory .runtime/backups/manual-001 -Quiesced`。脚本短暂停止运行中的容器后端，备份数据库与必要文件，随后恢复后端运行状态；目标必须为新目录。宿主机文件模式另传`-FilesDirectory .runtime/artifacts`。备份含私有数据，应保存在受限本地目录；不会包含`.env`或Redis登录会话，恢复后重新登录。

运行`./scripts/backup-local.ps1 -Action Verify -Directory .runtime/backups/manual-001`，会在随机临时数据库和临时卷中实际恢复，逐表比对内容指纹并核对每个必要文件引用的大小与哈希，结束清理临时资源；不会覆盖当前源库或原文件卷。该脚本是本地恢复演练，云端备份、加密存放、RPO/RTO及正式切换恢复流程留待部署阶段。`./scripts/verify-local.ps1 -Scope Artifact`覆盖本地存储、隔离、回收锁及备份恢复，不调用真实外部服务。

## 本地故障与资源验收

`./scripts/verify-local.ps1 -Scope Fault`运行输入及阶段事务硬退出、失联租约、取消迟到结果、重复提交、并发接受、SSE断线与会话失效、偏好删除、上下文及附件恢复、模型超时和限流、免费额度暂停等确定性实验。测试只使用本地PostgreSQL／Redis、回环HTTP和受控模型替身；不调用真实供应商。测试脚本会暂时停止运行中的容器后端，避免其Worker争抢测试任务，结束后恢复原运行状态；其他宿主机执行器应先停止。

`./scripts/verify-faults.ps1 -IncludeContainerFaults`额外执行后端SIGKILL重启、PostgreSQL／Redis重启和随机持久化探针核对，会短暂中断服务并保留命名卷。`-ContainersOnly`可只补做容器实验，避免重复已经通过的测试。容器健康探针与精确事务边界的JVM硬退出测试分别记录结果；本地故障通过不代表云端可用性或真实模型质量验收完成。脚本不保存控制台日志。真实免费额度耗尽经HTTP或SSE到达时，类型化拒绝会直接进入PAUSED_QUOTA，UNKNOWN用量仍保留原预占，不自动重发、付费或更换型号。
