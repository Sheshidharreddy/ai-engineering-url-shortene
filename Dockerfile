FROM maven:3.9.11-eclipse-temurin-21-alpine AS build
WORKDIR /workspace

COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline

COPY src ./src
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S app && adduser -S app -G app
COPY --from=build /workspace/target/ai-engineering-url-shortener-*.jar app.jar

USER app:app
EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=3s --start-period=30s --retries=5 \
  CMD wget -q -O - http://localhost:8080/internal/actuator/health/readiness || exit 1

STOPSIGNAL SIGTERM
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
