FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY customers-core/pom.xml customers-core/pom.xml
COPY customers-adapters/pom.xml customers-adapters/pom.xml
COPY customers-app/pom.xml customers-app/pom.xml
RUN mvn -B -q -pl customers-core,customers-adapters,customers-app dependency:go-offline || true
COPY . .
RUN mvn -B -q -pl customers-app -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S synkro && adduser -S synkro -G synkro
COPY --from=build /src/customers-app/target/customers-app-*.jar /app/customers-app.jar
USER synkro
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/customers-app.jar"]
