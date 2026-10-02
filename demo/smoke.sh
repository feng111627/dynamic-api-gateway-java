#!/usr/bin/env bash
# Local smoke script for the gateway demo.
# Prerequisite: the demo app is already running:
#   mvn spring-boot:run -Dspring-boot.run.profiles=demo
# (gateway on 18080, simulated backends on 18081/18082)
set -euo pipefail
GW=${GW:-http://127.0.0.1:18080}
B1=${B1:-http://127.0.0.1:18081}
B2=${B2:-http://127.0.0.1:18082}

hr() { printf '\n--- %s ---\n' "$1"; }

hr "1. routing + stickiness: caller alice must always land on the same instance"
for i in 1 2 3; do
  curl -s -H 'X-Caller-Id: alice' -D - "$GW/orders/42" -o /tmp/gw-body.json \
    | grep -iE 'HTTP/|X-Gateway-Instance' ; cat /tmp/gw-body.json; echo
done

hr "2. rate limiting: caller limit is 5 / 10s, the 6th request is 429 with rule identity"
for i in 1 2 3 4 5 6; do
  code=$(curl -s -o /tmp/gw-body.json -w '%{http_code}' -H 'X-Caller-Id: rate-demo' "$GW/orders/rl/$i")
  echo "request $i -> HTTP $code"
done
echo "last 429 body:"; cat /tmp/gw-body.json; echo

hr "3. sticky failover: bind caller to one instance, then force that instance down"
PRIMARY=$(curl -s -H 'X-Caller-Id: fail-demo' "$GW/orders/1" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')
OTHER=$( [ "$PRIMARY" = backend-1 ] && echo backend-2 || echo backend-1 )
PPORT=$( [ "$PRIMARY" = backend-1 ] && echo "${B1##*:}" || echo "${B2##*:}" )
PBASE=$( [ "$PRIMARY" = backend-1 ] && echo "$B1" || echo "$B2" )
echo "caller bound to $PRIMARY; forcing it down"
curl -s -X POST -d down "$PBASE/control" >/dev/null
sleep 0.3
curl -s -H 'X-Caller-Id: fail-demo' -D - "$GW/orders/1" -o /tmp/gw-body.json \
  | grep -iE 'HTTP/|X-Gateway-Instance|X-Gateway-Attempted'
cat /tmp/gw-body.json; echo
echo "health panel:"; curl -s "$GW/admin/health"

hr "4. recovery + sticky reattach (active probe every 1s, reattach backoff 1s)"
curl -s -X POST -d up "$PBASE/control" >/dev/null
sleep 3
echo "after probe recovery, waiting out the rate-limit window before next request..."
sleep 8
curl -s -H 'X-Caller-Id: fail-demo' -D - "$GW/orders/1" -o /tmp/gw-body.json \
  | grep -iE 'HTTP/|X-Gateway-Instance'
cat /tmp/gw-body.json; echo

hr "5. atomic hot reload: invalid batch -> 422 with issue codes, old rules preserved"
curl -s -w '\nHTTP %{http_code}\n' -X PUT -H 'Content-Type: application/json' \
  --data @"$(dirname "$0")/routes-invalid.json" "$GW/admin/config" || true
curl -s -o /dev/null -w 'old /orders still works -> HTTP %{http_code}\n' \
  -H 'X-Caller-Id: zoe' "$GW/orders/1"

hr "6. atomic hot reload: valid batch -> 200, version advances, new route live"
curl -s -X PUT -H 'Content-Type: application/json' \
  --data @"$(dirname "$0")/routes-v2.json" "$GW/admin/config"; echo
curl -s -H 'X-Caller-Id: zoe' "$GW/payments/pay-1"; echo
