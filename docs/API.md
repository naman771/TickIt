# API reference

Base URL: `http://localhost:8080`. The MVP uses simulated users 1–3; there is no login or payment step.

## Book a seat

Find an available seat first; seeded seat 1 is already booked.

```bash
curl -s http://localhost:8080/api/events/1/seats

curl -i -X POST http://localhost:8080/api/holds \
  -H 'Content-Type: application/json' \
  -d '{"eventId":1,"seatId":3,"userId":1}'

curl -i -X POST http://localhost:8080/api/bookings \
  -H 'Content-Type: application/json' \
  -d '{"eventId":1,"seatId":3,"userId":1}'

curl -s http://localhost:8080/api/bookings/user/1
```

`POST /api/holds` returns `200` with `status`, `eventId`, `seatId`, `seatLabel`, and `expiresInSeconds`. Same-owner retries return the remaining lifetime, without extending the hold.

`POST /api/bookings` returns `201` with `bookingId`, `eventId`, `seatId`, `seatLabel`, `status: "CONFIRMED"`, and `bookedAt`.

To release a hold before booking:

```bash
curl -i -X DELETE 'http://localhost:8080/api/holds/1/3?userId=1'
```

Release requires the current owner's ID. There is no public endpoint for unconditional hold deletion.

## Status codes

| Status | Meaning |
|---|---|
| 200 | Read, hold acquisition, or release succeeded |
| 201 | Booking committed |
| 400 | Invalid payload, missing user ID, unknown seat/user, or seat/event mismatch |
| 404 | Event detail or seat map not found |
| 409 | Competing hold, duplicate booking, or release by a different owner |
| 410 | Booking attempted without a valid hold |
| 429 | Bucket exhausted; `Retry-After: 10` seconds |
| 500 | Unexpected server failure |

JSON errors contain an `error` message. Advice-generated errors also include `status` and `timestamp`; explicit controller conflicts may contain only `error`.

## Live seat updates

Connect a SockJS/STOMP client to `/ws`, then subscribe to `/topic/seats/{eventId}`. Messages contain `eventId`, `seatId`, `seatLabel`, and `status` (`HELD`, `FREE`, or `BOOKED`). Hold messages also identify the demo holder. Expiration messages may use the numeric seat ID as the label; use `seatId` for identity.

Messages are advisory: reconcile with `GET /api/events/{id}/seats` after reconnecting. The browser also refreshes this authoritative snapshot every five seconds. Event-list availability counts exclude confirmed bookings; the seat-map badge shows currently free seats and also excludes active holds.
