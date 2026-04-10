# -------- Stage 1: Build minimal JRE using jlink --------
FROM eclipse-temurin:26-jdk-alpine AS base

RUN jlink \
    --module-path $JAVA_HOME/jmods \
    --compress=2 \
    --add-modules java.se,jdk.unsupported,jdk.crypto.ec \
    --no-header-files \
    --no-man-pages \
    --output /opt/jdk-mini

# -------- Stage 2: Runtime Image --------
FROM alpine:3.20

# Install minimal required runtime libs
RUN apk add --no-cache libstdc++ tzdata

# Copy minimized JRE
COPY --from=base /opt/jdk-mini /opt/jdk-mini

ENV JAVA_HOME=/opt/jdk-mini
ENV PATH="$JAVA_HOME/bin:$PATH"

# Copy configuration
COPY etc/ /etc/tcp-agent/

# Copy application
ADD resources/* /data/

ENTRYPOINT ["java", "-jar", "/data/@package.name@.jar"]