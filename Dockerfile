# ============================================================
# PRoverlap — 多阶段 Docker 构建
# 阶段 1: Maven 编译
# 阶段 2: JRE 运行
# ============================================================

# ---- 构建阶段 ----
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app

# 阿里云 Maven 镜像（仅国内构建时需要，GitHub Actions 跳过）
COPY settings.xml /usr/share/maven/ref/
ARG MIRROR_FLAG="-s /usr/share/maven/ref/settings.xml"

# 先复制 pom 文件，利用 Docker 缓存层
COPY pom.xml .
COPY proverlap-server/pom.xml proverlap-server/
COPY .mvn .mvn
COPY mvnw mvnw.cmd ./

# 修复 Windows 换行符 + 下载依赖（缓存层：源码未变则复用）
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw \
    && bash ./mvnw dependency:go-offline -pl proverlap-server ${MIRROR_FLAG} -q

# 复制源码并编译
COPY proverlap-server/src proverlap-server/src
RUN bash ./mvnw package -DskipTests -pl proverlap-server ${MIRROR_FLAG} -q

# ---- 运行阶段 ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S proverlap && adduser -S proverlap -G proverlap
COPY --from=build /app/proverlap-server/target/*.jar app.jar

USER proverlap
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
