# ---- 构建阶段 ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# 先复制 pom 下载依赖（利用 Docker 层缓存）
COPY pom.xml .
RUN mvn dependency:go-offline -B

# 复制源码并打包
COPY src ./src
RUN mvn clean package -DskipTests -B

# ---- 运行阶段 ----
FROM eclipse-temurin:17-jre
WORKDIR /app

# 数据目录（SQLite + 媒体文件），建议挂载卷持久化
ENV TOONFLOW_DATA_DIR=/data
VOLUME ["/data"]

COPY --from=build /build/target/toonflow-*.jar app.jar

EXPOSE 10588

# 通过环境变量注入 API Key 和数据目录
ENTRYPOINT ["java", \
  "-Dtoonflow.data-dir=${TOONFLOW_DATA_DIR}", \
  "-jar", "app.jar"]
