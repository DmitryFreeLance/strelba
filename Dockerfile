FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /opt/bot
RUN mkdir -p /opt/bot/data
COPY --from=build /app/target/training-diary-bot-1.0.0.jar /opt/bot/bot.jar
ENV SQLITE_PATH=/opt/bot/data/bot.db
CMD ["java", "-jar", "/opt/bot/bot.jar"]
