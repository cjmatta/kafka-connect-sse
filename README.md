# Server Sent Events

This is a Kafka Connect source connector supporting the [Server Sent Events Standard](https://en.wikipedia.org/wiki/Server-sent_events).


## Configuration

| Configuration Parameter  | Description                       | Required |
|--------------------------|-----------------------------------|----------|
| sse.uri                  | URI for the SSE stream            | yes      |
| topic                    | Topic to send events to           | yes      |
| http.basic.auth          | Weather or not use use basic auth | no       |
| http.basic.auth.username | username                          | no       |
| http.basic.auth.password | password                          | no       |

ToDo:
- [x] Support for basic auth


## ⚠️ Offset and Resume Disclaimer

Note: This connector does not provide reliable resume support across restarts.

The Server-Sent Events (SSE) protocol does not natively support seeking or replaying historical events. While some SSE servers emit an id field and support the Last-Event-ID header to resume from a recent point in the stream, this behavior is not standardized and not guaranteed across different SSE providers.

As a result:
	•	This connector does not persist partition or offset information for use in resuming ingestion.
	•	Upon restart, the connector will resume consuming from the current point in the stream as provided by the SSE server.
	•	No deduplication or de-duplication caching is performed unless explicitly implemented by the user at the application or downstream level.

For advanced users: if your SSE stream includes a stable and sequential event ID, you may implement custom filtering logic or use a specialized, forked version of the connector with support for offset tracking and resume.