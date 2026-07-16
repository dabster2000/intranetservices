#!/usr/bin/env bash
set -euo pipefail
umask 077

readonly SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd -P)"
readonly REPOSITORY_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd -P)"
readonly COMMITTED_PROFILE="$SCRIPT_DIR/fixtures/practice-revenue-performance-profile-v1.json"
readonly FIXTURE_IDENTITY_PATH="/system/practices-performance-fixture/identity"
readonly FIXTURE_SEED_PATH="/system/practices-performance-fixture/seed"
readonly FIXTURE_RUN_PATH="/system/practices-performance-fixture/run"
readonly FIXTURE_CLEANUP_PATH="/system/practices-performance-fixture/cleanup"

die() {
  printf '%s\n' "practice performance gate: $1" >&2
  exit 2
}

require_value() {
  [[ -n "${2:-}" && "${2:-}" != --* ]] || die "missing value for $1"
}

require_tool() {
  command -v "$1" >/dev/null 2>&1 || die "required tool is unavailable: $2"
}

private_mode() {
  stat -f '%Lp' "$1" 2>/dev/null || stat -c '%a' "$1" 2>/dev/null
}

require_private_file() {
  local file="$1" label="$2" mode
  [[ -f "$file" && -r "$file" ]] || die "$label must be a readable regular file"
  mode="$(private_mode "$file")"
  [[ "$mode" =~ ^0*[46]00$ ]] || die "$label must not grant group or other permissions"
}

canonical_path() {
  local path="$1"
  (cd "$(dirname "$path")" && printf '%s/%s\n' "$(pwd -P)" "$(basename "$path")")
}

sha256_file() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    sha256sum "$1" | awk '{print $1}'
  fi
}

sha256_text() {
  if command -v shasum >/dev/null 2>&1; then
    printf '%s' "$1" | shasum -a 256 | awk '{print $1}'
  else
    printf '%s' "$1" | sha256sum | awk '{print $1}'
  fi
}

normalize_url() {
  local value="$1"
  while [[ "$value" == */ ]]; do value="${value%/}"; done
  printf '%s\n' "$value"
}

require_https_url() {
  [[ "$1" =~ ^https://[^/@[:space:]]+([/:][^@[:space:]]*)?$ ]] \
    || die "$2 must be an HTTPS URL without embedded credentials"
}

require_exact_positive_integer() {
  [[ "$1" =~ ^[1-9][0-9]*$ && "$1" == "$2" ]] || die "$3 must equal $2"
}

require_exact_decimal() {
  [[ "$1" == "$2" ]] || die "$3 must equal $2"
}

environment=""
purpose=""
recovery_class=""
target_sha=""
base_url=""
live_url=""
live_db_login_path=""
requested_by_file=""
token_file=""
profile=""
task_definition=""
image_digest=""
output="${PRACTICES_GATE_OUTPUT:-}"
cost_basis_runs=""
revenue_runs=""
writes_total=""
baseline_warmup=""
candidate_warmup=""
baseline_samples=""
candidate_samples=""
write_rate=""
write_burst=""
percentile_method=""
max_cost_seconds=""
max_revenue_seconds=""
max_p95_ms=""
max_p95_percent=""
max_p99_lock_ms=""
max_lock_release_seconds=""

while (($#)); do
  case "$1" in
    --environment) require_value "$1" "${2:-}"; [[ -z "$environment" ]] || die "duplicate argument $1"; environment="$2"; shift 2 ;;
    --purpose) require_value "$1" "${2:-}"; [[ -z "$purpose" ]] || die "duplicate argument $1"; purpose="$2"; shift 2 ;;
    --recovery-class) require_value "$1" "${2:-}"; [[ -z "$recovery_class" ]] || die "duplicate argument $1"; recovery_class="$2"; shift 2 ;;
    --expected-backend-sha) require_value "$1" "${2:-}"; [[ -z "$target_sha" ]] || die "duplicate argument $1"; target_sha="$2"; shift 2 ;;
    --base-url) require_value "$1" "${2:-}"; [[ -z "$base_url" ]] || die "duplicate argument $1"; base_url="$2"; shift 2 ;;
    --reject-live-base-url) require_value "$1" "${2:-}"; [[ -z "$live_url" ]] || die "duplicate argument $1"; live_url="$2"; shift 2 ;;
    --reject-live-database-login-path) require_value "$1" "${2:-}"; [[ -z "$live_db_login_path" ]] || die "duplicate argument $1"; live_db_login_path="$2"; shift 2 ;;
    --requested-by-file) require_value "$1" "${2:-}"; [[ -z "$requested_by_file" ]] || die "duplicate argument $1"; requested_by_file="$2"; shift 2 ;;
    --system-token-file) require_value "$1" "${2:-}"; [[ -z "$token_file" ]] || die "duplicate argument $1"; token_file="$2"; shift 2 ;;
    --fixture-profile) require_value "$1" "${2:-}"; [[ -z "$profile" ]] || die "duplicate argument $1"; profile="$2"; shift 2 ;;
    --fixture-task-definition-arn) require_value "$1" "${2:-}"; [[ -z "$task_definition" ]] || die "duplicate argument $1"; task_definition="$2"; shift 2 ;;
    --fixture-image-digest) require_value "$1" "${2:-}"; [[ -z "$image_digest" ]] || die "duplicate argument $1"; image_digest="$2"; shift 2 ;;
    --cost-basis-runs) require_value "$1" "${2:-}"; cost_basis_runs="$2"; shift 2 ;;
    --revenue-runs) require_value "$1" "${2:-}"; revenue_runs="$2"; shift 2 ;;
    --writes-total) require_value "$1" "${2:-}"; writes_total="$2"; shift 2 ;;
    --baseline-warmup) require_value "$1" "${2:-}"; baseline_warmup="$2"; shift 2 ;;
    --candidate-warmup) require_value "$1" "${2:-}"; candidate_warmup="$2"; shift 2 ;;
    --baseline-samples) require_value "$1" "${2:-}"; baseline_samples="$2"; shift 2 ;;
    --candidate-latency-samples) require_value "$1" "${2:-}"; candidate_samples="$2"; shift 2 ;;
    --write-rate-per-second) require_value "$1" "${2:-}"; write_rate="$2"; shift 2 ;;
    --write-burst) require_value "$1" "${2:-}"; write_burst="$2"; shift 2 ;;
    --percentile-method) require_value "$1" "${2:-}"; percentile_method="$2"; shift 2 ;;
    --max-cost-basis-seconds) require_value "$1" "${2:-}"; max_cost_seconds="$2"; shift 2 ;;
    --max-revenue-seconds) require_value "$1" "${2:-}"; max_revenue_seconds="$2"; shift 2 ;;
    --max-p95-added-ms) require_value "$1" "${2:-}"; max_p95_ms="$2"; shift 2 ;;
    --max-p95-added-percent) require_value "$1" "${2:-}"; max_p95_percent="$2"; shift 2 ;;
    --max-p99-lock-ms) require_value "$1" "${2:-}"; max_p99_lock_ms="$2"; shift 2 ;;
    --max-lock-release-seconds) require_value "$1" "${2:-}"; max_lock_release_seconds="$2"; shift 2 ;;
    *) die "unknown argument $1" ;;
  esac
done

[[ "$environment" == staging ]] || die "the isolated performance gate is staging-only"
[[ "$purpose" == INITIAL_ROLLOUT || "$purpose" == RECOVERY ]] || die "invalid purpose"
if [[ "$purpose" == RECOVERY ]]; then
  [[ "$recovery_class" == REVENUE_ONLY || "$recovery_class" == COST_BASIS ]] \
    || die "recovery class required"
else
  [[ -z "$recovery_class" ]] || die "initial rollout cannot use a recovery class"
fi
[[ "$target_sha" =~ ^[a-f0-9]{40}$ ]] || die "expected backend SHA must be 40 lowercase hex characters"
require_https_url "$base_url" "base URL"
require_https_url "$live_url" "rejected live base URL"
base_url="$(normalize_url "$base_url")"
live_url="$(normalize_url "$live_url")"
[[ "$base_url" != "$live_url" ]] || die "fixture and live service targets must differ"

fixture_db_login_path="${PRACTICES_PERFORMANCE_DB_LOGIN_PATH:-}"
[[ -n "$fixture_db_login_path" ]] || die "PRACTICES_PERFORMANCE_DB_LOGIN_PATH is required"
[[ -n "$live_db_login_path" && "$fixture_db_login_path" != "$live_db_login_path" ]] \
  || die "fixture and live database login paths must differ"
require_private_file "$requested_by_file" "requested-by file"
require_private_file "$token_file" "system-token file"
[[ "$(canonical_path "$profile")" == "$(canonical_path "$COMMITTED_PROFILE")" ]] \
  || die "fixture profile must be the committed production-shaped profile"
[[ -n "$task_definition" ]] || die "fixture task definition ARN is required"
[[ "$image_digest" =~ ^sha256:[a-f0-9]{64}$ ]] || die "immutable fixture image digest is required"

require_exact_positive_integer "$cost_basis_runs" 3 "cost-basis runs"
require_exact_positive_integer "$revenue_runs" 3 "revenue runs"
require_exact_positive_integer "$writes_total" 10000 "total writes"
require_exact_positive_integer "$baseline_warmup" 250 "baseline warm-up"
require_exact_positive_integer "$candidate_warmup" 250 "candidate warm-up"
require_exact_positive_integer "$baseline_samples" 2000 "baseline samples"
require_exact_positive_integer "$candidate_samples" 4800 "candidate latency samples"
require_exact_decimal "$write_rate" 1.1 "write rate"
require_exact_positive_integer "$write_burst" 1 "write burst"
[[ "$percentile_method" == NEAREST_RANK_INTEGER_MICROSECONDS ]] || die "invalid percentile method"
require_exact_positive_integer "$max_cost_seconds" 1440 "maximum cost-basis seconds"
require_exact_positive_integer "$max_revenue_seconds" 1440 "maximum revenue seconds"
require_exact_positive_integer "$max_p95_ms" 5 "maximum p95 added milliseconds"
require_exact_positive_integer "$max_p95_percent" 10 "maximum p95 added percent"
require_exact_positive_integer "$max_p99_lock_ms" 10 "maximum p99 lock milliseconds"
require_exact_positive_integer "$max_lock_release_seconds" 5 "maximum lock-release seconds"

[[ -n "$output" && ! -e "$output" ]] || die "PRACTICES_GATE_OUTPUT must name a new evidence file"
output="$(canonical_path "$output")"
case "$output" in
  "$REPOSITORY_ROOT"/*) die "evidence output must be outside the repository" ;;
esac
[[ -d "$(dirname "$output")" && -w "$(dirname "$output")" ]] \
  || die "evidence output directory must already exist and be writable"

curl_bin="${PRACTICES_CURL_BIN:-curl}"
mariadb_bin="${PRACTICES_MARIADB_BIN:-mariadb}"
clock_bin="${PRACTICES_CLOCK_BIN:-date}"
require_tool "$curl_bin" curl
require_tool "$mariadb_bin" mariadb
require_tool "$clock_bin" clock
require_tool jq jq

jq -e '
  .schemaVersion == 1 and .profileVersion == 1 and
  .fixedSeed == "PRACTICES_CONTRIBUTION_PERFORMANCE_V1" and
  .generatorAlgorithm == "SHA256_COUNTER_CANONICAL_JSON_V1" and
  .expectedSeedFingerprint == "9be865e94ec6047ffc46a463042e722f39e15442470dfe067c1102b88547899d" and
  .completedInvoiceMonths == 60 and .companyCount == 3 and
  .consultants == {internal:320,external:40} and
  .documents == {total:15000,INVOICE:12000,PHANTOM:1800,CREDIT_NOTE:1200} and
  .itemControls == {total:60000,DELIVERY_BASE:42000,CALCULATED:6000,NEGATIVE_ADJUSTMENT:9000,POSITIVE_FEE:3000} and
  .allocationRowCount == 150000 and .dependencyRowCount == 250000 and
  .canonicalDeliveryRowCount == 450000 and .datedCapacityRowCount == 420000 and
  .employeeMonthSalaryRowCount == 19200 and .companyMonthControls == {SALARY:180,OPEX:180} and
  .valuationDistribution == {UNIQUE_GL:13500,PROVISIONAL:1200,UNAVAILABLE_OR_AMBIGUOUS:300} and
  .attributionDistribution == {CONFIRMED:105000,ESTIMATED:30000,PARTIAL_OR_RESIDUAL:15000} and
  .segmentDistribution == {PM:25000,BA:24000,CYB:15000,DEV:22000,SA:20000,JK:12000,UD:10000,EXTERNAL:8000,OTHER:6000,UNASSIGNED:8000} and
  .workload == {baselineWarmupCount:250,candidateWarmupCount:250,baselineSampleCount:2000,
    candidateLatencySampleCount:4800,totalWriteCount:10000,ratePerSecond:1.1,burst:1,
    percentileMethod:"NEAREST_RANK_INTEGER_MICROSECONDS",
    writeDistribution:{INVOICE_DOCUMENT:1500,FINANCE_GL:500,CURRENCY:100,
      ACCOUNT_CLASSIFICATION:100,INVOICE_ATTRIBUTION:1500,SELF_BILLED:500,
      PHANTOM_ATTRIBUTION:500,DELIVERY_EVIDENCE:4800,PRACTICE_BASIS_INPUT:500}} and
  .thresholds == {costBasisRunCount:3,revenueRunCount:3,maxPhaseSeconds:1440,
    jobDeadlineSeconds:1800,maxP95AddedMs:5,maxP95AddedPercent:10,maxP99LockMs:10,
    maxLockReleaseSeconds:5,maxDeadlockCount:0}
' "$profile" >/dev/null || die "fixture profile differs from the immutable production-shaped contract"

profile_sha="$(sha256_file "$profile")"
seed_fingerprint="$(jq -er '.expectedSeedFingerprint' "$profile")"
now_epoch="$($clock_bin -u +%s)"
[[ "$now_epoch" =~ ^[0-9]+$ ]] || die "clock returned an invalid epoch"
request_hash="$(sha256_text "$output|$now_epoch|$$|$RANDOM")"
evidence_id="${request_hash:0:8}-${request_hash:8:4}-4${request_hash:13:3}-a${request_hash:17:3}-${request_hash:20:12}"
fixture_tenant="practices_perf_${request_hash:0:24}"

tmp="$(mktemp -d)"
auth_config="$tmp/curl-auth"
cleanup_armed=false

cleanup_fixture() {
  if [[ "$cleanup_armed" == true ]]; then
    jq -n --arg tenant "$fixture_tenant" --arg requestId "$evidence_id" \
      '{fixtureTenant:$tenant,evidenceRequestId:$requestId}' >"$tmp/cleanup-request.json"
    set +e
    "$curl_bin" --config "$auth_config" --silent --show-error \
      --connect-timeout 10 --max-time 600 --request POST \
      --url "$base_url$FIXTURE_CLEANUP_PATH" --header 'Content-Type: application/json' \
      --data-binary "@$tmp/cleanup-request.json" --output "$tmp/cleanup-response.json" \
      --write-out '%{http_code}' >"$tmp/cleanup-status"
    set -e
    cleanup_armed=false
  fi
}

finish() {
  local status=$?
  cleanup_fixture
  rm -rf "$tmp"
  exit "$status"
}
trap finish EXIT HUP INT TERM

token="$(tr -d '\r\n' <"$token_file")"
requested_by="$(tr -d '\r\n' <"$requested_by_file")"
[[ "$token" =~ ^[A-Za-z0-9._~-]+$ ]] || die "system-token file contains an invalid token"
[[ "$requested_by" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ ]] \
  || die "requested-by file must contain one canonical lowercase UUID"
printf 'header = "Authorization: Bearer %s"\nheader = "X-Requested-By: %s"\n' \
  "$token" "$requested_by" >"$auth_config"
chmod 600 "$auth_config"

curl_json() {
  local auth="$1" method="$2" url="$3" payload="$4" destination="$5" max_time="$6"
  local status
  local command=("$curl_bin" --silent --show-error --connect-timeout 10 --max-time "$max_time"
    --request "$method" --url "$url" --header 'Accept: application/json'
    --output "$destination" --write-out '%{http_code}')
  [[ "$auth" == private ]] && command+=(--config "$auth_config")
  if [[ -n "$payload" ]]; then
    command+=(--header 'Content-Type: application/json' --data-binary "@$payload")
  fi
  status="$("${command[@]}")" || die "HTTP request failed for a fixture-control operation"
  [[ "$status" == 200 ]] || die "fixture-control operation returned HTTP $status"
  jq -e 'type == "object"' "$destination" >/dev/null || die "fixture-control response was not JSON"
}

curl_json public GET "$base_url$FIXTURE_IDENTITY_PATH" "" "$tmp/fixture-identity.json" 30
curl_json public GET "$live_url$FIXTURE_IDENTITY_PATH" "" "$tmp/live-identity.json" 30

fixture_service_identity="$(jq -er '.serviceIdentity | select(type=="string" and length>0)' "$tmp/fixture-identity.json")"
live_service_identity="$(jq -er '.serviceIdentity | select(type=="string" and length>0)' "$tmp/live-identity.json")"
[[ "$fixture_service_identity" != "$live_service_identity" ]] || die "fixture and live service identities match"
jq -e --arg sha "$target_sha" --arg task "$task_definition" --arg digest "$image_digest" '
  .fixtureOnly == true and .backendSha == $sha and .taskDefinitionArn == $task and
  .imageDigest == $digest and (.databaseIdentityHash | test("^[a-f0-9]{64}$"))
' "$tmp/fixture-identity.json" >/dev/null || die "fixture service identity does not match the reviewed deployment"

db_identity_query="/* practices:database-identity-read-only */ SELECT LOWER(SHA2(CONCAT(@@hostname, ':', DATABASE()), 256));"
fixture_db_identity="$("$mariadb_bin" --login-path="$fixture_db_login_path" --batch --raw \
  --skip-column-names --connect-timeout=10 --execute "$db_identity_query")"
live_db_identity="$("$mariadb_bin" --login-path="$live_db_login_path" --batch --raw \
  --skip-column-names --connect-timeout=10 --execute "$db_identity_query")"
[[ "$fixture_db_identity" =~ ^[a-f0-9]{64}$ && "$live_db_identity" =~ ^[a-f0-9]{64}$ ]] \
  || die "database identity query returned an invalid value"
[[ "$fixture_db_identity" != "$live_db_identity" ]] || die "fixture and live database identities match"
[[ "$(jq -r '.databaseIdentityHash' "$tmp/fixture-identity.json")" == "$fixture_db_identity" ]] \
  || die "fixture service and fixture database identities disagree"

jq -n --slurpfile profileJson "$profile" --arg tenant "$fixture_tenant" \
  --arg requestId "$evidence_id" --arg profileSha "$profile_sha" \
  --arg expectedSha "$target_sha" --arg task "$task_definition" --arg digest "$image_digest" '
  {fixtureTenant:$tenant,evidenceRequestId:$requestId,profile:$profileJson[0],
   fixtureProfileSha256:$profileSha,targetBackendSha:$expectedSha,
   fixtureTaskDefinitionArn:$task,fixtureImageDigest:$digest}
' >"$tmp/seed-request.json"
cleanup_armed=true
curl_json private POST "$base_url$FIXTURE_SEED_PATH" "$tmp/seed-request.json" "$tmp/seed-response.json" 1800
jq -e --arg tenant "$fixture_tenant" --arg fingerprint "$seed_fingerprint" '
  .seeded == true and .fixtureTenant == $tenant and .productionShapedFixture == true and
  .fixtureSeedFingerprint == $fingerprint and .conservationPassed == true
' "$tmp/seed-response.json" >/dev/null || die "fixture seed validation failed"

jq -n --arg tenant "$fixture_tenant" --arg requestId "$evidence_id" \
  --arg environment "$environment" --arg purpose "$purpose" --arg recoveryClass "$recovery_class" \
  --arg targetSha "$target_sha" --arg profileSha "$profile_sha" \
  --arg seedFingerprint "$seed_fingerprint" --arg task "$task_definition" \
  --arg digest "$image_digest" '
  {fixtureTenant:$tenant,evidenceRequestId:$requestId,environment:$environment,purpose:$purpose,
   recoveryClass:(if $recoveryClass=="" then null else $recoveryClass end),
   targetBackendSha:$targetSha,fixtureProfileVersion:1,fixtureProfileSha256:$profileSha,
   fixtureSeedFingerprint:$seedFingerprint,fixtureTaskDefinitionArn:$task,
   fixtureImageDigest:$digest,latencyEndpoint:"POST /work",
   latencyClientSettings:{keepAlive:true,concurrency:1},baselineWarmupCount:250,
   candidateWarmupCount:250,baselineSampleCount:2000,candidateLatencySampleCount:4800,
   totalWriteCount:10000,writeRatePerSecond:1.1,writeBurst:1,
   percentileMethod:"NEAREST_RANK_INTEGER_MICROSECONDS",costBasisRunCount:3,
   revenueRunCount:3,maxCostBasisSeconds:1440,maxRevenueSeconds:1440,
   maxP95AddedMs:5,maxP95AddedPercent:10,maxP99LockMs:10,maxLockReleaseSeconds:5}
' >"$tmp/run-request.json"
curl_json private POST "$base_url$FIXTURE_RUN_PATH" "$tmp/run-request.json" "$tmp/run-response.json" 10800

jq -n --arg tenant "$fixture_tenant" --arg requestId "$evidence_id" \
  '{fixtureTenant:$tenant,evidenceRequestId:$requestId}' >"$tmp/cleanup-request.json"
curl_json private POST "$base_url$FIXTURE_CLEANUP_PATH" "$tmp/cleanup-request.json" "$tmp/cleanup-response.json" 600
jq -e --arg tenant "$fixture_tenant" '.fixtureTenant == $tenant and .cleanupPassed == true' \
  "$tmp/cleanup-response.json" >/dev/null || die "fixture cleanup did not pass"
cleanup_armed=false

captured_at="$($clock_bin -u +%Y-%m-%dT%H:%M:%SZ)"
now_epoch="$($clock_bin -u +%s)"
[[ "$now_epoch" =~ ^[0-9]+$ ]] || die "clock returned an invalid epoch"
jq --arg evidenceId "$evidence_id" --arg capturedAt "$captured_at" \
  --arg environment "$environment" --arg purpose "$purpose" --arg recoveryClass "$recovery_class" \
  --arg targetSha "$target_sha" --arg profileSha "$profile_sha" \
  --arg seedFingerprint "$seed_fingerprint" --arg serviceIdentity "$fixture_service_identity" \
  --arg databaseIdentity "$fixture_db_identity" --arg task "$task_definition" --arg digest "$image_digest" '
  {schemaVersion:.schemaVersion,evidenceId:$evidenceId,environment:$environment,purpose:$purpose,
   targetBackendSha:$targetSha,recoveryClass:(if $recoveryClass=="" then null else $recoveryClass end),
   capturedAt:$capturedAt,productionShapedFixture:.productionShapedFixture,
   fixtureProfileVersion:.fixtureProfileVersion,fixtureProfileSha256:$profileSha,
   fixtureSeedFingerprint:$seedFingerprint,fixtureSeedCounts:.fixtureSeedCounts,
   valuationDistribution:.valuationDistribution,attributionDistribution:.attributionDistribution,
   segmentDistribution:.segmentDistribution,fixtureServiceIdentity:$serviceIdentity,
   fixtureDatabaseIdentityHash:$databaseIdentity,fixtureTaskDefinitionArn:$task,
   fixtureImageDigest:$digest,latencyEndpoint:.latencyEndpoint,
   latencyClientSettings:.latencyClientSettings,baselineWarmupCount:.baselineWarmupCount,
   candidateWarmupCount:.candidateWarmupCount,baselineSampleCount:.baselineSampleCount,
   candidateLatencySampleCount:.candidateLatencySampleCount,totalWriteCount:.totalWriteCount,
   writeDistribution:.writeDistribution,writeRatePerSecond:.writeRatePerSecond,
   writeBurst:.writeBurst,percentileMethod:.percentileMethod,phaseOverlap:.phaseOverlap,
   costBasisRunCount:.costBasisRunCount,revenueRunCount:.revenueRunCount,
   maxCostBasisSeconds:.maxCostBasisSeconds,maxRevenueSeconds:.maxRevenueSeconds,
   costBasisRunDurationsSeconds:.costBasisRunDurationsSeconds,
   revenueRunDurationsSeconds:.revenueRunDurationsSeconds,maxP95AddedMs:.maxP95AddedMs,
   maxP95AddedPercent:.maxP95AddedPercent,maxP99LockMs:.maxP99LockMs,
   maxLockReleaseSeconds:.maxLockReleaseSeconds,observedBaselineP95Micros:.observedBaselineP95Micros,
   observedCandidateP95Micros:.observedCandidateP95Micros,observedP95AddedMs:.observedP95AddedMs,
   observedP95AddedPercent:.observedP95AddedPercent,observedP99LockMs:.observedP99LockMs,
   observedMaxLockReleaseSeconds:.observedMaxLockReleaseSeconds,deadlockCount:.deadlockCount,
   currentMonthEndpointStayedAvailable:.currentMonthEndpointStayedAvailable,
   historicalChangeRepublished:.historicalChangeRepublished,fixtureCleanupPassed:true,passed:.passed}
' "$tmp/run-response.json" >"$tmp/evidence.json"

jq -e --slurpfile p "$profile" --arg environment "$environment" --arg purpose "$purpose" \
  --arg recoveryClass "$recovery_class" --arg sha "$target_sha" --arg profileSha "$profile_sha" \
  --arg seedFingerprint "$seed_fingerprint" --arg serviceIdentity "$fixture_service_identity" \
  --arg databaseIdentity "$fixture_db_identity" --arg task "$task_definition" --arg digest "$image_digest" \
  --argjson now "$now_epoch" '
  (keys | sort) == (["schemaVersion","evidenceId","environment","purpose","targetBackendSha",
    "recoveryClass","capturedAt","productionShapedFixture","fixtureProfileVersion",
    "fixtureProfileSha256","fixtureSeedFingerprint","fixtureSeedCounts","valuationDistribution",
    "attributionDistribution","segmentDistribution","fixtureServiceIdentity",
    "fixtureDatabaseIdentityHash","fixtureTaskDefinitionArn","fixtureImageDigest","latencyEndpoint",
    "latencyClientSettings","baselineWarmupCount","candidateWarmupCount","baselineSampleCount",
    "candidateLatencySampleCount","totalWriteCount","writeDistribution","writeRatePerSecond",
    "writeBurst","percentileMethod","phaseOverlap","costBasisRunCount","revenueRunCount",
    "maxCostBasisSeconds","maxRevenueSeconds","costBasisRunDurationsSeconds",
    "revenueRunDurationsSeconds","maxP95AddedMs","maxP95AddedPercent","maxP99LockMs",
    "maxLockReleaseSeconds","observedBaselineP95Micros","observedCandidateP95Micros",
    "observedP95AddedMs","observedP95AddedPercent","observedP99LockMs",
    "observedMaxLockReleaseSeconds","deadlockCount","currentMonthEndpointStayedAvailable",
    "historicalChangeRepublished","fixtureCleanupPassed","passed"] | sort) and
  .schemaVersion == 1 and (.evidenceId | test("^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-a[a-f0-9]{3}-[a-f0-9]{12}$")) and
  .environment == $environment and .purpose == $purpose and .targetBackendSha == $sha and
  (($purpose == "INITIAL_ROLLOUT" and .recoveryClass == null) or
   ($purpose == "RECOVERY" and .recoveryClass == $recoveryClass)) and
  ((.capturedAt | fromdateiso8601) <= $now) and
  .productionShapedFixture == true and .fixtureProfileVersion == 1 and
  .fixtureProfileSha256 == $profileSha and .fixtureSeedFingerprint == $seedFingerprint and
  .fixtureSeedCounts == {completedInvoiceMonths:60,companyCount:3,
    consultants:{internal:320,external:40},documents:{total:15000,INVOICE:12000,PHANTOM:1800,CREDIT_NOTE:1200},
    itemControls:{total:60000,DELIVERY_BASE:42000,CALCULATED:6000,NEGATIVE_ADJUSTMENT:9000,POSITIVE_FEE:3000},
    allocationRowCount:150000,dependencyRowCount:250000,canonicalDeliveryRowCount:450000,
    datedCapacityRowCount:420000,employeeMonthSalaryRowCount:19200,
    companyMonthControls:{SALARY:180,OPEX:180}} and
  .valuationDistribution == $p[0].valuationDistribution and
  .attributionDistribution == $p[0].attributionDistribution and
  .segmentDistribution == $p[0].segmentDistribution and
  .fixtureServiceIdentity == $serviceIdentity and .fixtureDatabaseIdentityHash == $databaseIdentity and
  .fixtureTaskDefinitionArn == $task and .fixtureImageDigest == $digest and
  .latencyEndpoint == "POST /work" and .latencyClientSettings == {keepAlive:true,concurrency:1} and
  .baselineWarmupCount == 250 and .candidateWarmupCount == 250 and
  .baselineSampleCount == 2000 and .candidateLatencySampleCount == 4800 and
  .totalWriteCount == 10000 and .writeDistribution == $p[0].workload.writeDistribution and
  .writeRatePerSecond == 1.1 and .writeBurst == 1 and
  .percentileMethod == "NEAREST_RANK_INTEGER_MICROSECONDS" and .phaseOverlap == true and
  .costBasisRunCount == 3 and .revenueRunCount == 3 and
  .maxCostBasisSeconds == 1440 and .maxRevenueSeconds == 1440 and
  (.costBasisRunDurationsSeconds | length == 3 and all(.[]; type=="number" and .>=0 and .<=1440)) and
  (.revenueRunDurationsSeconds | length == 3 and all(.[]; type=="number" and .>=0 and .<=1440)) and
  .maxP95AddedMs == 5 and .maxP95AddedPercent == 10 and .maxP99LockMs == 10 and
  .maxLockReleaseSeconds == 5 and (.observedBaselineP95Micros | type=="number" and .>0) and
  (.observedCandidateP95Micros | type=="number" and .>0) and
  (.observedP95AddedMs | type=="number") and .observedP95AddedMs <= 5 and
  (.observedP95AddedPercent | type=="number") and .observedP95AddedPercent <= 10 and
  (.observedP99LockMs | type=="number") and
  .observedP99LockMs >= 0 and .observedP99LockMs <= 10 and
  (.observedMaxLockReleaseSeconds | type=="number") and
  .observedMaxLockReleaseSeconds >= 0 and .observedMaxLockReleaseSeconds <= 5 and
  .deadlockCount == 0 and .currentMonthEndpointStayedAvailable == true and
  .historicalChangeRepublished == true and .fixtureCleanupPassed == true and .passed == true and
  ([paths(scalars) as $path | ($path[-1] | tostring | ascii_downcase)
    | select(test("token|password|authorization|cookie|secret"))] | length) == 0
' "$tmp/evidence.json" >/dev/null || die "performance evidence failed the immutable contract"

if grep -Fq "$token" "$tmp/evidence.json" || grep -Fq "$requested_by" "$tmp/evidence.json"; then
  die "performance evidence contains a secret or requested-user identifier"
fi
install -m 600 "$tmp/evidence.json" "$output"
printf '%s\n' "validated performance evidence: $output"
