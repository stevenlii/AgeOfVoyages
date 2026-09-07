# 纵横四海 (AgeOfVoyages) 项目长期备忘

## 技术栈（已定）
- 后端：**Java 21 + Spring Boot 3.3.5（Maven）**，实时通信 **WebSocket/STOMP**（SockJS 端点 `/ws`）。
- 前端：**Vue 3 + Vite**，`@stomp/stompjs` + `sockjs-client` 连后端。
- 存储：**MySQL**（本地 root/REMOVED，库 `age_of_voyages`）+ **MyBatis**（2026-09-07 由 JPA 迁移而来，统一用户技术栈偏好）。实时状态在内存 Map，变更（login/trade/travel/到达）经 `PlayerMapper` 写回 `players` 表；货舱 `cargo` 由 `CargoTypeHandler` 以 JSON 文本列存取。
- `players` 表结构：`client_id` VARCHAR(64) PK / name / gold / port / cargo_cap / cargo TEXT / traveling BIT(1) / traveling_to / arrive_at BIGINT。**MyBatis 不自动建表**，建表脚本在 `backend/schemas/schema.sql`。

## 运行方式
- 后端：`backend/` 下 `mvn package` 后 `java -jar target/backend-0.1.0.jar --server.port=8080`（托管后台启动可能被分配随机端口，需显式指定 8080）。
- 前端：`frontend/` 下 `npm install` 后 `npm run dev`（5173，已配 `/ws` 代理到 8080）。

## 约定
- STOMP 目的地：`/app/{login|travel|trade|chat}` 上行；`/topic/player/{clientId}/state`、`/topic/player/{clientId}/msg`、`/topic/log` 下行。
- 多人联网 MVP：3 港口 / 3 货物，先把"跑商赚钱"闭环跑通，再叠海战/公会/强化/AI 事件。
