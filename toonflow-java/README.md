# Toonflow Java

Toonflow 的 Java 重构版本，使用 Spring Boot 3 + Spring AI Alibaba + MyBatis-Plus 实现。

## 技术栈

| 组件 | 版本 |
|------|------|
| Java | 17 |
| Spring Boot | 3.5.6 |
| Spring AI | 1.1.2 |
| Spring AI Alibaba | 1.1.2.0 |
| MyBatis-Plus | 3.5.9 |
| SQLite | 3.46.1.3 |
| Spring Security + JWT | - |
| Spring WebSocket (STOMP) | - |

## 项目结构

```
src/main/java/com/toonflow/
├── ToonflowApplication.java        # 启动类
├── config/
│   ├── AiConfig.java               # Spring AI 配置
│   ├── DbInitConfig.java           # 数据库初始化（建表 + 默认数据）
│   ├── JwtAuthFilter.java          # JWT 鉴权过滤器
│   ├── MyBatisPlusConfig.java      # MyBatis-Plus 配置
│   ├── SecurityConfig.java         # Spring Security 配置
│   ├── StaticResourceConfig.java   # 静态资源目录配置
│   └── WebSocketConfig.java        # WebSocket STOMP 配置
├── common/
│   ├── exception/
│   │   ├── BusinessException.java
│   │   └── GlobalExceptionHandler.java
│   └── result/R.java               # 统一响应格式
├── entity/                         # 28 个实体类（对应所有数据库表）
├── mapper/                         # MyBatis-Plus Mapper 接口
├── controller/
│   ├── LoginController.java        # 登录
│   ├── ProjectController.java      # 项目管理
│   ├── NovelController.java        # 原文管理
│   ├── ScriptController.java       # 剧本管理
│   ├── AssetsController.java       # 素材管理
│   ├── StoryboardController.java   # 分镜管理
│   ├── ProductionController.java   # 制作工作台（视频轨道、图片流程）
│   ├── ArtStyleController.java     # 风格管理
│   ├── SettingController.java      # 设置（供应商、Agent、提示词）
│   ├── ModelSelectController.java  # 模型选择
│   ├── AgentController.java        # Agent API + SSE 流式输出
│   ├── GeneralController.java      # 统计、版本等通用接口
│   └── FileController.java         # 文件上传
├── ai/
│   └── AiService.java              # AI 服务（多供应商动态路由）
├── util/
│   └── JwtUtil.java                # JWT 工具
└── websocket/
    └── AgentWebSocketHandler.java  # WebSocket Agent 消息处理
```

## 快速开始

### 环境要求
- JDK 17+
- Maven 3.8+

### 配置

在 `src/main/resources/application.yml` 中配置：

```yaml
spring:
  ai:
    dashscope:
      api-key: YOUR_DASHSCOPE_API_KEY  # 阿里云 DashScope Key

toonflow:
  data-dir: /path/to/data   # 数据目录（存储 SQLite 文件和媒体文件）
```

### 运行（本地）

```bash
export DASHSCOPE_API_KEY=你的key
mvn spring-boot:run
```

### 运行（Docker）

```bash
# 方式一：docker compose（推荐）
export DASHSCOPE_API_KEY=你的key
docker compose up -d

# 方式二：手动构建
docker build -t toonflow-java .
docker run -d -p 10588:10588 \
  -e DASHSCOPE_API_KEY=你的key \
  -v toonflow-data:/data \
  toonflow-java
```

服务启动后访问：http://localhost:10588

默认账号：`admin` / `admin123`

> 数据（SQLite + 媒体文件）默认存于 `~/.toonflow`（本地）或 `/data` 卷（Docker），
> 容器重建不丢失。

## API 说明

### 认证

除 `/api/login/login` 外，所有接口需携带 JWT Token：

```
Authorization: Bearer <token>
```

### 主要接口前缀

| 模块 | 前缀 |
|------|------|
| 登录 | /api/login |
| 项目 | /api/project |
| 原文 | /api/novel |
| 剧本 | /api/script |
| 素材 | /api/assets |
| 分镜 | /api/production/storyboard |
| 制作 | /api/production |
| 风格 | /api/artStyle |
| 设置 | /api/setting |
| Agent | /api/agents |
| 模型 | /api/modelSelect |

### WebSocket

连接地址：`ws://localhost:10588/ws`（SockJS）

发送到 `/app/agent`，接收 `/topic/agent/{sessionId}`

### SSE 流式输出

```
GET /api/agents/stream?agentType=scriptAgent&prompt=xxx
```

## AI 供应商配置

系统支持通过数据库动态配置 AI 供应商。在设置页面添加供应商后，
格式为 `vendorId:modelId`，例如：`openai:gpt-4o`、`qwen:qwen-max`。

Spring AI Alibaba (DashScope) 作为默认提供商，其他供应商通过 OpenAI 兼容接口接入。
