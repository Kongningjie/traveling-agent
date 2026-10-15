# 先在宿主机执行 Maven verify，再构建运行镜像；M0 容器验证后记录镜像 digest。
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN groupadd --system travel && useradd --system --gid travel travel \
    && mkdir -p /var/lib/traveling-agent && chown travel:travel /var/lib/traveling-agent
COPY bootstrap/target/traveling-agent.jar app.jar
USER travel:travel
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
