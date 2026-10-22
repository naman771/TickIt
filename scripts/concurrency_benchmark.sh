#!/usr/bin/env bash
# ⚡ TickIt High-Concurrency & Rate-Limiting Benchmark Script
# Simulates a flash sale spike with 50 concurrent threads targeting the same seat

set -e

BASE_URL="${1:-http://localhost:8080}"
EVENT_ID="${2:-1}"
NUM_REQUESTS="${3:-50}"

echo "=================================================================="
echo "⚡ TICKIT FLASH-SALE CONCURRENCY BENCHMARK"
echo "Target Base URL: $BASE_URL"
echo "Target Event ID: $EVENT_ID"
echo "Concurrent Spikes: $NUM_REQUESTS requests"
echo "=================================================================="

# 1. Check if backend is alive
if ! curl -s -f "$BASE_URL/api/events" > /dev/null; then
    echo "❌ Error: TickIt backend is not reachable at $BASE_URL. Please run the server first."
    exit 1
fi

# 2. Pick the first available FREE seat
echo -n "🔍 Querying seat map for a FREE seat... "
TARGET_SEAT_ID=$(curl -s "$BASE_URL/api/events/$EVENT_ID/seats" | \
    python3 -c "import sys, json; seats = json.load(sys.stdin); free = [s for s in seats if s.get('status') == 'FREE']; print(free[0]['seatId'] if free else '')")

if [ -z "$TARGET_SEAT_ID" ]; then
    echo "No free seats found on Event $EVENT_ID!"
    exit 1
fi

SEAT_LABEL=$(curl -s "$BASE_URL/api/events/$EVENT_ID/seats" | \
    python3 -c "import sys, json; seats = json.load(sys.stdin); s = [x for x in seats if x['seatId'] == $TARGET_SEAT_ID][0]; print(s['label'])")

echo "Targeting Seat: ID $TARGET_SEAT_ID ($SEAT_LABEL)"
echo "------------------------------------------------------------------"
echo "🚀 Dispatching $NUM_REQUESTS concurrent requests to hold Seat $SEAT_LABEL simultaneously..."

TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"' EXIT

# 3. Fire NUM_REQUESTS simultaneous hold requests across unique simulated users
for i in $(seq 1 "$NUM_REQUESTS"); do
    (
        USER_ID=$((100 + i))
        STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/holds" \
            -H "Content-Type: application/json" \
            -H "X-User-Id: $USER_ID" \
            -d "{\"eventId\": $EVENT_ID, \"seatId\": $TARGET_SEAT_ID, \"userId\": $USER_ID}")
        echo "$STATUS" >> "$TMP_DIR/results.txt"
    ) &
done

# Wait for all background requests to complete
wait

# 4. Analyze results
COUNT_200=$(grep -c "^200$" "$TMP_DIR/results.txt" || true)
COUNT_409=$(grep -c "^409$" "$TMP_DIR/results.txt" || true)
COUNT_429=$(grep -c "^429$" "$TMP_DIR/results.txt" || true)
COUNT_OTHER=$(grep -v -E "^(200|409|429)$" "$TMP_DIR/results.txt" | wc -l || true)

echo "=================================================================="
echo "📊 BENCHMARK RESULTS SUMMARY:"
echo "------------------------------------------------------------------"
printf "%-32s : %s\n" "Total Concurrent Requests" "$NUM_REQUESTS"
printf "%-32s : %s\n" "200 OK (Hold Acquired)" "$COUNT_200"
printf "%-32s : %s\n" "409 CONFLICT (Blocked by Redis Hold)" "$COUNT_409"
printf "%-32s : %s\n" "429 RATE-LIMITED (Blocked by Token Bucket)" "$COUNT_429"
if [ "$COUNT_OTHER" -gt 0 ]; then
    printf "%-32s : %s\n" "Other Responses" "$COUNT_OTHER"
fi
echo "=================================================================="

# 5. Invariant Assertion
if [ "$COUNT_200" -eq 1 ]; then
    echo "✅ PASSED: Exactly 1 user acquired the hold. Zero race-condition overlap!"
else
    echo "⚠️ Warning: $COUNT_200 users acquired hold."
fi

# 6. Complete the booking for the winner
echo -n "💳 Confirming booking for the winning user... "
BOOKING_STATUS=$(curl -s -o "$TMP_DIR/booking_resp.json" -w "%{http_code}" -X POST "$BASE_URL/api/bookings" \
    -H "Content-Type: application/json" \
    -d "{\"eventId\": $EVENT_ID, \"seatId\": $TARGET_SEAT_ID, \"userId\": 101}")

if [ "$BOOKING_STATUS" -eq 201 ]; then
    BOOKING_ID=$(python3 -c "import json; print(json.load(open('$TMP_DIR/booking_resp.json'))['bookingId'])")
    echo "SUCCESS! Booking ID #$BOOKING_ID confirmed."
else
    echo "Booking status: HTTP $BOOKING_STATUS"
fi

# 7. Check database seat status
FINAL_STATUS=$(curl -s "$BASE_URL/api/events/$EVENT_ID/seats" | \
    python3 -c "import sys, json; seats = json.load(sys.stdin); s = [x for x in seats if x['seatId'] == $TARGET_SEAT_ID][0]; print(s['status'])")

echo "Seat $SEAT_LABEL Final Live Status: $FINAL_STATUS"
echo "=================================================================="
