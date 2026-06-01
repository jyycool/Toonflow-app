# 编译/运行自查清单

本项目经历了 Spring Boot 3.3→3.5、Spring AI M3→1.1.2 的大版本升级，
以下是潜在风险点及验证方法。建议本地执行 `mvn clean compile` 后逐项核对。

## 一、依赖与版本

| 项 | 当前值 | 备注 |
|----|--------|------|
| Spring Boot | 3.5.6 | 需与 Spring AI 1.1.x 匹配 |
| Spring AI | 1.1.2 | `@Tool`/`ChatClient.tools()` API |
| Spring AI Alibaba | 1.1.2.0 | DashScope starter |
| MyBatis-Plus | 3.5.9 | Boot 3.5 需 3.5.9+ |
| JDK | 17 | |

⚠️ 若 Maven 报找不到 `spring-ai-alibaba-bom:1.1.2.0`，确认仓库可访问
（spring-milestones 已配置；GA 版本在 Maven Central）。

## 二、已修复的真实 Bug

1. ✅ **`o_storyboard.index` 字段映射**
   建表列名为 `idx`（规避 SQL 保留字 `index`），实体已加 `@TableField("idx")`。

2. ✅ **ChatModel Bean 注入歧义**
   DashScope + OpenAI 两个 starter 都会注册 `ChatModel`。
   已在 `application.yml` 设 `spring.ai.model.chat/embedding=dashscope`，
   OpenAI 兼容供应商改为在 `AiService` 中手动 `OpenAiApi.builder()` 构建。

## 三、需重点验证的 Spring AI 1.1.2 API

以下 API 签名在升级后可能需微调（按实际编译报错调整）：

```java
// 1. OpenAiApi 构建（AiService.buildChatModel）
OpenAiApi.builder().baseUrl(url).apiKey(key).build();

// 2. OpenAiChatModel 构建
OpenAiChatModel.builder().openAiApi(api).defaultOptions(options).build();

// 3. 工具调用（AiService.streamTextWithTools）
chatClient.prompt().messages(msgs).tools(toolObjects).stream().content();

// 4. @Tool / @ToolParam 注解
org.springframework.ai.tool.annotation.Tool
org.springframework.ai.tool.annotation.ToolParam

// 5. EmbeddingModel
float[] embed(String text);  // EmbeddingService
```

若 `OpenAiApi.builder()` 报错，1.1.x 可能改为 `OpenAiApi.builder().apiKey(new SimpleApiKey(key))`，
按 IDE 提示调整即可。

## 四、SQL 保留字列名（SQLite 容忍，一般无需处理）

`o_setting.key/value`、`o_agentDeploy.desc/key`、`o_storyboard.track/state` 等。
SQLite 在 DML 中容忍这些名字（仅 `index` 因与建表 `idx` 不一致已单独修复）。
如遇 `SQL syntax` 报错，可在对应实体字段加 `@TableField("`列名`")` 反引号包裹。

## 五、运行前准备

1. 配置 `DASHSCOPE_API_KEY` 环境变量（文本生成 + 向量化必需）
2. 首次启动自动建表 + 初始化 admin/admin123
3. 数据目录默认 `~/.toonflow`，可用 `-Dtoonflow.data-dir=/path` 覆盖

## 六、快速验证命令

```bash
# 仅编译
mvn clean compile

# 打包（跳过测试）
mvn clean package -DskipTests

# 运行
export DASHSCOPE_API_KEY=your_key
java -jar target/toonflow-1.1.7.jar
```

## 七、尚未实现/骨架部分

- 厂商真实 API 适配（通义万相图片、可灵视频等）— `MediaGenerationService` 为 OpenAI 兼容骨架
- 视频任务轮询查询接口 — `VideoGenerationService.pollVideoTask`
- 单元测试 — 未编写
