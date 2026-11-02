# Build stage
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

# Run stage. The AI key comes from the host's environment variables, never from the image.
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/truerate-*.jar app.jar
ENV LANG=C.UTF-8
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-Dfile.encoding=UTF-8", "-jar", "app.jar"]
