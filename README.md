# 失物归属核验与交接管理系统

一个可本地运行的课程项目：四个有独立数据库 schema 的业务微服务、Eureka 服务注册与管理目录，以及由 Flowable 驱动的普通认领、争议认领、无人认领处置流程。项目位于独立目录，不使用旧上机实验的 `tool.py`、数据库或密钥。

## 启动

需要 Docker Desktop 与 Docker Compose。首次运行：

```bash
python3 scripts/init_env.py
docker compose up -d --build
docker compose ps
```

### 在另一台电脑独立部署

仓库默认设为私有；对方获得仓库访问权限后，在自己的电脑安装 Docker Desktop（或 Docker Engine 加 Compose）和 Python 3，执行：

```bash
git clone https://github.com/JinningCui/lost-found-workflow-system.git
cd lost-found-workflow-system
python3 scripts/init_env.py
docker compose up -d --build
python3 scripts/seed_demo.py
```

然后在**对方电脑**打开 <http://localhost:5173>。Windows 上如没有 `python3` 命令，可使用 `py -3`。每台电脑都应各自运行 `init_env.py` 生成独立 `.env`；不要传送本机的 `.env` 或数据库卷。GitHub 同步的是源代码，双方在各自电脑上的物品、申请和流程数据不会自动同步。若要从不同网络访问**同一套数据**，需要将服务部署到可公开访问的服务器，并配置真实用户认证、HTTPS 和访问控制；当前固定演示账号不适合直接暴露在公网。

浏览器打开 <http://localhost:5173>。流程 API 在 <http://localhost:8080>，Eureka 控制台在 <http://localhost:8761>。首次构建会下载 Maven、npm 依赖和基础镜像，所需时间取决于网络。再次启动使用 `docker compose up -d`。停止服务使用 `docker compose down`；这会保留 MySQL 数据卷。

可选：运行 `python3 scripts/seed_demo.py` 创建一件可重复演示的“黑色耳机盒”。以 `student/student123` 认领时，问题“盒内侧刻字是什么？”的演示答案为 `LF-2048`。种子脚本重复运行不会重复创建物品。

### 让同一局域网的电脑访问

默认网页只绑定本机 `127.0.0.1:5173`。在本机的 `.env` 中添加或修改 `LF_WEB_BIND_ADDRESS` 为当前 Wi-Fi 的 IPv4 地址，然后使用局域网叠加配置重新创建网页容器：

```dotenv
LF_WEB_BIND_ADDRESS=192.168.x.x
```

```bash
docker compose -f compose.yml -f compose.lan.yml up -d --force-recreate web
```

其他电脑连接同一局域网后，打开 `http://192.168.x.x:5173`，把地址替换为本机实际 IP；本机仍可使用 `http://localhost:5173`。前端的 `/api` 请求由 Nginx 转发到 Compose 内部的工作流服务，因此只需开放网页端口 5173；数据库、Eureka 和工作流直连端口仍保持本机访问。若无法连接，检查两台电脑是否能互访、macOS 防火墙或路由器的客户端隔离设置。演示账号可操作管理流程，仅在可信局域网中开放此地址。恢复仅本机访问时运行 `docker compose up -d --force-recreate web`。

需要持续使用局域网模式时，以 `docker compose -f compose.yml -f compose.lan.yml up -d` 启动；如果 Wi-Fi 地址变化，先更新 `.env` 中的 `LF_WEB_BIND_ADDRESS`。

`scripts/init_env.py` 生成仅用于本地运行的随机数据库密码、服务间令牌和 AES 密钥到 `.env`。不要提交 `.env`。`db/01-init.sh` 首次建立五个 schema 和各自数据库账号；业务表在首次启动时由 JPA 创建，各模块的 `sql/schema.sql` 记录表结构。

## 演示账号

| 账号 | 密码 | 角色 |
| --- | --- | --- |
| `student` | `student123` | 认领人 |
| `admin1` | `admin123` | 管理员 |
| `admin2` | `admin123` | 第二位管理员 |
| `staff` | `staff123` | 交接工作人员 |

这些账号仅供本地演示。四个业务服务只在 Compose 内部网络开放；浏览器通过 Web 页面访问流程服务。

## 模块

| 模块 | 职责 | 数据库 schema |
| --- | --- | --- |
| `discovery-server` | Eureka 注册中心和实例发现 | 无 |
| `item-service` | 公开物品信息、搜索、条件式状态迁移 | `item_db` |
| `claim-service` | 认领申请及状态历史 | `claim_db` |
| `verification-service` | 隐藏线索、答案比对、审核记录 | `verification_db` |
| `handover-service` | 一次性领取码、核销、交接和移交 | `handover_db` |
| `workflow-service` | Flowable 流程、服务目录、权限与 API | `workflow_db` |
| `web` | Vue 3 用户与管理员页面 | 无 |

服务启动时向 Eureka 注册。工作流服务首次启动把四个已知业务服务写入 MySQL 服务目录；管理员可在管理页查看在线实例、暂停、重新启用或重新注册。目录只接受预设的服务名和操作。流程任务调用服务前检查目录与 Eureka 在线实例。物品公开接口只返回核验问题，不返回答案。认领结论由管理员作出，自动答案比对只作辅助。

```text
浏览器 → Nginx/Vue → workflow-service（Flowable、权限、服务目录）
                             ├→ Eureka → item-service         → item_db
                             ├→ Eureka → claim-service        → claim_db
                             ├→ Eureka → verification-service → verification_db
                             └→ Eureka → handover-service     → handover_db
                       Flowable 流程实例与部署记录 → workflow_db
```

## 已部署流程与动态部署

- `claim_standard` v1：提交认领、线索核验、管理员审核、生成领取码、现场交接。
- `claim_dispute` v1：多人争认时冻结交接、人工裁决、确定唯一归属或重新开放。
- `unclaimed_disposal` v1：到期且无有效申请的物品审核、登记移交并处置。

管理页可以在运行时上传 BPMN 文件。项目提供 `workflow-service/src/main/resources/demo-v2/claim-standard-v2.bpmn20.xml`：管理员点击“部署贵重物品双人复核”后，新发起的贵重物品认领增加第二位不同管理员的审核；正在运行的 v1 实例仍按 v1 完成。上传时校验流程节点和允许调用的服务操作。

普通认领先将申请答案交给核验服务加密保存并计算初核结果，再进入管理员待办；审核通过后预留物品并生成领取码，交接人员凭失主出示的码完成核销。两份有效申请出现冲突时，争议流程冻结物品、撤销原领取码、由管理员裁决唯一获准申请。无人认领处置要求拾得至少 30 天、没有有效申请，并由不同于发起人的管理员审核。

## 状态与安全约束

物品通过条件更新执行 `PUBLISHED → RESERVED → HANDING_OVER → HANDED_OVER`；争议期间进入 `DISPUTED`，无人认领处置进入 `DISPOSED`。业务写接口使用 `operationId` 防重复；领取码限时、限制错误次数并只能核销一次。隐藏线索与认领答案在核验服务中用 AES-GCM 加密，公开 DTO 不包含答案。内部接口要求 `X-Internal-Token`，外部 API 根据登录角色限制操作。

## 构建与检查

Docker Compose 构建每个 Java 模块和 Web 页面。若本机已安装 Java 21 与 Maven，也可在项目根目录执行：

```bash
mvn -B test
```

联调时先检查 `docker compose ps` 中容器状态，再执行：

```bash
python3 scripts/smoke.py
```

脚本验证四个服务已注册、三条流程已部署、普通认领与一次性码、争议冻结与裁决、30 天处置限制，以及动态部署 v2 后的双人复核。它会创建测试记录并部署一个新版流程；可重复运行。如果要清空**本项目**的测试数据库并回到初始三条 v1 流程，运行 `docker compose down -v`，然后 `docker compose up -d`。这会删除本项目 MySQL 数据卷中的全部数据。

## 主要 API

| 用途 | 接口 |
| --- | --- |
| 登录 | `POST /api/auth/login` |
| 查找与登记物品 | `GET /api/items`、`GET /api/items/{id}`、`POST /api/items` |
| 申请与证据 | `POST /api/claims`、`GET /api/claims`、`GET /api/admin/claims/{id}/evidence` |
| 待办与交接 | `GET /api/tasks`、`POST /api/tasks/{id}/complete`、`GET /api/pickup/{claimId}/code`、`POST /api/pickup/{claimId}/redeem` |
| 特殊流程 | `POST /api/disputes`、`POST /api/disposals` |
| 服务管理 | `GET /api/admin/services`、`POST /api/admin/services/register`、`PATCH /api/admin/services/{name}` |
| 动态部署 | `GET /api/admin/process-definitions`、`POST /api/admin/process-definitions/upload`、`POST /api/admin/process-definitions/demo-v2` |

这是本地教学演示系统：账号和密码是固定演示值，登录令牌保存在工作流服务内存中。实际部署前需接入真实身份系统、调整密钥管理与数据库迁移方式，并配置 HTTPS。
