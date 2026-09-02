# syntax=docker/dockerfile:1
# ══════════════════════════════════════════════════════════════════
#  payment-api — 多階段建置
#
#  階段 1 (build)   : Maven + JDK 21，產生 fat jar
#  階段 2 (runtime) : 只有 JRE。建置工具不會進到最終 image。
# ══════════════════════════════════════════════════════════════════

# ── 階段 1：建置 ──────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

# 先只複製 pom 檔，單獨跑一次相依解析。
# 這一層的 cache key 只跟 pom 有關 —— 只要 pom 沒動，
# 之後改 Java 原始碼都不會重新下載 Maven 相依。
COPY pom.xml .
COPY payment-api/pom.xml               payment-api/
COPY trading-engine-simulator/pom.xml  trading-engine-simulator/
RUN mvn -q -pl payment-api -am dependency:go-offline -DskipTests

# 原始碼放在相依之後複製 —— 這一層改動頻繁，放越後面越好
COPY payment-api/src payment-api/src
RUN mvn -q -pl payment-api -am package -DskipTests

# ── 階段 2：執行 ──────────────────────────────────────────────────
# jre 而非 jdk：不需要編譯器就不要帶編譯器（爆炸半徑 + image 大小）
FROM eclipse-temurin:21-jre-jammy AS runtime

# HEALTHCHECK 需要 curl。--no-install-recommends 加上清掉 apt 快取，
# 是為了不讓這一層留下沒用的東西。
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# 建立非 root 使用者。K8s 常設 runAsNonRoot: true，
# 那時候 image 裡沒有非 root user 會直接起不來。
RUN useradd --system --uid 10001 --no-create-home appuser

WORKDIR /app
# --from=build：只從建置階段撈出那一個 jar，其他全部丟掉
COPY --from=build /src/payment-api/target/payment-api-qa-framework-1.0.0.jar app.jar
RUN chown -R appuser:appuser /app
USER appuser

ENV PAYMENT_PORT=8091 \
    PAYMENT_REPO=memory \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

# EXPOSE 不會真的開 port，它是「文件」—— 告訴讀的人這個容器聽哪裡
EXPOSE 8091

# ⚠️ 注意這個 healthcheck：它只驗證「HTTP 有回應」。
#    你 2026-07 的 GC 死亡螺旋事故中，這種檢查會全程通過。
#    Day 4 我們會把它換成「工作有沒有在前進」的檢查。
HEALTHCHECK --interval=15s --timeout=3s --start-period=20s --retries=3 \
  CMD curl -sf http://localhost:${PAYMENT_PORT}/api/v1/health || exit 1

# exec form（JSON 陣列）而不是 shell form：
# 這樣 java 會是 PID 1，能直接收到 SIGTERM。
# 用 shell form 的話 sh 是 PID 1，訊號傳不下去，容器停止會等到 timeout 被 SIGKILL。
# —— 這正是你第二次事故（SIGTERM/exit 143）的同一個主題。
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
