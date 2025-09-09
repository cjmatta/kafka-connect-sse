confluent connect custom-plugin create "kafka-connect-sse" \
    --plugin-file ~/Documents/Projects/kafka-connect-sse/target/components/packages/cjmatta-kafka-connect-sse-1.2.zip \
    --connector-class com.github.cjmatta.kafka.connect.sse.ServerSentEventsSourceConnector \
    --description "A Kafka Connect source connector for Server Sent Events" \
    --documentation-link https://github.com/cjmatta/kafka-connect-sse \
    --connector-type Source \
    --sensitive-properties http.basic.auth.password \
    --cloud aws