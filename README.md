# Server Sent Events

This is a Kafka Connect source connector supporting the [Server Sent Events Standard](https://en.wikipedia.org/wiki/Server-sent_events).


## Configuration

Configuration Parameter | Description | Required
-------------- | ---------- | --------- |
sse.uri | URI for the SSE stream | yes
topic | Topic to send events to | yes
http.basic.auth | Weather or not use use basic auth | no
http.basic.auth.username | username | no
http.basic.auth.password | password | no


ToDo:
- [x] Support for basic auth
