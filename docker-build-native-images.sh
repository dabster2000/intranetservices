#!/bin/bash
# jvm, native
buildtype="native"
# prod, test, latest
targetenv="prod"
# linux/amd64 (prod), linux/arm64 (latest)
targetplatform="linux/amd64"

# For native builds, the Dockerfile.multistage handles the Maven build
# For JVM builds, we need to build first
if [ $buildtype = 'jvm' ]; then mvn clean package -f pom.xml; fi

if [ $buildtype = 'native' ]; then docker build -f src/main/docker/Dockerfile.multistage -t trustworks/twservices:$targetenv .; fi
if [ $buildtype = 'jvm' ]; then docker build --platform $targetplatform -f src/main/docker/Dockerfile.jvm -t trustworks/twservices:$targetenv .; fi
docker push trustworks/twservices:$targetenv

