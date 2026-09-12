#!/usr/bin/env bash
#
# CareerFlux deployment smoke test.
#
# Checks the things that are cheap to check automatically and expensive to get
# wrong: is the app served, does the SPA survive a refresh, is the API reachable
# through the proxy, is anything exposed that should not be, does sign-in work,
# is the limiter live, did demo data appear.
#
# It deliberately does NOT try to test the product. Whether candidate discovery
# ranks sensibly, whether a shortlist reads correctly to a placement officer —
# those need a person looking at a screen, and are listed as manual steps in
# docs/SMOKE-TEST.md. A script that clicked through them would report success
# without anybody having looked.
#
#   BASE_URL=http://127.0.0.1:8081 ./scripts/smoke-test.sh
#
# Optional, to exercise an authenticated path:
#   SMOKE_EMAIL=... SMOKE_PASSWORD=... ./scripts/smoke-test.sh
#
# Exits non-zero if any check fails.
set -uo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:8081}"
BACKEND_CONTAINER="${CAREERFLUX_BACKEND_CONTAINER:-careerflux-backend}"
POSTGRES_CONTAINER="${CAREERFLUX_POSTGRES_CONTAINER:-careerflux-postgres}"

passed=0
failed=0

ok()   { printf '  \033[32mPASS\033[0m  %s\n' "$1"; passed=$((passed + 1)); }
bad()  { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; failed=$((failed + 1)); }
note() { printf '  ....  %s\n' "$1"; }

check() { # check <description> <expected> <actual>
    if [ "$2" = "$3" ]; then ok "$1"; else bad "$1 (expected $2, got $3)"; fi
}

status() { curl -s -o /dev/null -w '%{http_code}' "$@"; }

echo "CareerFlux smoke test against ${BASE_URL}"
echo

# --- 1. the app is served ---------------------------------------------------
echo "Serving"
check "frontend responds" 200 "$(status "${BASE_URL}/")"
body="$(curl -s "${BASE_URL}/")"
case "$body" in
    *'<div id="root"'*) ok "index.html is the SPA shell" ;;
    *)                  bad "index.html does not contain the app root" ;;
esac
check "nginx health endpoint" 200 "$(status "${BASE_URL}/healthz")"

# --- 2. SPA routing ---------------------------------------------------------
# The failure this catches: a deep link 404s after a browser refresh, which is
# what happens when try_files is missing and is invisible until a user does it.
echo
echo "Client routing"
for route in /app /app/discover /app/profile /app/requirements; do
    check "deep link ${route} serves the app" 200 "$(status "${BASE_URL}${route}")"
done
routed="$(curl -s "${BASE_URL}/app/discover")"
if [ "$routed" = "$body" ]; then ok "deep link returns the SPA shell, not a 404 page"
else bad "deep link body differs from index.html"; fi

# --- 3. the API is reachable through the proxy ------------------------------
echo
echo "API"
# 401, not 404 or 502: 404 means nginx is not proxying, 502 means the backend is
# not up, 401 means the whole path works and security is doing its job.
check "protected endpoint requires authentication" 401 "$(status "${BASE_URL}/api/auth/me")"
# 401 rather than 404, deliberately: the security chain refuses an unknown /api
# path before routing, so the API cannot be enumerated by watching which paths
# 404. Either is acceptable; a 200, or an unstructured error page, is not.
unknown_code="$(status "${BASE_URL}/api/there-is-no-such-thing")"
case "$unknown_code" in
    401|404) ok "unknown API path is refused (${unknown_code}) without revealing whether it exists" ;;
    *)       bad "unknown API path answered ${unknown_code}" ;;
esac
case "$(curl -s "${BASE_URL}/api/there-is-no-such-thing")" in
    *'"code"'*) ok "API errors use the structured error shape" ;;
    *)          bad "API error is not the structured shape — a container default page?" ;;
esac

# --- 4. nothing extra is exposed --------------------------------------------
echo
echo "Exposure"
# What matters is not the status code but whether backend tooling ANSWERS.
# nginx proxies only /api, so everything below falls through to the SPA
# catch-all and returns index.html with a 200 — which is correct, and is why
# asserting 404 here would have been asserting the wrong thing. The real check
# is that none of these responses contain the tooling itself.
for path in /actuator/health /actuator/env /actuator/metrics /swagger-ui.html /v3/api-docs /h2-console; do
    resp="$(curl -s "${BASE_URL}${path}")"
    case "$resp" in
        *'"status":"UP"'*|*'"openapi"'*|*'swagger'*|*'H2 Console'*|*'jdbc:'*|*'"propertySources"'*)
            bad "${path} returned developer tooling through the public origin" ;;
        *)  ok "${path} does not reach the backend" ;;
    esac
done
map_code="$(status "${BASE_URL}/assets/index.js.map")"
check "source maps are refused" 404 "${map_code}"

# --- 5. security headers ----------------------------------------------------
echo
echo "Headers"
headers="$(curl -s -D - -o /dev/null "${BASE_URL}/")"
for header in "X-Content-Type-Options: nosniff" "X-Frame-Options: DENY" "Referrer-Policy:"; do
    if printf '%s' "$headers" | grep -qi "$header"; then ok "sends ${header%%:*}"
    else bad "missing ${header%%:*}"; fi
done
if printf '%s' "$headers" | grep -qi '^server: nginx/[0-9]'; then
    bad "Server header advertises the nginx version"
else ok "Server header does not advertise a version" ; fi

# --- 6. backend health ------------------------------------------------------
echo
echo "Backend"
if health="$(docker exec "${BACKEND_CONTAINER}" wget -qO- http://localhost:8080/actuator/health 2>/dev/null)"; then
    case "$health" in
        *'"status":"UP"'*) ok "actuator health is UP (database reachable)" ;;
        *)                 bad "actuator health is not UP: ${health}" ;;
    esac
    case "$health" in
        *components*) bad "health exposes component detail to an unauthenticated caller" ;;
        *)            ok "health withholds component detail when unauthenticated" ;;
    esac
else
    bad "could not reach the backend health endpoint in ${BACKEND_CONTAINER}"
fi

# --- 7. demo safety ---------------------------------------------------------
echo
echo "Demo safety"
if profiles="$(docker exec "${BACKEND_CONTAINER}" printenv SPRING_PROFILES_ACTIVE 2>/dev/null)"; then
    # Matched as whole names in a comma-separated list, so `prod` means the
    # profile and not a substring of some other name.
    case ",$(printf '%s' "$profiles" | tr -d ' ')," in
        *,dev,*|*,demo,*|*,test,*) bad "active profiles include dev, demo or test: ${profiles}" ;;
        *,prod,*)                  ok "active profiles are ${profiles}" ;;
        *)                         bad "active profiles do not include prod: ${profiles}" ;;
    esac
fi
for pair in "CAREERFLUX_DEMO_SEED_SAMPLE_JOBS=false" \
            "CAREERFLUX_DEMO_ALLOW_SEEDING_INTO_POPULATED_CORPUS=false" \
            "CAREERFLUX_INGESTION_SCHEDULER_ENABLED=false"; do
    name="${pair%%=*}"; want="${pair##*=}"
    got="$(docker exec "${BACKEND_CONTAINER}" printenv "${name}" 2>/dev/null)"
    check "${name} is ${want}" "${want}" "${got:-unset}"
done

# --- 8. rate limiting -------------------------------------------------------
echo
echo "Rate limiting"
# Deliberately against an address that does not exist, so this cannot lock a
# real person out of their own account while smoke-testing a deployment.
probe="smoke-probe-$(date +%s)@example.invalid"
limited=no
for _ in $(seq 1 14); do
    code="$(curl -s -o /dev/null -w '%{http_code}' -X POST "${BASE_URL}/api/auth/login" \
        -H 'Content-Type: application/json' \
        -d "{\"email\":\"${probe}\",\"password\":\"not-the-password\"}")"
    if [ "$code" = "429" ]; then limited=yes; break; fi
done
if [ "$limited" = yes ]; then ok "sign-in is rate limited"
else bad "sign-in was never refused after 14 attempts"; fi

# --- 9. an authenticated round trip (optional) ------------------------------
echo
echo "Authenticated path"
if [ -n "${SMOKE_EMAIL:-}" ] && [ -n "${SMOKE_PASSWORD:-}" ]; then
    login="$(curl -s -X POST "${BASE_URL}/api/auth/login" -H 'Content-Type: application/json' \
        -d "{\"email\":\"${SMOKE_EMAIL}\",\"password\":\"${SMOKE_PASSWORD}\"}")"
    token="$(printf '%s' "$login" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')"
    if [ -n "$token" ]; then
        ok "sign-in returns an access token"
        me="$(curl -s "${BASE_URL}/api/auth/me" -H "Authorization: Bearer ${token}")"
        case "$me" in
            *'"email"'*) ok "authenticated request to /api/auth/me succeeds" ;;
            *)           bad "/api/auth/me did not return a session: ${me}" ;;
        esac
        # Never log the token itself.
        case "$me" in
            *"$SMOKE_PASSWORD"*) bad "a response echoed the password" ;;
            *)                   ok "no response echoed the password" ;;
        esac
    else
        bad "sign-in did not return a token (rate limited from an earlier run? wait 5 minutes)"
    fi
else
    note "SMOKE_EMAIL / SMOKE_PASSWORD not set — skipping the authenticated round trip"
fi

# --- 10. schema -------------------------------------------------------------
echo
echo "Schema"
if version="$(docker exec "${POSTGRES_CONTAINER}" psql -U careerflux -d careerflux -tA \
        -c "SELECT max(version::numeric) FROM flyway_schema_history WHERE success;" 2>/dev/null)"; then
    ok "schema is at V$(printf '%s' "$version" | tr -d ' ')"
    failedm="$(docker exec "${POSTGRES_CONTAINER}" psql -U careerflux -d careerflux -tA \
        -c "SELECT count(*) FROM flyway_schema_history WHERE NOT success;" 2>/dev/null | tr -d ' ')"
    check "no failed migrations" 0 "${failedm}"
fi

echo
echo "-----------------------------------------------------------"
printf 'passed %d, failed %d\n' "$passed" "$failed"
if [ "$failed" -gt 0 ]; then
    echo "Deployment is NOT verified. See docs/SMOKE-TEST.md for the manual steps too."
    exit 1
fi
echo "Automated checks passed. The manual checklist in docs/SMOKE-TEST.md still applies:"
echo "a deployment is not verified until a person has signed in and looked."
