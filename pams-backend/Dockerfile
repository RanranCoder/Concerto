# ---- 构建阶段：Maven 打包 ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

# ---- 运行阶段：JRE 21 ----
FROM eclipse-temurin:21-jre AS word-runtime
RUN apt-get update \
    && apt-get install -y --no-install-recommends libreoffice-writer poppler-utils fonts-noto-cjk \
    && rm -rf /var/lib/apt/lists/*
ENV TZ=Asia/Shanghai \
    PAMS_AGENT_WORD_RENDERER=/usr/bin/soffice \
    PAMS_AGENT_WORD_PDF_TEXT=/usr/bin/pdftotext

FROM word-runtime AS runtime
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
