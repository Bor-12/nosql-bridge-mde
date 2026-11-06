FROM maven:3.9.16-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320 AS build
WORKDIR /build

COPY pom.xml ./
COPY bridge-common/pom.xml bridge-common/pom.xml
COPY bridge-mongodb/pom.xml bridge-mongodb/pom.xml
COPY bridge-dynamodb/pom.xml bridge-dynamodb/pom.xml
COPY bridge-sqs/pom.xml bridge-sqs/pom.xml
COPY bridge-worker/pom.xml bridge-worker/pom.xml
COPY bridge-orchestrator/pom.xml bridge-orchestrator/pom.xml
COPY bridge-common/src/main bridge-common/src/main
COPY bridge-mongodb/src/main bridge-mongodb/src/main
COPY bridge-sqs/src/main bridge-sqs/src/main
COPY bridge-dynamodb/src/main bridge-dynamodb/src/main
COPY bridge-worker/src/main bridge-worker/src/main
RUN --mount=type=cache,target=/root/.m2 mvn --batch-mode --no-transfer-progress -pl bridge-worker -am -Dmaven.test.skip=true package

FROM eclipse-temurin:21.0.13_9-jre-alpine-3.22@sha256:3861a9a2208d5da324c57b0cff4ebc3f9603b6a03841235c77f1221bc9c3c462
RUN addgroup -g 10001 bridge && adduser -D -u 10001 -G bridge bridge
WORKDIR /app
COPY --from=build --chown=10001:10001 /build/bridge-worker/target/bridge-worker.jar /app/bridge-worker.jar
USER 10001:10001
STOPSIGNAL SIGTERM
ENTRYPOINT ["java", "-jar", "/app/bridge-worker.jar"]
