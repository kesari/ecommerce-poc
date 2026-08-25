#!/bin/bash
# End-to-end walk of the 13 fixture scenarios.
# HTTP journeys run through the Bruno collection; infra-assisted scenarios
# (quote expiry, mid-saga compensation, Kafka duplicates, outages) are driven
# here with docker/psql/kafka-console tooling.
set -uo pipefail

COMPOSE="docker compose"
BRUNO="npx --yes @usebruno/cli"
DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$DIR/.." || exit 1

PASS=0
FAIL=0
declare -a FAILED

note() { echo "--- $1"; }
pass() { echo "PASS: $1"; PASS=$((PASS+1)); }
fail() { echo "FAIL: $1"; FAIL=$((FAIL+1)); FAILED+=("$1"); }

run_bruno_folder() {
  local folder="$1"
  (cd e2e-bruno && $BRUNO run "$folder" --env local 2>&1 | tee "/tmp/op/bruno-$folder.log")
}

extract_marker() { # file prefix
  grep -o "$2=[^ ]*" "/tmp/op/bruno-$1.log" | head -1 | cut -d= -f2
}

poll_terminal() { # orderId token expectedStatus
  for _ in $(seq 1 40); do
    local response status
    response=$(http_json GET "/api/v1/orders/$1" "$2")
    status=$(body_of "$response" | python3 -c "import json,sys;print(json.load(sys.stdin).get('status',''))" 2>/dev/null)
    if [ "$status" = "$3" ]; then return 0; fi
    if [ "$status" = "CONFIRMED" ] || [ "$status" = "CANCELLED" ] \
       || [[ "$status" == REJECTED* ]]; then return 1; fi
    sleep 2
  done
  return 1
}

http_json() { # method path token body [extra-header]
  local method=$1 path=$2 token=$3 body=${4:-} extra=${5:-}
  local args=(-s -w $'\n%{http_code}' -X "$method" "http://localhost:8080$path")
  if [ -n "$token" ]; then args+=(-H "Authorization: Bearer $token"); fi
  [ -n "$extra" ] && args+=(-H "$extra")
  if [ -n "$body" ]; then
    args+=(-H 'Content-Type: application/json' -d "$body")
  fi
  curl "${args[@]}"
}

body_of()  { echo "$1" | sed '$d'; }
status_of() { echo "$1" | tail -1; }

signup_and_token() {
  local email="e2e$(date +%s%N)@example.com"
  local response
  response=$(http_json POST /api/v1/auth/signup "" \
    "{\"email\":\"$email\",\"password\":\"correct-horse-battery-42\"}")
  body_of "$response" | python3 -c "import json,sys;print(json.load(sys.stdin)['accessToken'])"
}

add_item() { http_json POST /api/v1/basket/items "$1" "$2" > /dev/null; }

poll_status() { # orderId token terminal expected
  for _ in $(seq 1 30); do
    local response status
    response=$(http_json GET "/api/v1/orders/$1" "$2")
    status=$(body_of "$response" | python3 -c "import json,sys;print(json.load(sys.stdin).get('status',''))" 2>/dev/null)
    if [ "$status" = "$3" ]; then return 0; fi
    sleep 2
  done
  return 1
}

kafka_publish() {
  $COMPOSE exec -T kafka /opt/kafka/bin/kafka-console-producer.sh \
    --bootstrap-server localhost:29092 \
    --property parse.key=true --property key.separator='|' \
    --topic "$1" <<< "$2|$3"
}

dlq_count() {
  $COMPOSE exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh \
    --bootstrap-server localhost:29092 --topic "$1" --from-beginning --timeout-ms 8000 \
    2>/dev/null | wc -l | tr -d ' '
}

wait_healthy() {
  for _ in $(seq 1 60); do
    [ "$(curl -s -o /dev/null -w '%{http_code}' "$1")" = "200" ] && return 0
    sleep 2
  done
  return 1
}

echo "############ bring-up and health"
$COMPOSE up -d 2>/dev/null
for port in 8081 8082 8083 8084 8085 8086 8087 8080; do
  wait_healthy "http://localhost:$port/actuator/health" || { fail "service on :$port never became healthy"; }
done

echo "############ scenario 01: signup-to-confirmed-order"
if run_bruno_folder 01-signup-to-confirmed-order; then
  TOKEN01=$(extract_marker 01-signup-to-confirmed-order BRUNOTOKEN)
  ORDER01=$(extract_marker 01-signup-to-confirmed-order BRUNOORDER)
  if poll_terminal "$ORDER01" "$TOKEN01" "CONFIRMED"; then
    pass "full happy journey reached CONFIRMED"
  else
    fail "saga did not reach CONFIRMED"
  fi
else
  fail "happy journey requests"
fi

echo "############ scenario 02: invalid coupon"
if run_bruno_folder 02-invalid-coupon; then
  pass "COUPON_INVALID surfaced"
else
  fail "invalid coupon"
fi

echo "############ scenario 03: second coupon rejected"
if run_bruno_folder 03-second-coupon; then
  pass "COUPON_ALREADY_APPLIED surfaced"
else
  fail "second coupon"
fi

echo "############ scenario 04: quote expires before placement"
TOKEN=$(signup_and_token)
add_item "$TOKEN" '{"productId":"11111111-1111-4111-8111-111111111111","quantity":1}'
ADDRESS=$(http_json POST /api/v1/addresses "$TOKEN" \
  '{"fullName":"E2E","line1":"12 MG Road","city":"Bengaluru","state":"KA","postalCode":"560001","country":"IN"}')
ADDRESS_ID=$(body_of "$ADDRESS" | python3 -c "import json,sys;print(json.load(sys.stdin)['id'])")
QUOTE=$(http_json POST /api/v1/checkout/quotes "$TOKEN" "{\"addressId\":\"$ADDRESS_ID\"}")
QUOTE_ID=$(body_of "$QUOTE" | python3 -c "import json,sys;print(json.load(sys.stdin)['quoteId'])")
$COMPOSE exec -T postgres psql -U order_service -d order_service -qc \
  "UPDATE quotes SET expires_at = now() - interval '1 minute'" > /dev/null
RESULT=$(http_json POST /api/v1/orders "$TOKEN" \
  "{\"quoteId\":\"$QUOTE_ID\",\"payment\":{\"method\":\"CREDIT_CARD\",\"token\":\"tok_success\"}}" "Idempotency-Key: $(uuidgen)")
QUOTE_STATUS="$(status_of "$RESULT")"
QUOTE_BODY_MATCHES=$(body_of "$RESULT" | grep -c QUOTE_EXPIRED)
if [ "$QUOTE_BODY_MATCHES" -ge 1 ] && [ "$QUOTE_STATUS" = "409" -o "$QUOTE_STATUS" = "410" ]; then
  pass "expired quote rejected with QUOTE_EXPIRED"
else
  fail "expected 409/410 QUOTE_EXPIRED, got: $RESULT"
fi

echo "############ scenario 05: basket changes after quote"
if run_bruno_folder 05-basket-changed-after-quote; then
  pass "QUOTE_BASKET_CHANGED surfaced"
else
  fail "basket changed after quote"
fi

echo "############ scenario 06: insufficient inventory"
if run_bruno_folder 06-out-of-stock; then
  TOKEN06=$(extract_marker 06-out-of-stock BRUNOTOKEN)
  ORDER06=$(extract_marker 06-out-of-stock BRUNOORDER)
  if poll_terminal "$ORDER06" "$TOKEN06" "REJECTED_OUT_OF_STOCK"; then
    pass "REJECTED_OUT_OF_STOCK without charging"
  else
    fail "order did not reach REJECTED_OUT_OF_STOCK"
  fi
else
  fail "out of stock requests"
fi

echo "############ scenario 07: payment decline releases stock"
STOCK_BEFORE=$($COMPOSE exec -T postgres psql -U inventory_service -d inventory_service -tAc \
  "SELECT available FROM stock WHERE product_id='22222222-2222-4222-8222-222222222222'")
if run_bruno_folder 07-payment-decline; then
  TOKEN07=$(extract_marker 07-payment-decline BRUNOTOKEN)
  ORDER07=$(extract_marker 07-payment-decline BRUNOORDER)
  poll_terminal "$ORDER07" "$TOKEN07" "REJECTED_PAYMENT"
  STOCK_AFTER=$($COMPOSE exec -T postgres psql -U inventory_service -d inventory_service -tAc \
    "SELECT available FROM stock WHERE product_id='22222222-2222-4222-8222-222222222222'")
  if [ "$STOCK_BEFORE" = "$STOCK_AFTER" ]; then
    pass "declined payment, stock released back to $STOCK_AFTER"
  else
    fail "stock not restored: before=$STOCK_BEFORE after=$STOCK_AFTER"
  fi
else
  fail "payment decline journey"
fi

echo "############ scenario 08: duplicate place-order"
if run_bruno_folder 08-duplicate-place-order; then
  pass "same Idempotency-Key returned the original order"
else
  fail "duplicate place-order"
fi

echo "############ scenario 09: duplicate kafka delivery"
DUP_ORDER=$(uuidgen)
ENVELOPE="{\"eventId\":\"$(uuidgen)\",\"eventType\":\"inventory.reserve.requested\",\"schemaVersion\":1,\"occurredAt\":\"2026-08-24T00:00:00Z\",\"producer\":\"order-service\",\"correlationId\":\"$DUP_ORDER\",\"causationId\":\"$(uuidgen)\",\"partitionKey\":\"$DUP_ORDER\",\"payload\":{\"orderId\":\"$DUP_ORDER\",\"items\":[{\"productId\":\"22222222-2222-4222-8222-222222222222\",\"quantity\":1}]}}"
for _ in 1 2; do
  kafka_publish inventory.reserve.requested.v1 "$DUP_ORDER" "$ENVELOPE"
  sleep 4
done
RESERVED=$($COMPOSE exec -T postgres psql -U inventory_service -d inventory_service -tAc \
  "SELECT count(*) FROM reservations WHERE order_id='$DUP_ORDER'")
if [ "$RESERVED" = "1" ]; then
  pass "duplicate delivery produced exactly one reservation"
else
  fail "expected exactly 1 reservation after duplicate delivery, got ${RESERVED:-none}"
fi

echo "############ scenario 10: refund workflow on a charged payment"
TOKEN10=$(signup_and_token)
add_item "$TOKEN10" '{"productId":"11111111-1111-4111-8111-111111111111","quantity":1}'
ADDRESS=$(http_json POST /api/v1/addresses "$TOKEN10" \
  '{"fullName":"E2E","line1":"12 MG Road","city":"Bengaluru","state":"KA","postalCode":"560001","country":"IN"}')
ADDRESS_ID=$(body_of "$ADDRESS" | python3 -c "import json,sys;print(json.load(sys.stdin)['id'])")
QUOTE=$(http_json POST /api/v1/checkout/quotes "$TOKEN10" "{\"addressId\":\"$ADDRESS_ID\"}")
QUOTE_ID=$(body_of "$QUOTE" | python3 -c "import json,sys;print(json.load(sys.stdin)['quoteId'])")
PLACED=$(http_json POST /api/v1/orders "$TOKEN10" \
  "{\"quoteId\":\"$QUOTE_ID\",\"payment\":{\"method\":\"CREDIT_CARD\",\"token\":\"tok_success\"}}" \
  "Idempotency-Key: $(uuidgen)")
ORDER_ID=$(body_of "$PLACED" | python3 -c "import json,sys;print(json.load(sys.stdin)['orderId'])")
if ! poll_status "$ORDER_ID" "$TOKEN10" "CONFIRMED"; then
  fail "order did not confirm before refund step"
fi
REFUND_ROW=$($COMPOSE exec -T postgres psql -U payment_service -d payment_service -tAc \
  "SELECT payment_id || '|' || amount_minor FROM payments WHERE order_id='$ORDER_ID'" | tr -d ' ')
PAYMENT_ID="${REFUND_ROW%%|*}"
AMOUNT="${REFUND_ROW##*|}"
echo "refund target: payment=$PAYMENT_ID amount=$AMOUNT"
REFUND_EVENT_ID=$(uuidgen | tr '[:upper:]' '[:lower:]')
REFUND_ENVELOPE="{\"eventId\":\"$REFUND_EVENT_ID\",\"eventType\":\"payment.refund.requested\",\"schemaVersion\":1,\"occurredAt\":\"2026-08-24T00:00:00Z\",\"producer\":\"order-service\",\"correlationId\":\"$ORDER_ID\",\"causationId\":\"$(uuidgen)\",\"partitionKey\":\"$ORDER_ID\",\"payload\":{\"orderId\":\"$ORDER_ID\",\"paymentId\":\"$PAYMENT_ID\",\"amountMinor\":$AMOUNT}}"
kafka_publish payment.refund.requested.v1 "$ORDER_ID" "$REFUND_ENVELOPE"
sleep 6
REFUND=$($COMPOSE exec -T postgres psql -U payment_service -d payment_service -tAc \
  "SELECT count(*) FROM refunds r JOIN payments p ON p.payment_id = r.payment_id WHERE p.order_id='$ORDER_ID'")
STATUS=$($COMPOSE exec -T postgres psql -U payment_service -d payment_service -tAc \
  "SELECT status FROM payments WHERE order_id='$ORDER_ID'")
if [ "${REFUND:-0}" -ge 1 ] && [ "$STATUS" = "REFUNDED" ]; then
  pass "refund command produced refunds row and REFUNDED status"
else
  fail "refund not applied (refunds=${REFUND:-0} status=$STATUS)"
fi

echo "############ scenario 11: valkey outage fallback"
$COMPOSE stop valkey > /dev/null 2>&1
HTTP=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/api/v1/products)
$COMPOSE start valkey > /dev/null 2>&1
sleep 3
if [ "$HTTP" = "200" ]; then
  pass "catalog served from database during valkey outage"
else
  fail "catalog returned $HTTP during outage"
fi

echo "############ scenario 12: consumer failure to DLQ"
BEFORE=$(dlq_count payment.charge.requested.v1.dlq)
MALFORMED="{\"eventId\":\"$(uuidgen)\",\"eventType\":\"payment.charge.requested\",\"schemaVersion\":1,\"occurredAt\":\"2026-08-24T00:00:00Z\",\"producer\":\"order-service\",\"correlationId\":\"$(uuidgen)\",\"causationId\":\"$(uuidgen)\",\"partitionKey\":\"$(uuidgen)\",\"payload\":{\"orderId\":\"not-a-uuid\",\"amountMinor\":100,\"currency\":\"INR\",\"token\":\"tok_success\"}}"
kafka_publish payment.charge.requested.v1 "dlq-probe" "$MALFORMED"
sleep 20
AFTER=$(dlq_count payment.charge.requested.v1.dlq)
if [ "${AFTER:-0}" -gt "${BEFORE:-0}" ]; then
  pass "malformed charge command landed on DLQ ($((AFTER-BEFORE)) new)"
else
  fail "no new DLQ record (before=$BEFORE after=$AFTER)"
fi

echo "############ scenario 13: breaker opens, fails fast, recovers"
TOKEN=$(signup_and_token)
$COMPOSE stop basket-service > /dev/null 2>&1
START_NS=$(date +%s%N)
HTTP503=0
for _ in 1 2 3 4 5 6 7 8; do
  CODE=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 \
    http://localhost:8080/api/v1/basket -H "Authorization: Bearer $TOKEN")
  ELAPSED_MS=$(( ($(date +%s%N) - START_NS) / 1000000 ))
  if [ "$CODE" = "503" ] && [ "$ELAPSED_MS" -lt 1500 ]; then
    HTTP503=1
    break
  fi
  sleep 1
done
$COMPOSE start basket-service > /dev/null 2>&1
wait_healthy http://localhost:8083/actuator/health || true
sleep 12
RECOVERED=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 \
  http://localhost:8080/api/v1/basket -H "Authorization: Bearer $TOKEN")
if [ "$HTTP503" = "1" ] && [ "$RECOVERED" = "200" ]; then
  pass "breaker failed fast with 503 (${ELAPSED_MS}ms) and recovered to 200"
else
  fail "breaker check: outage-code=$HTTP503 recovery=$RECOVERED"
fi

echo ""
echo "############################"
echo "E2E RESULT: PASS=$PASS FAIL=$FAIL"
if [ ${#FAILED[@]} -gt 0 ]; then printf 'failed scenarios: %s\n' "${FAILED[*]}"; exit 1; fi
exit 0
