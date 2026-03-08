# ── Build Stage ──
FROM gradle:8.7-jdk17 AS build
WORKDIR /app
COPY build.gradle settings.gradle ./
COPY gradle ./gradle
COPY src ./src
RUN gradle bootJar --no-daemon -x test

# ── Runtime Stage ──
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

COPY --from=build /app/build/libs/autofix-agent-*.jar app.jar

EXPOSE 8095

ENTRYPOINT ["java", "-jar", "app.jar"]
