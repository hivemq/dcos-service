#!/bin/bash
echo -------------------------------------------------------------------------
echo
echo '                  _    _  _              __  __   ____'
echo '                 | |  | |(_)            |  \/  | / __ \ '
echo '                 | |__| | _ __   __ ___ | \  / || |  | |'
echo '                 |  __  || |\ \ / // _ \| |\/| || |  | |'
echo '                 | |  | || | \ V /|  __/| |  | || |__| |'
echo '                 |_|  |_||_|  \_/  \___||_|  |_| \___\_\'
echo
echo "-------------------------------------------------------------------------"
echo ""
echo "  HiveMQ Start Script for DC/OS Containers v1.0"
echo ""

############## VARIABLES
JAVA_OPTS="$JAVA_OPTS -Djava.net.preferIPv4Stack=true"
JAVA_OPTS="$JAVA_OPTS -noverify"

JAVA_OPTS="$JAVA_OPTS --add-opens java.base/java.lang=ALL-UNNAMED"
JAVA_OPTS="$JAVA_OPTS --add-opens java.base/java.nio=ALL-UNNAMED"
JAVA_OPTS="$JAVA_OPTS --add-opens java.base/sun.nio.ch=ALL-UNNAMED"
JAVA_OPTS="$JAVA_OPTS --add-opens jdk.management/com.sun.management.internal=ALL-UNNAMED"
JAVA_OPTS="$JAVA_OPTS --add-exports java.base/jdk.internal.misc=ALL-UNNAMED"

if [ -c "/dev/urandom" ]; then
    # Use /dev/urandom as standard source for secure randomness if it exists
    JAVA_OPTS="$JAVA_OPTS -Djava.security.egd=file:/dev/./urandom"
fi

if [ -z "$HIVEMQ_HOME" ]; then
    HIVEMQ_FOLDER="$( cd "$( dirname "${BASH_SOURCE[0]}" )/../" && pwd )"
    HOME_OPT="-Dhivemq.home=$HIVEMQ_FOLDER"
else
    HIVEMQ_FOLDER=$HIVEMQ_HOME
    HOME_OPT=""
fi

if [ ! -d "$HIVEMQ_FOLDER" ]; then
    echo ERROR! HiveMQ Home Folder not found.
elif [ ! -d "$MESOS_SANDBOX" ]; then
    echo ERROR! Mesos sandbox undefined. Is this a DCOS system?
else

    if [ ! -w "$HIVEMQ_FOLDER" ]; then
        echo ERROR! HiveMQ Home Folder Permissions not correct.
    else

        if [ ! -f "$HIVEMQ_FOLDER/bin/hivemq.jar" ]; then
            echo ERROR! HiveMQ JAR not found.
            echo $HIVEMQ_FOLDER;
        else
            HIVEMQ_FOLDER=$(echo "$HIVEMQ_FOLDER" | sed 's/ /\\ /g')
            JAVA_OPTS="$JAVA_OPTS -XX:+CrashOnOutOfMemoryError"
            JAVA_OPTS="$JAVA_OPTS -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=$MESOS_SANDBOX/hivemq-data/heap-dump.hprof"

            echo "-------------------------------------------------------------------------"
            echo ""
            echo "  HIVEMQ_HOME: $HIVEMQ_FOLDER"
            echo ""
            echo "  JAVA_OPTS: $JAVA_OPTS"
            echo ""
            echo "  JAVA_VERSION: $java_version"
            echo ""
            echo "-------------------------------------------------------------------------"
            echo ""
            # Run HiveMQ
            JAR_PATH="$HIVEMQ_FOLDER/bin/hivemq.jar"
            exec "$JAVA_HOME/bin/java" ${HOME_OPT} ${JAVA_OPTS} -jar ${JAR_PATH}
        fi
    fi
fi
