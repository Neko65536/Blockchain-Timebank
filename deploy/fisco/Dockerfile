FROM eclipse-temurin:11-jre-jammy
COPY --chmod=755 local/fisco-bcos /usr/local/bin/fisco-bcos
WORKDIR /data
USER 10001:10001
ENTRYPOINT ["/usr/local/bin/fisco-bcos", "-c", "/config/config.ini", "-g", "/config/config.genesis"]
