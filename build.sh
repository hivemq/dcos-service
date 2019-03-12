#!/usr/bin/env bash

function build() {
    set -e

    echo "building scheduler..."
    cd java/scheduler
    ./gradlew build
    echo "building extension..."
    cd ../../extension
    mvn -P Packaging package
    echo "building dcos files layer..."
    cd ../
    rm dcos_files.zip
    rm -rf dcos_files/extensions/hivemq-dcos
    unzip extension/target/hivemq-dcos-*-distribution.zip -d dcos_files/extensions
    zip -r dcos_files dcos_files
    echo "uploading..."
    dcosdev up

    set +e
}

function reinstall() {
    echo "uninstalling previous version"
    dcos package uninstall hivemq --yes
    while (( $(dcos task | grep hivemq | wc -l) > 0 )); do
        echo "Framework not yet uninstalled"
        sleep 2
    done
    echo "reinstalling..."
    dcos package install hivemq --yes
}

build
reinstall