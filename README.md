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

在 IDE 中启动 `TravelingAgentApplication`，启用 `local` profile，并设置与 `.env` 一致的 `POSTGRES_PASSWORD`、`REDIS_PASSWORD` 环境变量；其他连接参数默认指向本机。**Spring Boot 不会自动读取 Compose 的 `.env` 文件**。如修改数据库名、端口或用户，也需同步设置对应环境变量。启动会执行 Flyway；当前 V1 仅创建应用 schema，业务表在后续迭代追加。

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

第一个脚本临时读取 `.env` 的连接配置，验证 Flyway、PostgreSQL JSONB 与事务回滚、Redis Session 仓储读写及删除，执行后恢复当前进程环境变量。普通 Maven verify 默认跳过这 3 项容器测试，仍执行 5 项离线测试。

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

开始工作前阅读根目录 [AGENTS.md](AGENTS.md)。开发阶段必须按计划串行执行，测试及推送前 Review 通过、日志补齐并核对远程 main 包含交付提交后，才可进入下一阶段。所有真实密钥禁止进入对话、日志或 Git；.env 永不提交。

用户指定 docs/ 全部只保留在本地，因此仓库页面中的 docs 链接仅在具备本地资料的工作区可用。编码、环境、技术栈和流程详见本地 [standards](docs/standards/README.md)。新克隆环境若缺少冻结方案或阶段资料，需先由用户在本机补齐；不能根据 README 摘要擅自开始实现。
