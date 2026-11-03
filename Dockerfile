# 1) Build del frontend React
FROM node:22-alpine AS web
WORKDIR /web
COPY frontend/package.json ./
RUN npm install --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

# 2) Build del backend Java (JDBC, API)
FROM maven:3.9-eclipse-temurin-21 AS api
WORKDIR /api
COPY backend/pom.xml ./
RUN mvn -q -B dependency:go-offline
COPY backend/src ./src
RUN mvn -q -B package

# 3) Runtime
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=api /api/target/monitor.jar /app/monitor.jar
COPY --from=web /web/dist /app/static
ENV STATIC_DIR=/app/static \
    PORT=8080
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/monitor.jar"]
