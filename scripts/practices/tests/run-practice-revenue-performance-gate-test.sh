#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/../../.." && pwd -P)"
gate="$root/scripts/practices/run-practice-revenue-performance-gate.sh"
profile="$root/scripts/practices/fixtures/practice-revenue-performance-profile-v1.json"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/bin" "$tmp/evidence"

requested_by='11111111-1111-4111-8111-111111111111'
token='stub.secret.token'
target_sha='0123456789012345678901234567890123456789'
task_definition='arn:aws:ecs:eu-west-1:111111111111:task-definition/practices-fixture:7'
image_digest='sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
fixture_db_hash='bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'
live_db_hash='cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc'
printf '%s\n' "$requested_by" >"$tmp/requested-by"
printf '%s\n' "$token" >"$tmp/token"
chmod 600 "$tmp/requested-by" "$tmp/token"

jq -n --slurpfile p "$profile" '
  {schemaVersion:1,productionShapedFixture:true,fixtureProfileVersion:1,
   fixtureSeedCounts:{completedInvoiceMonths:60,companyCount:3,
     consultants:$p[0].consultants,documents:$p[0].documents,itemControls:$p[0].itemControls,
     allocationRowCount:150000,dependencyRowCount:250000,canonicalDeliveryRowCount:450000,
     datedCapacityRowCount:420000,employeeMonthSalaryRowCount:19200,
     companyMonthControls:$p[0].companyMonthControls},
   valuationDistribution:$p[0].valuationDistribution,
   attributionDistribution:$p[0].attributionDistribution,
   segmentDistribution:$p[0].segmentDistribution,
   latencyEndpoint:"POST /work",latencyClientSettings:{keepAlive:true,concurrency:1},
   baselineWarmupCount:250,candidateWarmupCount:250,baselineSampleCount:2000,
   candidateLatencySampleCount:4800,totalWriteCount:10000,
   writeDistribution:$p[0].workload.writeDistribution,writeRatePerSecond:1.1,writeBurst:1,
   percentileMethod:"NEAREST_RANK_INTEGER_MICROSECONDS",phaseOverlap:true,
   costBasisRunCount:3,revenueRunCount:3,maxCostBasisSeconds:1440,maxRevenueSeconds:1440,
   costBasisRunDurationsSeconds:[120,121,122],revenueRunDurationsSeconds:[240,241,242],
   maxP95AddedMs:5,maxP95AddedPercent:10,maxP99LockMs:10,maxLockReleaseSeconds:5,
   observedBaselineP95Micros:1000,observedCandidateP95Micros:1050,
   observedP95AddedMs:0.05,observedP95AddedPercent:5,observedP99LockMs:2,
   observedMaxLockReleaseSeconds:1,deadlockCount:0,currentMonthEndpointStayedAvailable:true,
   historicalChangeRepublished:true,fixtureCleanupPassed:true,passed:true}
' >"$tmp/run-response.json"

cat >"$tmp/bin/curl" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
printf 'curl' >>"$STUB_LOG"
printf ' %q' "$@" >>"$STUB_LOG"
printf '\n' >>"$STUB_LOG"
url='' output='' data=''
while (($#)); do
  case "$1" in
    --url) url="$2"; shift 2 ;;
    --output) output="$2"; shift 2 ;;
    --data-binary) data="$2"; shift 2 ;;
    --config|--connect-timeout|--max-time|--request|--header|--write-out) shift 2 ;;
    --silent|--show-error) shift ;;
    *) shift ;;
  esac
done
case "$url" in
  https://fixture.invalid*/identity)
    jq -n --arg sha "$STUB_TARGET_SHA" --arg task "$STUB_TASK" --arg digest "$STUB_DIGEST" \
      --arg db "$STUB_FIXTURE_DB_HASH" \
      '{fixtureOnly:true,backendSha:$sha,taskDefinitionArn:$task,imageDigest:$digest,
        serviceIdentity:"fixture-service",databaseIdentityHash:$db}' >"$output" ;;
  https://live.invalid*/identity)
    printf '%s\n' '{"serviceIdentity":"live-service"}' >"$output" ;;
  */seed)
    payload="${data#@}"
    tenant="$(jq -r '.fixtureTenant' "$payload")"
    fingerprint="$(jq -r '.profile.expectedSeedFingerprint' "$payload")"
    if [[ "${STUB_SEED_RESPONSE_MODE:-}" == malformed ]]; then
      printf '%s\n' 'not-json' >"$output"
    else
      jq -n --arg tenant "$tenant" --arg fingerprint "$fingerprint" \
        '{seeded:true,fixtureTenant:$tenant,productionShapedFixture:true,
          fixtureSeedFingerprint:$fingerprint,conservationPassed:true}' >"$output"
    fi ;;
  */run) cp "$STUB_RUN_RESPONSE" "$output" ;;
  */cleanup)
    payload="${data#@}"
    tenant="$(jq -r '.fixtureTenant' "$payload")"
    jq -n --arg tenant "$tenant" '{fixtureTenant:$tenant,cleanupPassed:true}' >"$output" ;;
  *) printf '%s\n' '{}' >"$output" ;;
esac
printf '200'
STUB

cat >"$tmp/bin/mariadb" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
printf 'mariadb' >>"$STUB_LOG"
printf ' %q' "$@" >>"$STUB_LOG"
printf '\n' >>"$STUB_LOG"
case " $* " in
  *" --login-path=fixture-db "*) printf '%s\n' "$STUB_FIXTURE_DB_HASH" ;;
  *" --login-path=live-db "*) printf '%s\n' "$STUB_LIVE_DB_HASH" ;;
  *) exit 17 ;;
esac
STUB

cat >"$tmp/bin/clock" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${STUB_CLOCK_MODE:-}" == advancing ]]; then
  case "$*" in
    *%s*)
      count=0
      [[ ! -f "$STUB_CLOCK_STATE" ]] || read -r count <"$STUB_CLOCK_STATE"
      printf '%s\n' "$((count + 1))" >"$STUB_CLOCK_STATE"
      if ((count == 0)); then printf '%s\n' 1784160000; else printf '%s\n' 1784170800; fi
      ;;
    *) printf '%s\n' 2026-07-16T03:00:00Z ;;
  esac
  exit 0
fi
case "$*" in
  *%s*) printf '%s\n' 1784179200 ;;
  *) printf '%s\n' 2026-07-16T00:00:00Z ;;
esac
STUB
chmod +x "$tmp/bin/curl" "$tmp/bin/mariadb" "$tmp/bin/clock"

export STUB_LOG="$tmp/tools.log"
export STUB_RUN_RESPONSE="$tmp/run-response.json"
export STUB_TARGET_SHA="$target_sha"
export STUB_TASK="$task_definition"
export STUB_DIGEST="$image_digest"
export STUB_FIXTURE_DB_HASH="$fixture_db_hash"
export STUB_LIVE_DB_HASH="$live_db_hash"
export PRACTICES_CURL_BIN="$tmp/bin/curl"
export PRACTICES_MARIADB_BIN="$tmp/bin/mariadb"
export PRACTICES_CLOCK_BIN="$tmp/bin/clock"
export PRACTICES_PERFORMANCE_DB_LOGIN_PATH=fixture-db
common=(
  --environment staging --purpose INITIAL_ROLLOUT
  --expected-backend-sha "$target_sha"
  --fixture-profile "$profile"
  --fixture-task-definition-arn "$task_definition"
  --fixture-image-digest "$image_digest"
  --base-url https://fixture.invalid
  --reject-live-base-url https://live.invalid
  --reject-live-database-login-path live-db
  --requested-by-file "$tmp/requested-by"
  --system-token-file "$tmp/token"
  --cost-basis-runs 3 --revenue-runs 3 --writes-total 10000
  --baseline-warmup 250 --candidate-warmup 250
  --baseline-samples 2000 --candidate-latency-samples 4800
  --write-rate-per-second 1.1 --write-burst 1
  --percentile-method NEAREST_RANK_INTEGER_MICROSECONDS
  --max-cost-basis-seconds 1440 --max-revenue-seconds 1440
  --max-p95-added-ms 5 --max-p95-added-percent 10 --max-p99-lock-ms 10
  --max-lock-release-seconds 5
)

expect_failure() {
  local label="$1"; shift
  if "$@" >"$tmp/stdout" 2>"$tmp/stderr"; then
    printf '%s\n' "$label unexpectedly passed" >&2
    exit 1
  fi
}

expect_failure 'missing arguments' "$gate"
expect_failure 'production target' env PRACTICES_GATE_OUTPUT="$tmp/evidence/production.json" \
  "$gate" --environment production "${common[@]:2}"
expect_failure 'legacy fixture-base-url alias' env PRACTICES_GATE_OUTPUT="$tmp/evidence/legacy.json" \
  "$gate" "${common[@]}" --fixture-base-url https://fixture.invalid
expect_failure 'recovery without class' env PRACTICES_GATE_OUTPUT="$tmp/evidence/recovery.json" \
  "$gate" --environment staging --purpose RECOVERY "${common[@]:4}"

same_service=("${common[@]}")
for ((i=0; i<${#same_service[@]}; i++)); do
  [[ "${same_service[i]}" == --reject-live-base-url ]] && same_service[i+1]=https://fixture.invalid
done
expect_failure 'live service target' env PRACTICES_GATE_OUTPUT="$tmp/evidence/same-service.json" \
  "$gate" "${same_service[@]}"

expect_failure 'live database target' env PRACTICES_GATE_OUTPUT="$tmp/evidence/same-db.json" \
  PRACTICES_PERFORMANCE_DB_LOGIN_PATH=live-db "$gate" "${common[@]}"

chmod 640 "$tmp/token"
expect_failure 'group-readable token' env PRACTICES_GATE_OUTPUT="$tmp/evidence/mode.json" \
  "$gate" "${common[@]}"
chmod 600 "$tmp/token"

scaled=("${common[@]}")
for ((i=0; i<${#scaled[@]}; i++)); do
  [[ "${scaled[i]}" == --writes-total ]] && scaled[i+1]=9999
done
expect_failure 'scaled workload' env PRACTICES_GATE_OUTPUT="$tmp/evidence/scaled.json" \
  "$gate" "${scaled[@]}"

cp "$profile" "$tmp/profile-copy.json"
copied=("${common[@]}")
for ((i=0; i<${#copied[@]}; i++)); do
  [[ "${copied[i]}" == --fixture-profile ]] && copied[i+1]="$tmp/profile-copy.json"
done
expect_failure 'operator profile copy' env PRACTICES_GATE_OUTPUT="$tmp/evidence/copied.json" \
  "$gate" "${copied[@]}"

: >"$STUB_LOG"
export STUB_SEED_RESPONSE_MODE=malformed
export PRACTICES_GATE_OUTPUT="$tmp/evidence/malformed-seed.json"
expect_failure 'malformed committed seed response' "$gate" "${common[@]}"
grep -q '/cleanup' "$STUB_LOG"
[[ ! -e "$PRACTICES_GATE_OUTPUT" ]]
unset STUB_SEED_RESPONSE_MODE

: >"$STUB_LOG"
export STUB_CLOCK_MODE=advancing
export STUB_CLOCK_STATE="$tmp/clock-state"
export PRACTICES_GATE_OUTPUT="$tmp/evidence/pass-one.json"
"$gate" "${common[@]}" >"$tmp/success-output"
jq -e '.passed == true and .fixtureCleanupPassed == true and .environment == "staging" and
  .capturedAt == "2026-07-16T03:00:00Z"' \
  "$PRACTICES_GATE_OUTPUT" >/dev/null
[[ "$(stat -f '%Lp' "$PRACTICES_GATE_OUTPUT" 2>/dev/null || stat -c '%a' "$PRACTICES_GATE_OUTPUT")" == 600 ]]
unset STUB_CLOCK_MODE STUB_CLOCK_STATE

seed_line="$(grep -n '/seed' "$STUB_LOG" | head -1 | cut -d: -f1)"
run_line="$(grep -n '/run' "$STUB_LOG" | head -1 | cut -d: -f1)"
cleanup_line="$(grep -n '/cleanup' "$STUB_LOG" | head -1 | cut -d: -f1)"
((seed_line < run_line && run_line < cleanup_line))
if grep -Fq "$token" "$STUB_LOG" || grep -Fq "$requested_by" "$STUB_LOG"; then
  printf '%s\n' 'secret value leaked into tool arguments' >&2
  exit 1
fi
live_db_line="$(grep 'mariadb --login-path=live-db' "$STUB_LOG")"
[[ "$live_db_line" == *database-identity-read-only* ]]
if [[ "$live_db_line" =~ (CALL|INSERT|UPDATE|DELETE|CREATE|ALTER|DROP|TRUNCATE) ]]; then
  printf '%s\n' 'live database received a write' >&2
  exit 1
fi

first_id="$(jq -r '.evidenceId' "$PRACTICES_GATE_OUTPUT")"
export PRACTICES_GATE_OUTPUT="$tmp/evidence/pass-two.json"
"$gate" "${common[@]}" >/dev/null
second_id="$(jq -r '.evidenceId' "$PRACTICES_GATE_OUTPUT")"
[[ "$first_id" != "$second_id" ]]
expect_failure 'existing evidence file overwrite' "$gate" "${common[@]}"

invalid_numeric_fields=(
  observedBaselineP95Micros observedCandidateP95Micros observedP95AddedMs
  observedP95AddedPercent observedP99LockMs observedMaxLockReleaseSeconds
)
for ((i=0; i<${#invalid_numeric_fields[@]}; i++)); do
  field="${invalid_numeric_fields[i]}"
  invalid_value=null
  ((i % 2 == 1)) && invalid_value=false
  jq --arg field "$field" --argjson value "$invalid_value" '.[$field]=$value' \
    "$tmp/run-response.json" >"$tmp/invalid-$field.json"
  export STUB_RUN_RESPONSE="$tmp/invalid-$field.json"
  export PRACTICES_GATE_OUTPUT="$tmp/evidence/invalid-$field.json"
  expect_failure "$field rejects $invalid_value" "$gate" "${common[@]}"
  [[ ! -e "$PRACTICES_GATE_OUTPUT" ]]
done

jq '.passed=false' "$tmp/run-response.json" >"$tmp/failed-response.json"
export STUB_RUN_RESPONSE="$tmp/failed-response.json"
export PRACTICES_GATE_OUTPUT="$tmp/evidence/failed-result.json"
expect_failure 'failed fixture result' "$gate" "${common[@]}"
grep -q '/cleanup' "$STUB_LOG"
[[ ! -e "$PRACTICES_GATE_OUTPUT" ]]

printf '%s\n' 'performance gate direct-orchestration contract tests passed'
