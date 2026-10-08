# Build the jar with Maven and a full JDK.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Resolve dependencies first so this layer is cached until pom.xml changes.
COPY pom.xml ./
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests

# Split the fat jar so its dependencies land in their own image layer.
FROM eclipse-temurin:21-jre-alpine AS extract
WORKDIR /extract
COPY --from=build /build/target/*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --destination /extracted

# Runtime image: JRE only, no Maven, no build sources.
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S spring && adduser -S spring -G spring

# Dependencies change rarely, the application jar changes every build.
COPY --from=extract /extracted/lib ./lib
COPY --from=extract /extracted/app.jar ./app.jar

USER spring
EXPOSE 8080

# A container is always a deployed environment, so it runs the prod profile:
# the database comes from the DB_* variables, and startup fails if any is
# missing. Running from source (mvn spring-boot:run) is unaffected and stays
# on the local profile. Override with -e SPRING_PROFILES_ACTIVE=... if ever needed.
ENV SPRING_PROFILES_ACTIVE=prod
ENTRYPOINT ["java", "-jar", "app.jar"]
