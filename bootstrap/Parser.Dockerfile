FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY bootstrap/target/traveling-agent.jar app.jar
USER 65532:65532
ENTRYPOINT ["java", "-Dlogback.configurationFile=parser-logback.xml", "-Xmx256m", "-XX:MaxDirectMemorySize=64m", "-XX:ActiveProcessorCount=1", "-jar", "/app/app.jar"]
