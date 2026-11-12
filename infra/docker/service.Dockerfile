# P19 (ADR-0027): one image per service from its Spring Boot jar. Distroless Java 25 (no shell, no package
# manager), non-root (uid 65532), read-only root filesystem friendly (/tmp is the only write; mount an emptyDir).
#   docker build -f infra/docker/service.Dockerfile -t dexter/<svc>:<version> services/<svc>/target
FROM gcr.io/distroless/java25-debian13:nonroot
WORKDIR /app
COPY app.jar /app/app.jar
USER nonroot
ENTRYPOINT ["/usr/bin/java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
