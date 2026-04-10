# Use OpenJDK 26
FROM eclipse-temurin:26-jdk

# Install system tools + Python 3
RUN apt-get update && apt-get install -y \
    curl \
    git \
    unzip \
    sudo \
    python3 \
    python3-pip \
    python3-venv \
    python3-dev \
    build-essential \
    && rm -rf /var/lib/apt/lists/*

# Install Maven
ARG MAVEN_VERSION=3.9.9
RUN curl -fsSL https://downloads.apache.org/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz \
    | tar -xz -C /opt \
    && ln -s /opt/apache-maven-${MAVEN_VERSION}/bin/mvn /usr/bin/mvn

ENV MAVEN_HOME=/opt/apache-maven-${MAVEN_VERSION}
ENV PATH=$MAVEN_HOME/bin:$PATH
ENV JAVA_HOME=/opt/java/openjdk

# Create non-root user (recommended for dev containers)
RUN useradd -ms /bin/bash vscode && \
    echo "vscode ALL=(ALL) NOPASSWD:ALL" >> /etc/sudoers

USER vscode
WORKDIR /workspace

# Verify installs
RUN java -version && mvn -version && python3 --version && pip3 --version