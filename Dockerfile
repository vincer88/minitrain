# syntax=docker/dockerfile:1
FROM ubuntu:22.04

ARG DEBIAN_FRONTEND=noninteractive

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        build-essential \
        cmake \
        openjdk-17-jdk \
        gradle \
        git \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace

COPY . /workspace

ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
ENV PATH="$JAVA_HOME/bin:$PATH"

RUN cmake -S firmware -B firmware/build \
    && cmake --build firmware/build \
    && ctest --test-dir firmware/build

RUN cd android-app \
    && gradle --no-daemon test

CMD ["/bin/bash"]
