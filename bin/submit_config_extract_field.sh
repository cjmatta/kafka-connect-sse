#!/bin/bash

CONNECT_HOST=localhost

if [[ $1 ]];then
    CONNECT_HOST=$1
fi

HEADER="Content-Type: application/json"
DATA=$( cat << EOF
{
  "name": "wikipedia-sse-1",
  "config": {
    "connector.class": "com.github.cjmatta.kafka.connect.sse.ServerSentEventsSourceConnector",
    "topic": "wikipedia-raw",
    "sse.uri": "https://stream.wikimedia.org/v2/stream/recentchange",
    "transforms": "extractjson",
    "transforms.extractjson.type": "org.apache.kafka.connect.transforms.ExtractField$Value",
    "transforms.extractjson.field": "data",
    "value.converter": "io.confluent.connect.avro.AvroConverter",
    "value.converter.schema.registry.url": "http://localhost:8081",
    "tasks.max": "1"
  }
}
EOF
)

echo "curl -X POST -H \"${HEADER}\" --data \"${DATA}\" http://${CONNECT_HOST}:8083/connectors"
curl -X POST -H "${HEADER}" --data "${DATA}" http://${CONNECT_HOST}:8083/connectors
echo
