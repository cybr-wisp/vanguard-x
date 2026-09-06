FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /workspace
COPY . .

RUN mvn -B -DskipTests -pl vanguard-api -am package

FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

RUN groupadd --system --gid 10001 vanguard \
    && useradd --system --uid 10001 --gid vanguard --create-home vanguard

COPY --from=build \
    /workspace/vanguard-api/target/vanguard-api-0.1.0-SNAPSHOT.jar \
    /app/vanguard-api.jar

USER 10001:10001

EXPOSE 8081
EXPOSE 5000/udp

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/vanguard-api.jar"]
