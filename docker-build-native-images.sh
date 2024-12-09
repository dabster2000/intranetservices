#!/bin/bash
# jvm, native
buildtype="jvm"
# prod, test, latest
targetenv="prod"
# linux/amd64 (prod), linux/arm64 (latest)
targetplatform="linux/amd64"

mvn clean package -f pom.xml
if [ $buildtype = 'native' ]; then docker build -f src/main/docker/Dockerfile.multistage -t trustworks/twservices:$targetenv .; fi
if [ $buildtype = 'jvm' ]; then docker build --platform $targetplatform -f src/main/docker/Dockerfile.jvm -t trustworks/twservices:$targetenv .; fi
docker push trustworks/twservices:$targetenv

