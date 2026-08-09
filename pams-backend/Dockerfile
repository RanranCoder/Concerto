# ---- 构建阶段：Maven 打包 ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
# 国内构建加速：阿里云 Maven 镜像
COPY .m2-settings.xml /root/.m2/settings.xml
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

# ---- 运行阶段：JRE 21 ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
ENV TZ=Asia/Shanghai
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
