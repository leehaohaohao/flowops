FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml mvnw ./
COPY .mvn .mvn
COPY src src
RUN chmod +x mvnw && ./mvnw package -DskipTests -q

FROM openjdk:17-jdk-slim
WORKDIR /app
RUN mkdir -p /data/flowops/services /data/flowops/logs

COPY --from=build /app/target/flowops-*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
