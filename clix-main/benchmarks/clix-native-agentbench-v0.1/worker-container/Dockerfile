FROM eclipse-temurin:21-jre-alpine@sha256:326837fba06a8ff5482a17bafbd65319e64a6e997febb7c85ebe7e3f73c12b11

LABEL org.opencontainers.image.title="clix Native AgentBench Candidate Worker"
LABEL org.opencontainers.image.description="Networkless runtime for a read-only mounted clix Candidate jar"
LABEL com.clix.agentbench.role="candidate-worker"
LABEL com.clix.agentbench.scope="relay-worker-only"

USER root
RUN addgroup -S -g 10002 clixworker \
    && adduser -S -D -H -u 10002 -G clixworker -h /home/clix -s /sbin/nologin clixworker \
    && mkdir -p /workspace /home/clix /opt/clix \
    && chown 10002:10002 /workspace /home/clix \
    && chmod 0555 /opt/clix

ENV HOME=/home/clix \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8

WORKDIR /workspace
USER clixworker:clixworker

ENTRYPOINT ["java"]
CMD ["-version"]
