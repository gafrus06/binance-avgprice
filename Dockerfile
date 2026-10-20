FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests package

FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app app && mkdir /data && chown app:app /data
WORKDIR /app
COPY --from=build /build/target/binance-avgprice-1.0.0.jar app.jar
USER app
EXPOSE 8080
ENV BINANCE_STATE_DIR=/data
ENTRYPOINT ["java", "-jar", "app.jar"]
