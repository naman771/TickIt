#!/usr/bin/env bash
# Demonstrates competing holds by the three seeded users, then books as the winner.
set -euo pipefail
BASE_URL="${1:-http://localhost:8080}"
EVENT_ID="${2:-1}"
NUM_REQUESTS="${3:-50}"
[[ "$EVENT_ID" =~ ^[1-9][0-9]*$ && "$NUM_REQUESTS" =~ ^[1-9][0-9]*$ ]] || exit 2
TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"' EXIT
curl --max-time 10 -fsS "$BASE_URL/api/events/$EVENT_ID/seats" > "$TMP_DIR/seats.json"
SEAT_ID=$(python3 -c 'import json,sys; print(next((s["seatId"] for s in json.load(sys.stdin) if s["status"] == "FREE"), ""))' < "$TMP_DIR/seats.json")
[ -n "$SEAT_ID" ] || { echo "No free seats in event $EVENT_ID"; exit 1; }
echo "Dispatching $NUM_REQUESTS holds for event $EVENT_ID, seat $SEAT_ID"
for ((i=1; i<=NUM_REQUESTS; i++)); do
  (
    USER_ID=$(( (i-1) % 3 + 1 ))
    STATUS=$(curl --max-time 15 -sS -o "$TMP_DIR/$i.json" -w '%{http_code}' \
      -H 'Content-Type: application/json' -X POST "$BASE_URL/api/holds" \
      -d "{\"eventId\":$EVENT_ID,\"seatId\":$SEAT_ID,\"userId\":$USER_ID}")
    echo "$STATUS $USER_ID" > "$TMP_DIR/$i.status"
  ) &
done
wait
python3 - "$TMP_DIR" <<'PYCODE'
import pathlib, collections, sys
root = pathlib.Path(sys.argv[1]); results = [p.read_text().split() for p in root.glob('*.status')]
counts = collections.Counter(r[0] for r in results)
print('HTTP response counts:', dict(sorted(counts.items())))
winners = {r[1] for r in results if r[0] == '200'}
if len(winners) != 1 or any(s not in {'200', '409', '429'} for s in counts):
    raise SystemExit('FAILED: expected exactly one distinct hold owner and no unexpected responses')
(root / 'winner').write_text(winners.pop())
print('PASS: exactly one distinct hold owner (same-owner retries are idempotent).')
PYCODE
WINNER=$(cat "$TMP_DIR/winner")
# Allow the shared IP bucket to refill after the burst.
sleep 1
STATUS=$(curl --max-time 15 -sS -o "$TMP_DIR/booking.json" -w '%{http_code}' \
  -H 'Content-Type: application/json' -X POST "$BASE_URL/api/bookings" \
  -d "{\"eventId\":$EVENT_ID,\"seatId\":$SEAT_ID,\"userId\":$WINNER}")
cat "$TMP_DIR/booking.json"
[ "$STATUS" = 201 ] || { echo "Booking failed: HTTP $STATUS"; exit 1; }
curl --max-time 10 -fsS "$BASE_URL/api/events/$EVENT_ID/seats" | python3 -c \
  'import sys,json; seat=next(s for s in json.load(sys.stdin) if s["seatId"] == int(sys.argv[1])); assert seat["status"] == "BOOKED"; print("\nPASS: winning booking confirmed; seat is BOOKED.")' "$SEAT_ID"
