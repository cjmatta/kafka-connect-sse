
#!/usr/bin/env bash
DIR=$(cd $(dirname $0) && pwd)
: ${SUSPEND:='n'}

set -e
BUILD=false
while getopts b option; do
    case "${option}"
    in
        b) BUILD=true;;
    esac
done

if [[ ${BUILD} = true ]]; then
    mvn clean package
fi

export KAFKA_JMX_OPTS="-Xdebug -agentlib:jdwp=transport=dt_socket,server=y,suspend=${SUSPEND},address=5005"

connect-standalone ${DIR}/../config/connect-avro-docker.properties ${DIR}/../config/kafka-connect-sse.properties
