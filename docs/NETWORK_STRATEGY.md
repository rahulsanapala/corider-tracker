# Network Strategy

The app is optimized around one rule: do not send data unless it improves another rider's view.

## Transport

The starter uses Server-Sent Events for inbound updates and small HTTP POSTs for outbound updates.

- Each rider opens one long-lived `GET /rides/{rideId}/events?riderId={riderId}` stream.
- Each location update is a `POST /rides/{rideId}/location`.
- The relay stores only the latest location per rider and broadcasts it to connected riders in that ride.
- Stale riders are removed after 90 seconds.

This is simple to run locally and avoids polling. For production at larger scale, MQTT over TLS is the natural next transport because it has lower framing overhead and built-in publish/subscribe semantics.

## Compact Payload

Location payload:

```json
{"y":"loc","u":"rider-id","n":"Rider","t":1778750000000,"a":173850000,"o":784860000,"s":520,"b":91,"c":8}
```

Fields:

- `y` - event type, `loc` or `left`.
- `u` - rider id.
- `n` - display name.
- `t` - timestamp in milliseconds.
- `a` - latitude multiplied by 10,000,000.
- `o` - longitude multiplied by 10,000,000.
- `s` - speed in centimeters per second.
- `b` - bearing in degrees, or `-1` if unknown.
- `c` - accuracy in meters, or `-1` if unknown.

Integer coordinates avoid long decimal strings and are easy to move to protobuf, CBOR, or MQTT binary payloads later.

## Adaptive Send Rules

`LocationGate` samples location frequently but publishes sparingly:

- Speed at least `12 m/s`: send after `3 s` and `25 m`.
- Speed at least `2 m/s`: send after `5 s` and `12 m`.
- Slow or stopped: send after `15 s` and `8 m`.
- Heartbeat: send after `60 s` even if distance is small.
- Skip very inaccurate fixes over `120 m` unless the heartbeat is due.

This keeps moving riders smooth while stopped riders become nearly silent.

## Scale Notes

For small ride groups, a single room stream is enough. For very large events:

- Split rides into map tiles or geohash topics.
- Send updates only to riders within a radius.
- Increase slow/stopped intervals to 30-60 seconds.
- Use MQTT topics such as `rides/{rideId}/tile/{tileId}`.
- Keep a Redis TTL key for latest rider location and no permanent history by default.

## Security Notes

The local relay is not production security. A real deployment should add:

- HTTPS/TLS everywhere.
- Authenticated join codes.
- Short-lived ride membership tokens.
- Rate limits per rider and per IP.
- Payload signature or token-bound rider id so clients cannot spoof another rider.
- Ride expiration and explicit data retention rules.

