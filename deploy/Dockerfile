FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY customers-core/pom.xml customers-core/pom.xml
COPY customers-adapters/pom.xml customers-adapters/pom.xml
COPY customers-app/pom.xml customers-app/pom.xml
RUN mvn -B -q -pl customers-core,customers-adapters,customers-app dependency:go-offline
COPY . .
RUN mvn -B -q -pl customers-app -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine@sha256:ac05c3c42b7c2f1a52c9bd52f01041fa1958231b15ebeae7420429cbc5baf474
RUN apk upgrade --no-cache && apk del gnupg
RUN addgroup -S synkro && adduser -S synkro -G synkro
COPY --from=build /src/customers-app/target/customers-app-*.jar /app/customers-app.jar
USER synkro
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/customers-app.jar"]
