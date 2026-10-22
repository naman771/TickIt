#!/usr/bin/env bash
# Uses the caller's real session. Does not create or simulate paid bookings.
set -euo pipefail
: "${TICKIT_ACCESS_TOKEN:?Set TICKIT_ACCESS_TOKEN to your own short-lived session token}"
[[ "$TICKIT_ACCESS_TOKEN" =~ ^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$ ]] || { echo "Expected a JWT session token"; exit 2; }
BASE_URL="${1:-http://localhost:8080}"
EVENT_ID="${2:-1}"
COUNT="${3:-25}"
[[ "$EVENT_ID" =~ ^[1-9][0-9]*$ && "$COUNT" =~ ^[1-9][0-9]*$ ]] || exit 2
TMP_DIR=$(mktemp -d)
chmod 700 "$TMP_DIR"
# Keep the token out of curl's command-line arguments and process listings.
printf 'header = "Authorization: Bearer %s"\n' "$TICKIT_ACCESS_TOKEN" > "$TMP_DIR/curl.conf"
chmod 600 "$TMP_DIR/curl.conf"
SEAT_ID=""
cleanup() {
  if [ -n "$SEAT_ID" ]; then
    sleep 1
    curl --max-time 10 --silent --config "$TMP_DIR/curl.conf" -X DELETE "$BASE_URL/api/holds/$EVENT_ID/$SEAT_ID" > /dev/null || true
  fi
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT
curl --max-time 10 --fail --silent --show-error --config "$TMP_DIR/curl.conf" "$BASE_URL/api/me" > /dev/null
SEAT_ID=$(curl --max-time 10 --fail --silent --show-error "$BASE_URL/api/events/$EVENT_ID/seats" | python3 -c 'import json,sys; print(next((s["seatId"] for s in json.load(sys.stdin) if s["status"] == "FREE"), ""))')
[ -n "$SEAT_ID" ] || { echo "No free seats"; exit 1; }
for ((i=1;i<=COUNT;i++)); do
  (
    curl --max-time 15 --silent --show-error --config "$TMP_DIR/curl.conf" -o /dev/null -w '%{http_code}\n' -X POST "$BASE_URL/api/holds" -H 'Content-Type: application/json' -d "{\"eventId\":$EVENT_ID,\"seatId\":$SEAT_ID}" > "$TMP_DIR/$i.status"
  ) &
done
wait
python3 - "$TMP_DIR" <<'PY'
import pathlib,collections,sys
counts=collections.Counter(p.read_text().strip() for p in pathlib.Path(sys.argv[1]).glob('*.status'))
print('HTTP response counts:', dict(sorted(counts.items())))
print('Requests use one authenticated account; successful retries share its existing hold.')
if any(status not in {'200','409','429'} for status in counts): raise SystemExit('Unexpected response; inspect the server logs.')
PY
