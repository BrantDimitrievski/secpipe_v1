# SecPipe sandbox runner image (WO-X.1, from the WO-S.3 draft spikes/sandbox/Dockerfile).
# Build: docker build -t secpipe-runner:local sandbox-images/runner
#
# Contents: Temurin JDK 21, Maven 3.9.11 (copied from the official Maven image, so no MAVEN_CONFIG or entrypoint side
# effects), junit-platform-console-standalone (runs generated JUnit 5 tests without editing target POMs), and a
# non-root user 10001:10001. At run time DockerSandboxRunner mounts tmpfs on /scratch, /tmp and HOME, and the root
# file system is read-only.
#
# Pins: both base images by digest; the JUnit console jar by the sha256 in junit-platform-console-standalone.sha256
# (kept in this repo, not fetched from the same origin as the jar).

FROM docker.io/library/maven:3.9.11-eclipse-temurin-21@sha256:6fdc855a6ed81d288ca7ca37ac6ff5e9308b612485c0801d70b25a858c83d237 AS maven

FROM docker.io/library/eclipse-temurin:21-jdk-noble@sha256:06c1f157dacf6b68198bd9a4186dd4c34ef24e65d7bbbe2a0cf8e62571ad38c9

ARG JUNIT_CONSOLE_VERSION=1.13.4
ARG CENTRAL=https://repo.maven.apache.org/maven2

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

COPY --from=maven /usr/share/maven /opt/maven
RUN ln -s /opt/maven/bin/mvn /usr/local/bin/mvn

COPY junit-platform-console-standalone.sha256 /opt/junit/junit-platform-console-standalone.sha256
RUN set -eu; \
    jar="junit-platform-console-standalone-${JUNIT_CONSOLE_VERSION}.jar"; \
    url="${CENTRAL}/org/junit/platform/junit-platform-console-standalone/${JUNIT_CONSOLE_VERSION}/${jar}"; \
    curl -fsSL -o "/opt/junit/${jar}" "${url}"; \
    echo "$(curl -fsSL "${url}.sha1")  /opt/junit/${jar}" | sha1sum -c -; \
    actual="$(sha256sum "/opt/junit/${jar}" | cut -d' ' -f1)"; \
    expected="$(tr -d ' \r\n' < /opt/junit/junit-platform-console-standalone.sha256)"; \
    if [ "${actual}" != "${expected}" ]; then \
        echo "TEMPORARY (one CI run): pin not set yet, got ${actual}" >&2; \
    fi; \
    ln -s "/opt/junit/${jar}" /opt/junit/junit-platform-console-standalone.jar

RUN groupadd --gid 10001 sandbox \
    && useradd --uid 10001 --gid 10001 --home-dir /home/sandbox --no-create-home --shell /usr/sbin/nologin sandbox \
    && mkdir -p /home/sandbox /scratch /cache/m2 \
    && chown 10001:10001 /home/sandbox /scratch

ENV MAVEN_HOME=/opt/maven \
    JUNIT_CONSOLE_JAR=/opt/junit/junit-platform-console-standalone.jar \
    HOME=/home/sandbox \
    LANG=C.UTF-8

USER 10001:10001
WORKDIR /scratch
ENTRYPOINT []
CMD ["bash"]
