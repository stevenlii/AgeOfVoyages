# AgeOfVoyages（纵横四海）

多人文字跑商轻网游。Spring Boot 后端（含 WebSocket/STOMP）+ Vue3 前端。

## 目录结构

```
AgeOfVoyages/
├── backend/       Spring Boot 3 后端（端口 8080，MyBatis + MySQL + WebSocket）
├── frontend/      Vue3 + Vite 前端（dev 端口 5173）
├── deploy/        部署脚本与离线打包
│   ├── bin/
│   │   ├── build.sh    【本机】构建 + 启动 + 打包离线部署
│   │   └── deploy.sh   【离线机】启动（无需 Maven）
│   ├── conf/       配置（deploy.properties / router.properties）
│   └── config/     外部配置 yml（env.yml 为密钥，不随包分发）
└── 纵横四海游戏.md   游戏设计文档
```

## 快速开始（本机）

```bash
./deploy/bin/build.sh start     # 构建前端 + 后端并启动（Web UI http://127.0.0.1:8080/）
./deploy/bin/build.sh status
./deploy/bin/build.sh stop
```

## 离线部署

```bash
./deploy/bin/build.sh deploy    # 生成本机离线包 deploy/release/deploy-<日期>.tar.gz
# 把包拷到离线机（只需 JDK 21+）
tar -xzvf deploy-<日期>.tar.gz && cd deploy-<日期> && ./bin/deploy.sh start
```

详见 [deploy/部署脚本使用说明.md](deploy/部署脚本使用说明.md)

## 数据库

- MySQL，库名 `age_of_voyages`（自动建库，表结构见 `backend/schemas/schema.sql`）
- 默认连接 `localhost:3306`，账号 `root/REMOVED`，可用 `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` 环境变量覆盖
