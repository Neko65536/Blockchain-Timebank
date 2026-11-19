FROM eclipse-temurin:21-jre-jammy
RUN groupadd --gid 10001 timebank && useradd --uid 10001 --gid 10001 --create-home --home-dir /home/timebank --shell /usr/sbin/nologin timebank
WORKDIR /app
COPY backend/timecoin-web/target/timecoin-web-0.0.1-SNAPSHOT.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
