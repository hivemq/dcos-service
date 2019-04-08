#!/usr/bin/env bash

function build() {
    set -e

    echo "building scheduler..."
    cd java/scheduler
    ./gradlew build
    echo "building extension..."
    cd ../../extension
    mvn -P Packaging clean package
    echo "building hivemq tool..."
    cd ../hivemq-tool
    mvn clean package
    echo "building dcos files layer..."
    cd ../
    rm dcos_files.zip || true
    rm -rf dcos_files/extensions/hivemq-dcos
    # add extension
    unzip extension/target/hivemq-dcos-*-distribution.zip -d dcos_files/extensions
    # add hivemq-tool
    cp -rp hivemq-tool/target/hivemq-tool-*with-dep*.jar dcos_files/
    zip -r dcos_files dcos_files
    echo "uploading..."
    dcosdev up

    set +e
}

build