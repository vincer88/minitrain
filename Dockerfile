# syntax=docker/dockerfile:1

### Stage 1: build firmware on Ubuntu
FROM ubuntu:22.04 AS firmware

ARG DEBIAN_FRONTEND=noninteractive

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        build-essential \
        cmake \
        git \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace

COPY . /workspace

RUN cmake -S firmware -B firmware/build \
    && cmake --build firmware/build \
    && ctest --test-dir firmware/build


### Stage 2: run Android/Gradle tests using a Gradle image with JDK21
# Use a Gradle image that bundles a modern Gradle and JDK21 so it matches
# the project's kotlin jvmToolchain(21) and plugin requirements.
FROM gradle:8.6-jdk21 AS android

WORKDIR /workspace

# copy workspace from firmware stage
COPY --from=firmware /workspace /workspace

# Run the Android (Kotlin/JVM) tests using the container's Gradle
RUN cd android-app \
    && gradle --no-daemon test

CMD ["/bin/bash"]
