# Server Sent Events Source Connector for Apache Kafka

A Kafka Connect source connector supporting the [Server Sent Events Standard](https://en.wikipedia.org/wiki/Server-sent_events) to stream real-time updates from SSE-compatible endpoints into Apache Kafka topics.

## Features

- Streams events from any Server-Sent Events (SSE) compatible endpoint
- Supports secured endpoints with HTTP Basic Authentication
- Compatible with Kafka Connect in standalone and distributed modes
- Works with Confluent Platform and Confluent Cloud
- Configurable topic routing
- JSON data formatting
- Easy deployment and management with included scripts

## Configuration

| Configuration Parameter  | Description                          | Required |
|--------------------------|--------------------------------------|----------|
| sse.uri                  | URI for the SSE stream               | yes      |
| topic                    | Topic to send events to              | yes      |
| http.basic.auth          | Weather or not use use basic auth    | no       |
| http.basic.auth.username | username                             | no       |
| http.basic.auth.password | password                             | no       |
| http.header.<Name>       | Set a HTTP request header Name=Value | no       |

### Custom HTTP Headers

You can set custom HTTP headers using the `http.header.<Name>` configuration pattern. For example:

```properties
http.header.User-Agent=MyApp/1.0 (https://example.com; contact@example.com)
http.header.X-Custom-Header=CustomValue
```

**Default User-Agent:** If no `User-Agent` header is explicitly configured, the connector automatically sends:
```
KafkaConnectSSE/1.4 (https://github.com/cjmatta/kafka-connect-sse)
```

This default User-Agent helps comply with robot policies for public SSE endpoints like Wikimedia. You can override it by setting `http.header.User-Agent` to your preferred value.

### HTTP Client Configuration

| Configuration Parameter          | Description                                    | Required | Default |
|----------------------------------|------------------------------------------------|----------|---------|
| compression.enabled              | Enable gzip compression                        | no       | true    |

### Rate Limiting Configuration

| Configuration Parameter              | Description                                   | Required | Default |
|--------------------------------------|-----------------------------------------------|----------|---------|
| rate.limit.requests.per.second      | Maximum requests per second (optional)         | no       | -       |
| rate.limit.max.concurrent           | Maximum concurrent connections (optional)      | no       | -       |

### Retry Configuration

| Configuration Parameter    | Description                                  | Required | Default |
|----------------------------|----------------------------------------------|----------|---------|
| retry.backoff.initial.ms   | Initial backoff time for retries (ms)       | no       | 2000    |
| retry.backoff.max.ms       | Maximum backoff time for retries (ms)       | no       | 30000   |
| retry.max.attempts         | Maximum retry attempts (-1 for unlimited)    | no       | -1      |

## Building the Connector

To build the connector, you need Maven and Java 8 or higher installed on your system.

```bash
# Clone the repository
git clone https://github.com/cjmatta/kafka-connect-sse.git
cd kafka-connect-sse

# Build the connector
mvn clean package
```

This will create a ZIP file at `target/components/packages/cjmatta-kafka-connect-sse-1.4.zip` that can be used to deploy the connector.

## Deployment Options

### Standalone Mode

1. Extract the ZIP file to your Kafka Connect plugins directory
2. Configure the connector in a properties file (see `config/kafka-connect-sse.properties` for an example)
3. Start Kafka Connect with the standalone config:

```bash
$KAFKA_HOME/bin/connect-standalone.sh $KAFKA_HOME/config/connect-standalone.properties path/to/your-connector-config.properties
```

### Distributed Mode

1. Extract the ZIP file to your Kafka Connect plugins directory on all worker nodes
2. Restart the Kafka Connect workers to pick up the new plugin
3. Use the Kafka Connect REST API to create a connector instance:

```bash
curl -X POST -H "Content-Type: application/json" \
  --data @config/your-connector-config.json \
  http://localhost:8083/connectors
```

### Confluent Platform

1. Use Confluent Hub to install the connector:

```bash
confluent-hub install cjmatta/kafka-connect-sse:1.4
```

2. Restart Kafka Connect
3. Create a connector instance using Confluent Control Center UI or the REST API

### Confluent Cloud

The repository includes a convenient script to manage the connector in Confluent Cloud:

```bash
./manage-connector.sh upload   # Upload the connector plugin
./manage-connector.sh create   # Create a connector instance
```

## Managing with manage-connector.sh

The included `manage-connector.sh` script provides a simplified workflow for managing the connector in Confluent Cloud:

### Prerequisites

- Confluent CLI installed and configured
- Environment variables for authentication (or 1Password CLI)

### Commands

```bash
# Upload the connector plugin to Confluent Cloud
./manage-connector.sh upload

# Create a connector instance
./manage-connector.sh create

# Check status of connectors and plugins
./manage-connector.sh status

# Delete a connector instance
./manage-connector.sh delete-connector --connector-id <ID>

# Delete a plugin
./manage-connector.sh delete-plugin --plugin-id <ID>

# Display help
./manage-connector.sh help
```

### Using with 1Password

If you use 1Password to store your Confluent Cloud credentials:

```bash
# Create a .env file with your credential references
op run --env-file=.env -- ./manage-connector.sh create
```

## Example: Wikipedia Recent Changes

To stream Wikipedia's recent changes:

1. Build the connector
2. Upload to Confluent Cloud: `./manage-connector.sh upload`
3. Create the connector: `./manage-connector.sh create`
4. Verify data is flowing:
   ```bash
   kafka-console-consumer --bootstrap-server <your-bootstrap-server> \
     --topic wikimedia-raw --from-beginning
   ```

## ⚠️ Offset and Resume Disclaimer

Note: This connector does not provide reliable resume support across restarts.

The Server-Sent Events (SSE) protocol does not natively support seeking or replaying historical events. While some SSE servers emit an id field and support the Last-Event-ID header to resume from a recent point in the stream, this behavior is not standardized and not guaranteed across different SSE providers.

As a result:
	•	This connector does not persist partition or offset information for use in resuming ingestion.
	•	Upon restart, the connector will resume consuming from the current point in the stream as provided by the SSE server.
	•	No deduplication or de-duplication caching is performed unless explicitly implemented by the user at the application or downstream level.
