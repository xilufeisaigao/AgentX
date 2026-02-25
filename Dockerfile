FROM maven:3.9.11-eclipse-temurin-21 AS builder

WORKDIR /build

COPY pom.xml ./
COPY src ./src

RUN mvn -q -DskipTests package

FROM maven:3.9.11-eclipse-temurin-21

RUN apt-get update \
    && apt-get install -y --no-install-recommends git python3 python3-venv python3-pip wget \
    && ln -sf /usr/bin/python3 /usr/local/bin/python \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=builder /build/target/agentx-backend-*.jar /app/agentx-backend.jar
COPY docker/entrypoint.sh /app/entrypoint.sh

RUN chmod +x /app/entrypoint.sh \
    && mkdir -p /agentx/repo /agentx/runtime-data

EXPOSE 8080

ENTRYPOINT ["/app/entrypoint.sh"]
