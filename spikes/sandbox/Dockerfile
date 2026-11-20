# DRAFT runner image for the WO-S.3 sandbox spike. WO-X.4 turns this into the pinned image
# (sandbox-images/runner/Dockerfile): pin both base images by digest, pin and verify the JUnit jar by a sha256 kept
# in the repo, and add an SBOM. Build: docker build -t secpipe-runner:spike spikes/sandbox
#
# Contents: Temurin JDK 21, Maven 3.9.x (copied from the official Maven image, so no MAVEN_CONFIG or entrypoint
# side effects), junit-platform-console-standalone (runs generated JUnit 5 tests without editing target POMs), curl
# (used only by the network probe), and a non-root user 10001:10001 whose HOME is a tmpfs at run time.

ARG MAVEN_IMAGE=maven:3.9.11-eclipse-temurin-21
ARG JDK_IMAGE=eclipse-temurin:21-jdk-noble

FROM ${MAVEN_IMAGE} AS maven

FROM ${JDK_IMAGE}

ARG JUNIT_CONSOLE_VERSION=1.13.4
ARG CENTRAL=https://repo.maven.apache.org/maven2

# Debian/Ubuntu packages: curl for the probe, nothing else. Keep the image small; no compilers beyond the JDK.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

COPY --from=maven /usr/share/maven /opt/maven
RUN ln -s /opt/maven/bin/mvn /usr/local/bin/mvn

# JUnit console launcher. The .sha1 comes from the same origin, so this only catches corruption; WO-X.4 pins a sha256.
RUN set -eu; \
    jar="junit-platform-console-standalone-${JUNIT_CONSOLE_VERSION}.jar"; \
    url="${CENTRAL}/org/junit/platform/junit-platform-console-standalone/${JUNIT_CONSOLE_VERSION}/${jar}"; \
    mkdir -p /opt/junit; \
    curl -fsSL -o "/opt/junit/${jar}" "${url}"; \
    echo "$(curl -fsSL "${url}.sha1")  /opt/junit/${jar}" | sha1sum -c -; \
    ln -s "/opt/junit/${jar}" /opt/junit/junit-platform-console-standalone.jar

# Non-root user. The sandbox always passes --user 10001:10001 explicitly; this entry only gives the uid a name and
# a HOME path. /home/sandbox, /scratch and /cache/m2 are mount points (tmpfs, rw bind, ro bind) at run time.
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
