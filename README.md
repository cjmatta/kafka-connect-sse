# Server Sent Events

This is a Kafka Connect source connector supporting the [Server Sent Events Standard](https://www.w3.org/TR/2009/WD-eventsource-20090421/).


## Configuration

Configuration Parameter | Description | Required
-------------- | ---------- | --------- |
sse.uri | URI for the SSE stream | yes
topic | Topic to send events to | yes


ToDo:
- [ ] Support for basic auth