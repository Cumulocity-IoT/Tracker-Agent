# Use OpenJDK 26
FROM eclipse-temurin:26-jdk

# Install system tools + Python + Maven
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
    maven \
    && rm -rf /var/lib/apt/lists/*

# Create non-root user
RUN useradd -ms /bin/bash vscode && \
    echo "vscode ALL=(ALL) NOPASSWD:ALL" >> /etc/sudoers

USER vscode
WORKDIR /workspace

# Verify installs
RUN java -version && mvn -version && python3 --version && python3 -m pip --version