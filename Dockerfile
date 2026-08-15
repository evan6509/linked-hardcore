# syntax=docker/dockerfile:1
#
# Linked Hardcore — single image that runs EITHER the Velocity proxy or a Fabric
# backend server. The entrypoint decides via MODE=proxy|server.
#
# Stages:
#   1. build          — compiles the Fabric mod and the Velocity plugin with gradle.
#   2. server-install — runs the Fabric installer for MC 26.2 / loader 0.19.3,
#                       downloads the vanilla server jar, and drops in the mod jars.
#   3. proxy-install  — downloads the Velocity 4.0.0 proxy jar.
#   4. runtime        — combines the above, plus the entrypoint and jq.

# ---- Stage 1: gradle build -----------------------------------------------
FROM eclipse-temurin:25-jdk AS build

WORKDIR /src
COPY . .

# JAVA_HOME is set in the temurin image; gradlew picks it up.
RUN ./gradlew --no-daemon build

RUN mkdir -p /out \
    && cp fabric-mod/build/libs/fabric-mod-0.1.0.jar /out/fabric-mod-0.1.0.jar \
    && cp velocity-plugin/build/libs/velocity-plugin-0.1.0.jar /out/velocity-plugin-0.1.0.jar

# ---- Stage 2: Fabric server install ----------------------------------------
FROM eclipse-temurin:25-jdk AS server-install

ARG FABRIC_INSTALLER_VERSION=1.1.2
ARG MINECRAFT_VERSION=26.2
ARG FABRIC_LOADER_VERSION=0.19.3
ARG FABRIC_API_VERSION=0.156.0+26.2

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

RUN mkdir -p /install/mods \
    && curl -fsSL -o /install/fabric-installer.jar \
       "https://maven.fabricmc.net/net/fabricmc/fabric-installer/${FABRIC_INSTALLER_VERSION}/fabric-installer-${FABRIC_INSTALLER_VERSION}.jar" \
    && java -jar /install/fabric-installer.jar server \
       -mcversion "${MINECRAFT_VERSION}" -loader "${FABRIC_LOADER_VERSION}" -dir /install -downloadMinecraft \
    && rm /install/fabric-installer.jar

RUN curl -fsSL -o "/install/mods/fabric-api-${FABRIC_API_VERSION}.jar" \
       "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/${FABRIC_API_VERSION}/fabric-api-${FABRIC_API_VERSION}.jar"

COPY --from=build /out/fabric-mod-0.1.0.jar /install/mods/fabric-mod-0.1.0.jar

# ---- Stage 3: Velocity proxy install ---------------------------------------
FROM eclipse-temurin:25-jdk AS proxy-install

# velocity-4.0.0-6 (STABLE), pinned by content address (sha256 is part of the URL).
ARG VELOCITY_SHA256=4540289f48c83e305fc2f2c495a84d1f4d0b7f360830251e169dd5a208740e70
ARG VELOCITY_URL=https://fill-data.papermc.io/v1/objects/4540289f48c83e305fc2f2c495a84d1f4d0b7f360830251e169dd5a208740e70/velocity-4.0.0-6.jar

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

RUN curl -fsSL -o /velocity.jar "${VELOCITY_URL}" \
    && echo "${VELOCITY_SHA256}  /velocity.jar" | sha256sum -c -

# ---- Stage 4: runtime -------------------------------------------------------
FROM eclipse-temurin:25-jdk AS runtime

# jq: robust, schema-preserving templating of the mod's config.json. Everything
# else (sed, grep, coreutils, bash) ships with the Ubuntu base image.
RUN apt-get update \
    && apt-get install -y --no-install-recommends jq \
    && rm -rf /var/lib/apt/lists/*

COPY --from=server-install /install /opt/linkedhardcore/server
COPY --from=proxy-install /velocity.jar /opt/linkedhardcore/proxy/velocity.jar
COPY --from=build /out/velocity-plugin-0.1.0.jar /opt/linkedhardcore/velocity-plugin-0.1.0.jar
COPY docker/entrypoint.sh /opt/linkedhardcore/entrypoint.sh

RUN chmod +x /opt/linkedhardcore/entrypoint.sh

# Defaults (all overridable via environment).
ENV MODE=server \
    SERVER_ID=a \
    FORWARDING_SECRET=linkedhardcore-dev-secret-change-me \
    MC_MEMORY=3G \
    TRANSFER_COUNTDOWN_SECONDS=5 \
    VELOCITY_PORT=25577

WORKDIR /opt/linkedhardcore
ENTRYPOINT ["/opt/linkedhardcore/entrypoint.sh"]
