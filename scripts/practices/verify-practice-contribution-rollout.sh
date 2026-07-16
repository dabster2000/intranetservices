#!/usr/bin/env bash
set -euo pipefail
umask 077

# This verifier deliberately owns the orchestration it performs.  It never sources an
# operator script, accepts credentials as values, pushes a ref, updates an ECS service,
# or provisions infrastructure.  The writer-drain executable remains environment-owned;
# this script validates only the immutable evidence produced by its closed CLI.

readonly SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd -P)"
readonly REPOSITORY_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd -P)"
readonly RECONCILIATION_SQL="$SCRIPT_DIR/practice-contribution-reconciliation.sql"
readonly RECONCILIATION_SQL_SHA256="c89faf4e657a0c3da3257b743363b7e336510cfbf1d496ec5dfcbf215c9a77ac"
readonly PERFORMANCE_PROFILE="$SCRIPT_DIR/fixtures/practice-revenue-performance-profile-v1.json"
readonly DEADLINE_RUNNER="$SCRIPT_DIR/run-with-deadline.py"
readonly SYSTEM_BATCH_PATH="/system/batch"
readonly CONTRIBUTION_PATH="/practices/cxo/contribution"
readonly BFF_CONTRIBUTION_PATH="/api/cxo/practices/contribution"
readonly BFF_OPERATING_COST_PATH="/api/cxo/practices/operating-cost"
readonly EXPECTED_WRITER_CATEGORIES_JSON='["BI_FULL_INCREMENTAL_EVENTS","CURRENCY_ACCOUNT_CLASSIFICATION","FINANCE_GL_AND_MULTI_TRANSACTION_IMPORTS","INVOICE_ATTRIBUTION","INVOICE_DOCUMENT_ITEM_PRICING","PHANTOM_ATTRIBUTION","PRACTICE_COST_PUBLICATION_JOBS","PRACTICE_DIRTY_RETENTION_JOBS","PRACTICE_STATUS_HISTORY","SELF_BILLED","WORK_CONTRACT_DELIVERY_ROUTING"]'

die() {
  printf '%s\n' "practice rollout verifier: $1" >&2
  exit 2
}

require_value() {
  [[ -n "${2:-}" && "${2:-}" != --* ]] || die "missing value for $1"
}

require_once() {
  [[ -z "$2" ]] || die "duplicate argument $1"
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

normalize_url() {
  local value="$1"
  while [[ "$value" == */ ]]; do value="${value%/}"; done
  printf '%s\n' "$value"
}

require_https_url() {
  [[ "$1" =~ ^https://[^/@[:space:]]+([/:][^@[:space:]]*)?$ ]] \
    || die "$2 must be an HTTPS URL without embedded credentials"
}

require_sha() {
  [[ "$1" =~ ^[a-f0-9]{40}$ ]] || die "$2 must be 40 lowercase hexadecimal characters"
}

require_digest() {
  [[ "$1" =~ ^sha256:[a-f0-9]{64}$ ]] || die "$2 must be an immutable sha256 digest"
}

require_uuid() {
  [[ "$1" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ ]] \
    || die "$2 must be a canonical lowercase UUID"
}

require_month_date() {
  [[ "$1" =~ ^[0-9]{4}-(0[1-9]|1[0-2])-01$ ]] \
    || die "$2 must be a canonical first-of-month date"
}

require_fingerprint() {
  [[ "$1" =~ ^[a-f0-9]{64}$ ]] || die "$2 must be a lowercase SHA-256 value"
}

require_boolean() {
  [[ "$1" == true || "$1" == false ]] || die "$2 must be true or false"
}

require_bounded_seconds() {
  local value="$1" maximum="$2" label="$3"
  [[ "$value" =~ ^[1-9][0-9]*$ ]] || die "$label must be a positive integer"
  ((value <= maximum)) || die "$label exceeds the bounded maximum of $maximum seconds"
}

command_deadline() {
  local maximum="$1" remaining
  if [[ -n "${POLL_DEADLINE_SECONDS:-}" ]]; then
    remaining=$((POLL_DEADLINE_SECONDS - SECONDS))
    ((remaining < 1)) && remaining=1
    ((remaining < maximum)) && maximum="$remaining"
  fi
  printf '%s\n' "$maximum"
}

run_with_deadline() {
  local seconds="$1"
  shift
  require_bounded_seconds "$seconds" 10800 "external command deadline"
  require_tool "$python_bin" python3
  "$python_bin" "$DEADLINE_RUNNER" "$seconds" "$@"
}

sha256_file() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    sha256sum "$1" | awk '{print $1}'
  fi
}

mode="${1:-}"
[[ -n "$mode" ]] || die "mode is required"
shift
case "$mode" in
  preflight|schema|refresh|revenue-recovery-refresh|reconcile|api|\
  publication-enable-build|publication-enable-serving|publication-disable|\
  cost-serving-disable|cost-serving-enable|recover-stale|deploy) ;;
  *) die "unknown mode $mode" ;;
esac

environment="${PRACTICES_TARGET_ENV:-}"
[[ "$environment" == staging || "$environment" == production ]] \
  || die "PRACTICES_TARGET_ENV must be staging or production"

expected_backend_sha=""
expected_frontend_sha=""
api_base_url=""
expected_cost_generation_at=""
expected_cost_request_id=""
expected_cost_request_key=""
expected_cost_input_vector=""
expected_source_vector=""
expected_generation_id=""
expected_recovery_execution_id=""
expected_migrations_file=""
expect_refresh_enabled=""
expect_contribution_serving_enabled=""
expect_legacy_cost_serving_enabled=""
expect_bi_refresh_lock_released=false
full_bi_mode=""
full_bi_wait_seconds=""
cost_basis_wait_seconds=""
revenue_wait_seconds=""
scope=""
surface=""
confirm_publication_control=false
allow_structured_live_cost_gaps=false
resume_build_after_recovery=false
confirm_stale_recovery=false
category=""
wait_seconds=""
component=""
phase=""
require_writer_quiescence=false
require_no_old_task=false
writer_evidence_file=""
writer_release_evidence_file=""
same_drain_lease_as=""
writer_max_age_seconds=""
writer_min_validity_seconds=""
expected_workflow_path=""
ci_wait_seconds=""
deploy_wait_seconds=""
observation_seconds=""
expected_task_definition=""
expected_image_digest=""
performance_evidence=""
performance_purpose=""
performance_target_backend_sha=""
performance_fixture_profile=""
performance_fixture_task_definition=""
performance_fixture_image_digest=""
performance_max_age_seconds=""
performance_recovery_class=""
expected_migrations=()
cost_sources=()

while (($#)); do
  case "$1" in
    --expected-backend-sha) require_value "$1" "${2:-}"; require_once "$1" "$expected_backend_sha"; expected_backend_sha="$2"; shift 2 ;;
    --expected-frontend-sha) require_value "$1" "${2:-}"; require_once "$1" "$expected_frontend_sha"; expected_frontend_sha="$2"; shift 2 ;;
    --api-base-url) require_value "$1" "${2:-}"; require_once "$1" "$api_base_url"; api_base_url="$2"; shift 2 ;;
    --expected-cost-generation-at) require_value "$1" "${2:-}"; require_once "$1" "$expected_cost_generation_at"; expected_cost_generation_at="$2"; shift 2 ;;
    --expected-cost-request-id) require_value "$1" "${2:-}"; require_once "$1" "$expected_cost_request_id"; expected_cost_request_id="$2"; shift 2 ;;
    --expected-cost-request-key) require_value "$1" "${2:-}"; require_once "$1" "$expected_cost_request_key"; expected_cost_request_key="$2"; shift 2 ;;
    --expected-cost-input-vector-fingerprint) require_value "$1" "${2:-}"; require_once "$1" "$expected_cost_input_vector"; expected_cost_input_vector="$2"; shift 2 ;;
    --expected-source-vector-fingerprint) require_value "$1" "${2:-}"; require_once "$1" "$expected_source_vector"; expected_source_vector="$2"; shift 2 ;;
    --expected-generation-id) require_value "$1" "${2:-}"; require_once "$1" "$expected_generation_id"; expected_generation_id="$2"; shift 2 ;;
    --expected-recovery-execution-id) require_value "$1" "${2:-}"; require_once "$1" "$expected_recovery_execution_id"; expected_recovery_execution_id="$2"; shift 2 ;;
    --expected-migration) require_value "$1" "${2:-}"; expected_migrations+=("$2"); shift 2 ;;
    --expected-migrations-file) require_value "$1" "${2:-}"; require_once "$1" "$expected_migrations_file"; expected_migrations_file="$2"; shift 2 ;;
    --expect-refresh-enabled) require_value "$1" "${2:-}"; require_once "$1" "$expect_refresh_enabled"; expect_refresh_enabled="$2"; shift 2 ;;
    --expect-contribution-serving-enabled) require_value "$1" "${2:-}"; require_once "$1" "$expect_contribution_serving_enabled"; expect_contribution_serving_enabled="$2"; shift 2 ;;
    --expect-legacy-cost-serving-enabled) require_value "$1" "${2:-}"; require_once "$1" "$expect_legacy_cost_serving_enabled"; expect_legacy_cost_serving_enabled="$2"; shift 2 ;;
    --expect-bi-refresh-lock-released) [[ "$expect_bi_refresh_lock_released" == false ]] || die "duplicate argument $1"; expect_bi_refresh_lock_released=true; shift ;;
    --full-bi-mode) require_value "$1" "${2:-}"; require_once "$1" "$full_bi_mode"; full_bi_mode="$2"; shift 2 ;;
    --full-bi-wait-seconds) require_value "$1" "${2:-}"; require_once "$1" "$full_bi_wait_seconds"; full_bi_wait_seconds="$2"; shift 2 ;;
    --cost-basis-wait-seconds) require_value "$1" "${2:-}"; require_once "$1" "$cost_basis_wait_seconds"; cost_basis_wait_seconds="$2"; shift 2 ;;
    --revenue-wait-seconds) require_value "$1" "${2:-}"; require_once "$1" "$revenue_wait_seconds"; revenue_wait_seconds="$2"; shift 2 ;;
    --scope) require_value "$1" "${2:-}"; require_once "$1" "$scope"; scope="$2"; shift 2 ;;
    --surface) require_value "$1" "${2:-}"; require_once "$1" "$surface"; surface="$2"; shift 2 ;;
    --cost-source) require_value "$1" "${2:-}"; cost_sources+=("$2"); shift 2 ;;
    --confirm-publication-control) [[ "$confirm_publication_control" == false ]] || die "duplicate argument $1"; confirm_publication_control=true; shift ;;
    --allow-structured-live-cost-gaps) [[ "$allow_structured_live_cost_gaps" == false ]] || die "duplicate argument $1"; allow_structured_live_cost_gaps=true; shift ;;
    --resume-build-after-recovery) [[ "$resume_build_after_recovery" == false ]] || die "duplicate argument $1"; resume_build_after_recovery=true; shift ;;
    --confirm-stale-recovery) [[ "$confirm_stale_recovery" == false ]] || die "duplicate argument $1"; confirm_stale_recovery=true; shift ;;
    --category) require_value "$1" "${2:-}"; require_once "$1" "$category"; category="$2"; shift 2 ;;
    --wait-seconds) require_value "$1" "${2:-}"; require_once "$1" "$wait_seconds"; wait_seconds="$2"; shift 2 ;;
    --component) require_value "$1" "${2:-}"; require_once "$1" "$component"; component="$2"; shift 2 ;;
    --phase) require_value "$1" "${2:-}"; require_once "$1" "$phase"; phase="$2"; shift 2 ;;
    --require-writer-quiescence) [[ "$require_writer_quiescence" == false ]] || die "duplicate argument $1"; require_writer_quiescence=true; shift ;;
    --require-no-old-task) [[ "$require_no_old_task" == false ]] || die "duplicate argument $1"; require_no_old_task=true; shift ;;
    --writer-quiescence-evidence-file) require_value "$1" "${2:-}"; require_once "$1" "$writer_evidence_file"; writer_evidence_file="$2"; shift 2 ;;
    --writer-quiescence-release-evidence-file) require_value "$1" "${2:-}"; require_once "$1" "$writer_release_evidence_file"; writer_release_evidence_file="$2"; shift 2 ;;
    --same-drain-lease-as) require_value "$1" "${2:-}"; require_once "$1" "$same_drain_lease_as"; same_drain_lease_as="$2"; shift 2 ;;
    --writer-quiescence-max-age-seconds) require_value "$1" "${2:-}"; require_once "$1" "$writer_max_age_seconds"; writer_max_age_seconds="$2"; shift 2 ;;
    --writer-quiescence-min-validity-seconds) require_value "$1" "${2:-}"; require_once "$1" "$writer_min_validity_seconds"; writer_min_validity_seconds="$2"; shift 2 ;;
    --expected-workflow-path) require_value "$1" "${2:-}"; require_once "$1" "$expected_workflow_path"; expected_workflow_path="$2"; shift 2 ;;
    --ci-wait-seconds) require_value "$1" "${2:-}"; require_once "$1" "$ci_wait_seconds"; ci_wait_seconds="$2"; shift 2 ;;
    --deploy-wait-seconds) require_value "$1" "${2:-}"; require_once "$1" "$deploy_wait_seconds"; deploy_wait_seconds="$2"; shift 2 ;;
    --observation-seconds) require_value "$1" "${2:-}"; require_once "$1" "$observation_seconds"; observation_seconds="$2"; shift 2 ;;
    --expected-task-definition) require_value "$1" "${2:-}"; require_once "$1" "$expected_task_definition"; expected_task_definition="$2"; shift 2 ;;
    --expected-image-digest) require_value "$1" "${2:-}"; require_once "$1" "$expected_image_digest"; expected_image_digest="$2"; shift 2 ;;
    --require-performance-evidence) require_value "$1" "${2:-}"; require_once "$1" "$performance_evidence"; performance_evidence="$2"; shift 2 ;;
    --performance-purpose) require_value "$1" "${2:-}"; require_once "$1" "$performance_purpose"; performance_purpose="$2"; shift 2 ;;
    --performance-target-backend-sha) require_value "$1" "${2:-}"; require_once "$1" "$performance_target_backend_sha"; performance_target_backend_sha="$2"; shift 2 ;;
    --performance-fixture-profile) require_value "$1" "${2:-}"; require_once "$1" "$performance_fixture_profile"; performance_fixture_profile="$2"; shift 2 ;;
    --performance-fixture-task-definition) require_value "$1" "${2:-}"; require_once "$1" "$performance_fixture_task_definition"; performance_fixture_task_definition="$2"; shift 2 ;;
    --performance-fixture-image-digest) require_value "$1" "${2:-}"; require_once "$1" "$performance_fixture_image_digest"; performance_fixture_image_digest="$2"; shift 2 ;;
    --performance-max-age-seconds) require_value "$1" "${2:-}"; require_once "$1" "$performance_max_age_seconds"; performance_max_age_seconds="$2"; shift 2 ;;
    --performance-recovery-class) require_value "$1" "${2:-}"; require_once "$1" "$performance_recovery_class"; performance_recovery_class="$2"; shift 2 ;;
    *) die "unknown argument $1" ;;
  esac
done

curl_bin="${PRACTICES_CURL_BIN:-curl}"
mariadb_bin="${PRACTICES_MARIADB_BIN:-mariadb}"
gh_bin="${PRACTICES_GH_BIN:-gh}"
aws_bin="${PRACTICES_AWS_BIN:-aws}"
python_bin="${PRACTICES_PYTHON_BIN:-python3}"
clock_bin="${PRACTICES_CLOCK_BIN:-date}"
sleep_bin="${PRACTICES_SLEEP_BIN:-sleep}"
jq_bin="${PRACTICES_JQ_BIN:-jq}"
poll_interval="${PRACTICES_POLL_INTERVAL_SECONDS:-5}"
[[ "$poll_interval" =~ ^[1-9][0-9]*$ ]] && ((poll_interval <= 60)) \
  || die "PRACTICES_POLL_INTERVAL_SECONDS must be between 1 and 60"

tmp="$(mktemp -d)"
finish() { rm -rf "$tmp"; }
trap finish EXIT HUP INT TERM

captured_at() {
  "$clock_bin" -u +%Y-%m-%dT%H:%M:%SZ
}

epoch_now() {
  "$clock_bin" -u +%s
}

backend_base_url() {
  local value="${api_base_url:-${PRACTICES_BACKEND_BASE_URL:-}}"
  [[ -n "$value" ]] || die "PRACTICES_BACKEND_BASE_URL is required"
  require_https_url "$value" "backend base URL"
  normalize_url "$value"
}

frontend_base_url() {
  local value="${PRACTICES_FRONTEND_BASE_URL:-}"
  [[ -n "$value" ]] || die "PRACTICES_FRONTEND_BASE_URL is required"
  require_https_url "$value" "frontend base URL"
  normalize_url "$value"
}

evidence_directory() {
  local dir="${PRACTICES_EVIDENCE_DIR:-}"
  [[ -n "$dir" && -d "$dir" && -w "$dir" ]] \
    || die "PRACTICES_EVIDENCE_DIR must be an existing writable directory"
  dir="$(cd "$dir" && pwd -P)"
  case "$dir" in
    "$REPOSITORY_ROOT"|"$REPOSITORY_ROOT"/*) die "evidence directory must be outside the repository" ;;
  esac
  printf '%s\n' "$dir"
}

evidence_file_for() {
  local suffix="$1" dir
  dir="$(evidence_directory)"
  printf '%s/%s.json\n' "$dir" "$suffix"
}

write_evidence() {
  local source="$1" destination="$2"
  "$jq_bin" -e 'type=="object" and .passed==true and
    ([paths(scalars) as $p | ($p[-1]|tostring|ascii_downcase) |
      select(test("token|password|authorization|cookie|secret"))] | length)==0' \
    "$source" >/dev/null || die "refused unsafe or failed evidence"
  install -m 600 "$source" "$destination"
  printf '%s\n' "validated rollout evidence: $destination"
}

system_auth_config=""
dashboard_auth_config=""

init_auth_config() {
  local kind="$1" token_file requested_file token requested destination
  requested_file="${PRACTICES_REQUESTED_BY_FILE:-}"
  [[ -n "$requested_file" ]] || die "PRACTICES_REQUESTED_BY_FILE is required"
  require_private_file "$requested_file" "requested-by file"
  requested="$(tr -d '\r\n' <"$requested_file")"
  require_uuid "$requested" "requested-by value"
  if [[ "$kind" == system ]]; then
    token_file="${PRACTICES_SYSTEM_TOKEN_FILE:-}"
    destination="$tmp/system-auth"
  else
    token_file="${PRACTICES_DASHBOARD_TOKEN_FILE:-}"
    destination="$tmp/dashboard-auth"
  fi
  [[ -n "$token_file" ]] || die "$kind token-file environment variable is required"
  require_private_file "$token_file" "$kind token file"
  token="$(tr -d '\r\n' <"$token_file")"
  [[ "$token" =~ ^[A-Za-z0-9._~-]+$ ]] || die "$kind token file contains an invalid token"
  printf 'header = "Authorization: Bearer %s"\nheader = "X-Requested-By: %s"\n' \
    "$token" "$requested" >"$destination"
  chmod 600 "$destination"
  token="" requested=""
  if [[ "$kind" == system ]]; then system_auth_config="$destination"; else dashboard_auth_config="$destination"; fi
}

ensure_system_auth() {
  [[ -n "$system_auth_config" ]] || init_auth_config system
}

ensure_dashboard_auth() {
  [[ -n "$dashboard_auth_config" ]] || init_auth_config dashboard
}

HTTP_STATUS=""
http_request() {
  local auth="$1" method="$2" url="$3" payload="$4" body="$5" headers="$6" max_time="$7"
  local command status cookie
  require_tool "$curl_bin" curl
  command=("$curl_bin" --silent --show-error --connect-timeout 10 --max-time "$max_time"
    --request "$method" --url "$url" --header 'Accept: application/json'
    --dump-header "$headers" --output "$body" --write-out '%{http_code}')
  case "$auth" in
    system) ensure_system_auth; command+=(--config "$system_auth_config") ;;
    dashboard) ensure_dashboard_auth; command+=(--config "$dashboard_auth_config") ;;
    admin-cookie)
      cookie="${PRACTICES_ADMIN_COOKIE_FILE:-}"; [[ -n "$cookie" ]] || die "PRACTICES_ADMIN_COOKIE_FILE is required"
      require_private_file "$cookie" "ADMIN cookie file"; command+=(--cookie "$cookie") ;;
    nonadmin-cookie)
      cookie="${PRACTICES_NONADMIN_COOKIE_FILE:-}"; [[ -n "$cookie" ]] || die "PRACTICES_NONADMIN_COOKIE_FILE is required"
      require_private_file "$cookie" "non-ADMIN cookie file"; command+=(--cookie "$cookie") ;;
    public) ;;
    *) die "internal unknown HTTP auth class" ;;
  esac
  if [[ -n "$payload" ]]; then
    command+=(--header 'Content-Type: application/json' --data-binary "@$payload")
  fi
  status="$("${command[@]}")" || die "bounded HTTP request failed"
  [[ "$status" =~ ^[0-9]{3}$ ]] || die "HTTP client returned an invalid status"
  HTTP_STATUS="$status"
}

post_system_json() {
  local path="$1" payload="$2" output="$3"
  http_request system POST "$(backend_base_url)$path" "$payload" "$output" "$tmp/http-headers" 30
  [[ "$HTTP_STATUS" == 200 ]] || die "protected operation returned HTTP $HTTP_STATUS"
  "$jq_bin" -e 'type=="object"' "$output" >/dev/null || die "protected operation returned invalid JSON"
}

db_login_path() {
  local value="${PRACTICES_DB_LOGIN_PATH:-}"
  [[ "$value" =~ ^[A-Za-z0-9._-]+$ ]] || die "PRACTICES_DB_LOGIN_PATH is required and must be a login-path name"
  printf '%s\n' "$value"
}

db_read() {
  local sql="$1" login
  require_tool "$mariadb_bin" mariadb
  login="$(db_login_path)"
  [[ "$sql" =~ ^/\*\ practices:[a-z0-9-]+\ \*/[[:space:]]*(SELECT|SHOW|SET[[:space:]]+STATEMENT) ]] \
    || die "internal read-only SQL guard rejected a statement"
  run_with_deadline "$(command_deadline 130)" "$mariadb_bin" --login-path="$login" --batch --raw --skip-column-names \
    --connect-timeout=10 --execute "$sql"
}

db_read_file() {
  local file="$1" login
  require_tool "$mariadb_bin" mariadb
  login="$(db_login_path)"
  [[ "$(cd "$(dirname "$file")" && pwd -P)" == "$(cd "$tmp" && pwd -P)" &&
     "$(basename "$file")" == reconciliation-*.sql ]] \
    || die "reconciliation SQL must be the verifier-owned temporary statement"
  if grep -Eiq '(^|;)[[:space:]]*(INSERT|UPDATE|DELETE|REPLACE|ALTER|DROP|TRUNCATE|CREATE|CALL|LOAD|GRANT|REVOKE)[[:space:]]|[[:space:]]INTO[[:space:]]+(OUTFILE|DUMPFILE)|LOAD_FILE[[:space:]]*\(' "$file"; then
    die "reconciliation SQL failed the read-only guard"
  fi
  run_with_deadline "$(command_deadline 130)" "$mariadb_bin" --login-path="$login" --batch --raw --skip-column-names \
    --connect-timeout=10 <"$file"
}

db_invoke_full_bi() {
  local deadline="$1" login="${PRACTICES_BI_DB_LOGIN_PATH:-}"
  [[ "$login" =~ ^[A-Za-z0-9._-]+$ ]] || die "PRACTICES_BI_DB_LOGIN_PATH is required for full BI invocation"
  require_tool "$mariadb_bin" mariadb
  [[ "$login" != "$(db_login_path)" ]] || die "write-capable BI and read-only login paths must differ"
  run_with_deadline "$deadline" "$mariadb_bin" --login-path="$login" --batch --raw --skip-column-names \
    --connect-timeout=10 --execute "SET STATEMENT max_statement_time=$deadline FOR CALL sp_nightly_bi_refresh(3,24);" \
    || die "full BI invocation exceeded its bounded deadline or failed"
}

POLL_RESULT=""
poll_until() {
  local timeout="$1" label="$2" callback="$3" deadline remaining step
  require_bounded_seconds "$timeout" 10800 "$label timeout"
  require_tool "$sleep_bin" sleep
  deadline=$((SECONDS + timeout))
  POLL_DEADLINE_SECONDS="$deadline"
  while true; do
    if "$callback"; then unset POLL_DEADLINE_SECONDS; return 0; fi
    remaining=$((deadline - SECONDS))
    ((remaining > 0)) || break
    step="$poll_interval"; ((remaining < step)) && step="$remaining"
    "$sleep_bin" "$step"
  done
  unset POLL_DEADLINE_SECONDS
  die "$label did not reach its required state within $timeout seconds"
}

state_snapshot() {
  db_read "/* practices:state-snapshot */ SELECT CONCAT_WS('|',
    CAST(b.full_refresh_version AS CHAR), b.refresh_state, COALESCE(b.active_refresh_token,''),
    COALESCE(DATE_FORMAT(b.certified_complete_through_date,'%Y-%m-%d'),''),
    COALESCE(DATE_FORMAT(o.generation_at,'%Y-%m-%dT%H:%i:%s.%fZ'),''), o.refresh_state,
    COALESCE(o.active_refresh_token,''), COALESCE(CAST(o.latest_cost_basis_request_id AS CHAR),''),
    COALESCE(CAST(o.certified_cost_basis_request_id AS CHAR),''),
    COALESCE(p.published_generation_id,''), COALESCE(p.previous_generation_id,''), p.status,
    COALESCE(p.owner_token,''), COALESCE(DATE_FORMAT(p.paired_cost_generation_at,'%Y-%m-%dT%H:%i:%s.%fZ'),''),
    CAST(c.refresh_enabled AS UNSIGNED), CAST(c.contribution_serving_enabled AS UNSIGNED),
    CAST(c.legacy_cost_serving_enabled AS UNSIGNED), COALESCE(c.revenue_recovery_state,''),
    COALESCE(c.revenue_recovery_execution_id,''))
  FROM bi_refresh_watermark b
  JOIN practice_operating_cost_publication o ON o.publication_id=1
  JOIN practice_revenue_publication p ON p.publication_key='PRACTICE_CONTRIBUTION'
  JOIN practice_contribution_publication_control c ON c.control_id=1
  WHERE b.pipeline_name='FACT_USER_DAY';"
}

request_snapshot() {
  local request_id="$1"
  [[ "$request_id" =~ ^[1-9][0-9]*$ ]] || die "invalid cost request ID"
  db_read "/* practices:request-snapshot */ SELECT CONCAT_WS('|', CAST(request_id AS CHAR),
    request_key,input_vector_fingerprint,status,COALESCE(CAST(superseded_by_request_id AS CHAR),''),
    COALESCE(DATE_FORMAT(resulting_cost_generation_at,'%Y-%m-%dT%H:%i:%s.%fZ'),''),
    COALESCE(DATE_FORMAT(compared_cost_generation_at,'%Y-%m-%dT%H:%i:%s.%fZ'),''),
    COALESCE(resulting_basis_generation_id,''),COALESCE(compared_basis_generation_id,''))
  FROM practice_cost_basis_refresh_request WHERE request_id=$request_id;"
}

latest_request_snapshot() {
  db_read "/* practices:latest-request */ SELECT CONCAT_WS('|', CAST(request_id AS CHAR),request_key,
    input_vector_fingerprint,status,COALESCE(CAST(superseded_by_request_id AS CHAR),''),
    COALESCE(DATE_FORMAT(resulting_cost_generation_at,'%Y-%m-%dT%H:%i:%s.%fZ'),''),
    COALESCE(DATE_FORMAT(compared_cost_generation_at,'%Y-%m-%dT%H:%i:%s.%fZ'),''),
    COALESCE(resulting_basis_generation_id,''),COALESCE(compared_basis_generation_id,''))
  FROM practice_cost_basis_refresh_request
  WHERE NOT EXISTS (SELECT 1 FROM practice_cost_basis_refresh_request newer
                    WHERE newer.request_id>practice_cost_basis_refresh_request.request_id)
  ORDER BY request_id DESC LIMIT 1;"
}

source_vector_fingerprint() {
  db_read "/* practices:source-vector */ SELECT COALESCE(SHA2(GROUP_CONCAT(CONCAT(source_name,'=',source_version)
    ORDER BY source_name SEPARATOR '|'),256),'') FROM practice_revenue_source_watermark
    HAVING COUNT(*)=9 AND SUM(source_state<>'READY')=0;"
}

ecs_variables() {
  local which="$1"
  case "$which" in
    backend) ECS_CLUSTER="${PRACTICES_BACKEND_ECS_CLUSTER:-}"; ECS_SERVICE="${PRACTICES_BACKEND_ECS_SERVICE:-}" ;;
    frontend) ECS_CLUSTER="${PRACTICES_FRONTEND_ECS_CLUSTER:-}"; ECS_SERVICE="${PRACTICES_FRONTEND_ECS_SERVICE:-}" ;;
    performance-fixture) ECS_CLUSTER="${PRACTICES_PERFORMANCE_ECS_CLUSTER:-}"; ECS_SERVICE="${PRACTICES_PERFORMANCE_ECS_SERVICE:-}" ;;
    *) die "internal unknown ECS component" ;;
  esac
  [[ -n "$ECS_CLUSTER" && -n "$ECS_SERVICE" ]] || die "ECS cluster/service are required for $which"
}

ECS_TASK_DEFINITION=""
ECS_OBSERVED_DIGEST=""
ecs_is_exact() {
  local which="$1" sha="$2" expected_task="${3:-}" expected_digest="${4:-}"
  local service_json tasks_json described_json task_definition_json task_definition desired running pending
  require_tool "$aws_bin" aws
  ecs_variables "$which"
  service_json="$tmp/ecs-service.json"
  run_with_deadline "$(command_deadline 30)" "$aws_bin" ecs describe-services --cluster "$ECS_CLUSTER" --services "$ECS_SERVICE" \
    --output json >"$service_json" || return 1
  "$jq_bin" -e '.failures==[] and (.services|length)==1' "$service_json" >/dev/null || return 1
  desired="$("$jq_bin" -r '.services[0].desiredCount' "$service_json")"
  running="$("$jq_bin" -r '.services[0].runningCount' "$service_json")"
  pending="$("$jq_bin" -r '.services[0].pendingCount' "$service_json")"
  [[ "$desired" =~ ^[1-9][0-9]*$ && "$running" == "$desired" && "$pending" == 0 ]] || return 1
  "$jq_bin" -e '[.services[0].deployments[]|select(.status=="PRIMARY" and .rolloutState=="COMPLETED")]|length==1' \
    "$service_json" >/dev/null || return 1
  task_definition="$("$jq_bin" -er '.services[0].taskDefinition' "$service_json")" || return 1
  [[ -z "$expected_task" || "$task_definition" == "$expected_task" ]] || return 1
  tasks_json="$tmp/ecs-task-list.json"
  run_with_deadline "$(command_deadline 30)" "$aws_bin" ecs list-tasks --cluster "$ECS_CLUSTER" --service-name "$ECS_SERVICE" \
    --desired-status RUNNING --output json >"$tasks_json" || return 1
  "$jq_bin" -e --argjson desired "$desired" '.taskArns|length==$desired' "$tasks_json" >/dev/null || return 1
  described_json="$tmp/ecs-tasks.json"
  # shellcheck disable=SC2046
  run_with_deadline "$(command_deadline 30)" "$aws_bin" ecs describe-tasks --cluster "$ECS_CLUSTER" --tasks \
    $("$jq_bin" -r '.taskArns[]' "$tasks_json") --output json >"$described_json" || return 1
  "$jq_bin" -e --arg task "$task_definition" --argjson desired "$desired" \
    '.failures==[] and (.tasks|length)==$desired and all(.tasks[]; .lastStatus=="RUNNING" and .taskDefinitionArn==$task)' \
    "$described_json" >/dev/null || return 1
  if [[ -n "$expected_digest" ]]; then
    "$jq_bin" -e --arg digest "$expected_digest" \
      'all(.tasks[]; any(.containers[]; .imageDigest==$digest))' "$described_json" >/dev/null || return 1
    ECS_OBSERVED_DIGEST="$expected_digest"
  else
    ECS_OBSERVED_DIGEST="$("$jq_bin" -r '[.tasks[].containers[].imageDigest]|unique|if length==1 then .[0] else "" end' "$described_json")"
  fi
  task_definition_json="$tmp/ecs-task-definition.json"
  run_with_deadline "$(command_deadline 30)" "$aws_bin" ecs describe-task-definition --task-definition "$task_definition" --include TAGS \
    --output json >"$task_definition_json" || return 1
  "$jq_bin" -e --arg sha "$sha" '
    ([.tags[]? | select((.key=="git-sha" or .key=="practices:sha") and .value==$sha)]|length)==1 or
    any(.taskDefinition.containerDefinitions[];
      (.image|contains($sha)) or
      ([.environment[]? | select((.name=="GIT_SHA" or .name=="COMMIT_SHA" or
        .name=="BACKEND_SHA" or .name=="FRONTEND_SHA") and .value==$sha)]|length)==1)' \
    "$task_definition_json" >/dev/null || return 1
  ECS_TASK_DEFINITION="$task_definition"
  return 0
}

wait_for_ecs() {
  local which="$1" sha="$2" timeout="$3" expected_task="${4:-}" expected_digest="${5:-}"
  ECS_WAIT_COMPONENT="$which" ECS_WAIT_SHA="$sha" ECS_WAIT_TASK="$expected_task" ECS_WAIT_DIGEST="$expected_digest"
  ecs_wait_callback() { ecs_is_exact "$ECS_WAIT_COMPONENT" "$ECS_WAIT_SHA" "$ECS_WAIT_TASK" "$ECS_WAIT_DIGEST"; }
  poll_until "$timeout" "$which ECS deployment" ecs_wait_callback
}

validate_writer_evidence() {
  local kind="$1" file="$2" same_file="${3:-}" now expected_sha captured_field
  require_private_file "$file" "writer-drain evidence"
  require_sha "$expected_backend_sha" "expected backend SHA"
  now="$(epoch_now)"; [[ "$now" =~ ^[0-9]+$ ]] || die "clock returned an invalid epoch"
  if [[ "$kind" == active ]]; then
    require_bounded_seconds "$writer_max_age_seconds" 300 "writer evidence maximum age"
    require_bounded_seconds "$writer_min_validity_seconds" 7200 "writer evidence minimum validity"
    "$jq_bin" -e --arg environment "$environment" --arg sha "$expected_backend_sha" \
      --argjson categories "$EXPECTED_WRITER_CATEGORIES_JSON" --argjson now "$now" \
      --argjson maxAge "$writer_max_age_seconds" --argjson minValidity "$writer_min_validity_seconds" '
      (keys|sort)==(["schemaVersion","environment","targetBackendSha","capturedAt","validUntil",
        "active","affectedWritersDrained","writerCategories","drainLeaseId","issuer",
        "oldTaskCountAtCapture"]|sort) and .schemaVersion==1 and .environment==$environment and
      .targetBackendSha==$sha and .active==true and .affectedWritersDrained==true and
      (.writerCategories|sort)==$categories and (.drainLeaseId|type=="string" and length>0) and
      (.issuer|type=="string" and length>0) and
      (.oldTaskCountAtCapture|type=="number" and .>=0 and floor==.) and
      ((.capturedAt|fromdateiso8601)<=$now) and ($now-(.capturedAt|fromdateiso8601)<=$maxAge) and
      ((.validUntil|fromdateiso8601)-$now >= $minValidity)
    ' "$file" >/dev/null || die "writer-drain active evidence failed its exact contract"
  else
    "$jq_bin" -e --arg environment "$environment" --arg sha "$expected_backend_sha" \
      --argjson categories "$EXPECTED_WRITER_CATEGORIES_JSON" --argjson now "$now" '
      (keys|sort)==(["schemaVersion","environment","targetBackendSha","releasedAt","active",
        "affectedWritersRestored","writerCategories","drainLeaseId","issuer"]|sort) and
      .schemaVersion==1 and .environment==$environment and .targetBackendSha==$sha and
      .active==false and .affectedWritersRestored==true and (.writerCategories|sort)==$categories and
      (.drainLeaseId|type=="string" and length>0) and (.issuer|type=="string" and length>0) and
      ((.releasedAt|fromdateiso8601)<=$now)
    ' "$file" >/dev/null || die "writer-drain release evidence failed its exact contract"
  fi
  if [[ -n "$same_file" ]]; then
    require_private_file "$same_file" "same-lease evidence"
    [[ "$(canonical_path "$file")" != "$(canonical_path "$same_file")" ]] \
      || die "writer evidence phases must use different files"
    "$jq_bin" -e --slurpfile prior "$same_file" '
      .schemaVersion==$prior[0].schemaVersion and .environment==$prior[0].environment and
      .targetBackendSha==$prior[0].targetBackendSha and .drainLeaseId==$prior[0].drainLeaseId and
      .issuer==$prior[0].issuer and (.writerCategories|sort)==($prior[0].writerCategories|sort)
    ' "$file" >/dev/null || die "writer-drain evidence does not preserve the exact lease"
  fi
}

validate_cost_sources() {
  local seen_booked=0 seen_draft=0 value
  ((${#cost_sources[@]}==2)) || die "reconciliation requires BOOKED and BOOKED_PLUS_DRAFT exactly once"
  for value in "${cost_sources[@]}"; do
    case "$value" in
      BOOKED) ((seen_booked+=1)) ;;
      BOOKED_PLUS_DRAFT) ((seen_draft+=1)) ;;
      *) die "unknown cost source $value" ;;
    esac
  done
  ((seen_booked==1 && seen_draft==1)) || die "cost sources must not be repeated"
}

validate_performance_evidence() {
  local now profile_sha fixture_deploy
  [[ "$allow_structured_live_cost_gaps" == true ]] || return 0
  [[ "$environment" == staging ]] || die "structured live cost gaps are staging-only"
  [[ -n "$performance_evidence" && -n "$performance_purpose" &&
     -n "$performance_target_backend_sha" && -n "$performance_fixture_profile" &&
     -n "$performance_fixture_task_definition" && -n "$performance_fixture_image_digest" &&
     -n "$performance_max_age_seconds" ]] || die "structured live cost gaps require complete performance evidence binding"
  [[ "$performance_purpose" == INITIAL_ROLLOUT || "$performance_purpose" == RECOVERY ]] \
    || die "invalid performance purpose"
  if [[ "$performance_purpose" == RECOVERY ]]; then
    [[ "$performance_recovery_class" == REVENUE_ONLY || "$performance_recovery_class" == COST_BASIS ]] \
      || die "recovery performance evidence requires its exact recovery class"
  else
    [[ -z "$performance_recovery_class" ]] || die "initial-rollout evidence cannot have a recovery class"
  fi
  [[ "$performance_max_age_seconds" == 21600 ]] || die "performance evidence maximum age must be 21600 seconds"
  require_sha "$performance_target_backend_sha" "performance target backend SHA"
  require_digest "$performance_fixture_image_digest" "performance fixture image digest"
  require_private_file "$performance_evidence" "performance evidence"
  [[ "$(canonical_path "$performance_fixture_profile")" == "$(canonical_path "$PERFORMANCE_PROFILE")" ]] \
    || die "performance profile must be the committed profile"
  profile_sha="$(sha256_file "$PERFORMANCE_PROFILE")"
  fixture_deploy="$(evidence_file_for "deploy-performance-fixture-$performance_target_backend_sha")"
  [[ -f "$fixture_deploy" ]] || die "exact fixture deployment evidence is required before performance evidence"
  now="$(epoch_now)"; [[ "$now" =~ ^[0-9]+$ ]] || die "clock returned an invalid epoch"
  "$jq_bin" -e --slurpfile deploy "$fixture_deploy" --slurpfile profile "$PERFORMANCE_PROFILE" \
    --arg environment "$environment" --arg purpose "$performance_purpose" \
    --arg recoveryClass "$performance_recovery_class" --arg sha "$performance_target_backend_sha" \
    --arg profileSha "$profile_sha" --arg task "$performance_fixture_task_definition" \
    --arg digest "$performance_fixture_image_digest" --argjson now "$now" '
    (keys|sort)==(["schemaVersion","evidenceId","environment","purpose","targetBackendSha",
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
      "historicalChangeRepublished","fixtureCleanupPassed","passed"]|sort) and
    .schemaVersion==1 and
    (.evidenceId|test("^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-a[a-f0-9]{3}-[a-f0-9]{12}$")) and
    .environment==$environment and
    .purpose==$purpose and .targetBackendSha==$sha and
    (($purpose=="INITIAL_ROLLOUT" and .recoveryClass==null) or
     ($purpose=="RECOVERY" and .recoveryClass==$recoveryClass)) and
    .productionShapedFixture==true and .fixtureProfileVersion==1 and
    .fixtureProfileSha256==$profileSha and .fixtureSeedFingerprint==$profile[0].expectedSeedFingerprint and
    .fixtureSeedCounts=={completedInvoiceMonths:60,companyCount:3,
      consultants:{internal:320,external:40},documents:{total:15000,INVOICE:12000,PHANTOM:1800,CREDIT_NOTE:1200},
      itemControls:{total:60000,DELIVERY_BASE:42000,CALCULATED:6000,NEGATIVE_ADJUSTMENT:9000,POSITIVE_FEE:3000},
      allocationRowCount:150000,dependencyRowCount:250000,canonicalDeliveryRowCount:450000,
      datedCapacityRowCount:420000,employeeMonthSalaryRowCount:19200,
      companyMonthControls:{SALARY:180,OPEX:180}} and
    .valuationDistribution==$profile[0].valuationDistribution and
    .attributionDistribution==$profile[0].attributionDistribution and
    .segmentDistribution==$profile[0].segmentDistribution and
    .fixtureServiceIdentity==$deploy[0].serviceIdentity and
    .fixtureDatabaseIdentityHash==$deploy[0].databaseIdentityHash and
    .fixtureTaskDefinitionArn==$task and .fixtureImageDigest==$digest and
    .latencyEndpoint=="POST /work" and .latencyClientSettings=={keepAlive:true,concurrency:1} and
    .baselineWarmupCount==250 and .candidateWarmupCount==250 and .baselineSampleCount==2000 and
    .candidateLatencySampleCount==4800 and .totalWriteCount==10000 and
    .writeDistribution==$profile[0].workload.writeDistribution and .writeRatePerSecond==1.1 and
    .writeBurst==1 and .percentileMethod=="NEAREST_RANK_INTEGER_MICROSECONDS" and .phaseOverlap==true and
    .costBasisRunCount==3 and .revenueRunCount==3 and .maxCostBasisSeconds==1440 and
    .maxRevenueSeconds==1440 and (.costBasisRunDurationsSeconds|length)==3 and
    all(.costBasisRunDurationsSeconds[]; type=="number" and .>=0 and .<=1440) and
    (.revenueRunDurationsSeconds|length)==3 and
    all(.revenueRunDurationsSeconds[]; type=="number" and .>=0 and .<=1440) and
    .maxP95AddedMs==5 and .maxP95AddedPercent==10 and .maxP99LockMs==10 and
    .maxLockReleaseSeconds==5 and
    (.observedBaselineP95Micros|type=="number") and .observedBaselineP95Micros>0 and
    (.observedCandidateP95Micros|type=="number") and .observedCandidateP95Micros>0 and
    (.observedP95AddedMs|type=="number") and .observedP95AddedMs<=5 and
    (.observedP95AddedPercent|type=="number") and .observedP95AddedPercent<=10 and
    (.observedP99LockMs|type=="number") and .observedP99LockMs>=0 and .observedP99LockMs<=10 and
    (.observedMaxLockReleaseSeconds|type=="number") and
    .observedMaxLockReleaseSeconds>=0 and .observedMaxLockReleaseSeconds<=5 and .deadlockCount==0 and
    .currentMonthEndpointStayedAvailable==true and .historicalChangeRepublished==true and
    .fixtureCleanupPassed==true and .passed==true and
    ((.capturedAt|fromdateiso8601)<=$now) and ($now-(.capturedAt|fromdateiso8601)<=21600) and
    ((.capturedAt|fromdateiso8601)>=($deploy[0].observedAt|fromdateiso8601)) and
    $deploy[0].passed==true and $deploy[0].component=="performance-fixture" and
    $deploy[0].environment==$environment and $deploy[0].backendSha==$sha and
    $deploy[0].taskDefinitionArn==$task and $deploy[0].imageDigest==$digest and
    ([paths(scalars) as $p | ($p[-1]|tostring|ascii_downcase) |
      select(test("token|password|authorization|cookie|secret"))]|length)==0
  ' "$performance_evidence" >/dev/null || die "performance evidence failed the immutable rollout contract"
}

run_schema() {
  local migration_file="$tmp/migrations.tsv" controls line expected normalized found migrations_json destination
  require_tool "$jq_bin" jq
  [[ -z "$expected_migrations_file" || ${#expected_migrations[@]} -eq 0 ]] \
    || die "use repeated --expected-migration or --expected-migrations-file, not both"
  if [[ -n "$expected_migrations_file" ]]; then
    [[ -f "$expected_migrations_file" && -r "$expected_migrations_file" ]] \
      || die "expected migrations file must be readable"
    while IFS= read -r line || [[ -n "$line" ]]; do
      [[ -z "$line" || "$line" == \#* ]] && continue
      expected_migrations+=("$line")
    done <"$expected_migrations_file"
  fi
  ((${#expected_migrations[@]}>0)) || die "schema requires at least one expected migration"
  for expected in "${expected_migrations[@]}"; do
    [[ "$expected" =~ ^[Vv]?[0-9]+([._-][A-Za-z0-9._-]+)?$ ]] || die "invalid expected migration identifier"
  done
  db_read "/* practices:schema */ SELECT version,description,COALESCE(CAST(checksum AS CHAR),''),success
    FROM flyway_schema_history ORDER BY installed_rank;" >"$migration_file"
  for expected in "${expected_migrations[@]}"; do
    normalized="${expected#V}"; normalized="${normalized#v}"; normalized="${normalized%%__*}"; normalized="${normalized%%_*}"
    found="$(awk -F '\t' -v e="$expected" -v n="$normalized" '
      $1==n || ("V"$1"__"$2".sql")==e || $2==e {if ($3!="" && $4==1) print $0}' "$migration_file")"
    [[ -n "$found" ]] || die "expected successful migration $expected was not found with a checksum"
  done
  controls="$(db_read "/* practices:schema-controls */ SELECT CONCAT_WS('|',
    CAST(c.refresh_enabled AS UNSIGNED),CAST(c.contribution_serving_enabled AS UNSIGNED),
    CAST(c.legacy_cost_serving_enabled AS UNSIGNED),COALESCE(CAST(IS_USED_LOCK('bi_refresh') AS CHAR),''),
    COALESCE(b.active_refresh_token,''),b.refresh_state,
    (SELECT COUNT(*) FROM practice_revenue_source_watermark),
    (SELECT SUM(source_state<>'READY') FROM practice_revenue_source_watermark)
  FROM practice_contribution_publication_control c
  JOIN bi_refresh_watermark b ON b.pipeline_name='FACT_USER_DAY' WHERE c.control_id=1;")"
  IFS='|' read -r actual_refresh actual_contribution actual_legacy lock_owner active_token bi_state source_count source_not_ready <<<"$controls"
  [[ -z "$expect_refresh_enabled" ]] || { require_boolean "$expect_refresh_enabled" "expected refresh state"; [[ "$actual_refresh" == "$([[ "$expect_refresh_enabled" == true ]] && printf 1 || printf 0)" ]] || die "refresh-enabled postcondition failed"; }
  [[ -z "$expect_contribution_serving_enabled" ]] || { require_boolean "$expect_contribution_serving_enabled" "expected contribution-serving state"; [[ "$actual_contribution" == "$([[ "$expect_contribution_serving_enabled" == true ]] && printf 1 || printf 0)" ]] || die "contribution-serving postcondition failed"; }
  [[ -z "$expect_legacy_cost_serving_enabled" ]] || { require_boolean "$expect_legacy_cost_serving_enabled" "expected legacy-cost-serving state"; [[ "$actual_legacy" == "$([[ "$expect_legacy_cost_serving_enabled" == true ]] && printf 1 || printf 0)" ]] || die "legacy-cost-serving postcondition failed"; }
  if [[ "$expect_bi_refresh_lock_released" == true ]]; then
    [[ -z "$lock_owner" && -z "$active_token" ]] || die "BI refresh lock/token is still owned"
  fi
  [[ "$source_count" == 9 ]] || die "source watermark cardinality is not nine"
  migrations_json="$(awk -F '\t' '{printf "%s{\"version\":\"%s\",\"description\":\"%s\",\"checksum\":\"%s\"}", (NR==1?"":","),$1,$2,$3} END{print ""}' "$migration_file")"
  "$jq_bin" -n --arg environment "$environment" --arg observedAt "$(captured_at)" \
    --argjson migrations "[$migrations_json]" --argjson refresh "$actual_refresh" \
    --argjson contribution "$actual_contribution" --argjson legacy "$actual_legacy" \
    --arg biState "$bi_state" --argjson sourceNotReady "$source_not_ready" '
    {schemaVersion:1,mode:"schema",environment:$environment,observedAt:$observedAt,
     migrations:$migrations,refreshEnabled:($refresh==1),contributionServingEnabled:($contribution==1),
     legacyCostServingEnabled:($legacy==1),biState:$biState,sourceNotReadyCount:$sourceNotReady,passed:true}' \
    >"$tmp/evidence.json"
  destination="$(evidence_file_for schema)"; write_evidence "$tmp/evidence.json" "$destination"
}

run_preflight() {
  local source_state destination
  require_sha "$expected_backend_sha" "expected backend SHA"
  source_state="$(db_read "/* practices:preflight */ SELECT CONCAT_WS('|',COUNT(*),SUM(source_state='READY'),
    SUM(attempt_token IS NOT NULL),SUM(recovery_token IS NOT NULL),SUM(async_pending_count),
    (SELECT COUNT(*) FROM practice_cost_basis_refresh_request WHERE status IN ('RUNNING','FAILED')),
    (SELECT COUNT(*) FROM practice_revenue_publication WHERE owner_token IS NOT NULL))
    FROM practice_revenue_source_watermark;")"
  IFS='|' read -r source_count ready_count source_owners recovery_owners async_pending blocked_cost revenue_owners <<<"$source_state"
  [[ "$source_count" == 9 && "$ready_count" == 9 && "$source_owners" == 0 &&
     "$recovery_owners" == 0 && "$async_pending" == 0 && "$blocked_cost" == 0 && "$revenue_owners" == 0 ]] \
    || die "source/publication preflight is not quiescent and READY"
  wait_for_ecs backend "$expected_backend_sha" "${PRACTICES_PREFLIGHT_WAIT_SECONDS:-60}"
  "$jq_bin" -n --arg environment "$environment" --arg backendSha "$expected_backend_sha" \
    --arg task "$ECS_TASK_DEFINITION" --arg observedAt "$(captured_at)" '
    {schemaVersion:1,mode:"preflight",environment:$environment,backendSha:$backendSha,
     taskDefinitionArn:$task,sourceWatermarkCount:9,sourceReadyCount:9,observedAt:$observedAt,passed:true}' \
    >"$tmp/evidence.json"
  destination="$(evidence_file_for preflight)"; write_evidence "$tmp/evidence.json" "$destination"
}

run_refresh() {
  local before after line prior_full prior_cost prior_revenue final_full final_cost final_revenue
  local request request_id request_key request_vector request_status successor result_cost compared_cost
  local cost_generation source_vector destination full_bi_deadline full_bi_remaining
  require_sha "$expected_backend_sha" "expected backend SHA"
  [[ "$full_bi_mode" == invoke || "$full_bi_mode" == await-event ]] || die "refresh requires --full-bi-mode invoke or await-event"
  [[ -n "$full_bi_wait_seconds" && -n "$cost_basis_wait_seconds" && -n "$revenue_wait_seconds" ]] \
    || die "refresh requires all three bounded waits"
  require_bounded_seconds "$full_bi_wait_seconds" 10800 "full BI wait"
  require_bounded_seconds "$cost_basis_wait_seconds" 1800 "cost-basis wait"
  require_bounded_seconds "$revenue_wait_seconds" 1800 "revenue wait"
  before="$(state_snapshot)"
  IFS='|' read -r prior_full _ _ _ prior_cost _ _ _ _ prior_revenue _ _ _ _ _ _ _ _ _ <<<"$before"
  full_bi_deadline=$((SECONDS + full_bi_wait_seconds))
  if [[ "$full_bi_mode" == invoke ]]; then db_invoke_full_bi "$full_bi_wait_seconds"; fi
  full_ready_callback() {
    line="$(state_snapshot)"
    IFS='|' read -r final_full bi_status bi_owner certified _rest <<<"$line"
    [[ "$bi_status" == READY && -z "$bi_owner" && "$final_full" =~ ^[0-9]+$ &&
       "$prior_full" =~ ^[0-9]+$ && "$final_full" -gt "$prior_full" && -n "$certified" ]]
  }
  full_bi_remaining=$((full_bi_deadline - SECONDS))
  ((full_bi_remaining > 0)) || die "full BI invocation consumed its certification deadline"
  poll_until "$full_bi_remaining" "full BI certification" full_ready_callback
  request="$(latest_request_snapshot)"; [[ -n "$request" ]] || die "full BI produced no cost-basis request"
  IFS='|' read -r request_id request_key request_vector request_status successor result_cost compared_cost _ _ <<<"$request"
  while [[ "$request_status" == SUPERSEDED ]]; do
    [[ -n "$successor" ]] || die "superseded cost request lacks an explicit successor"
    request="$(request_snapshot "$successor")"; [[ -n "$request" ]] || die "explicit cost successor is missing"
    IFS='|' read -r request_id request_key request_vector request_status successor result_cost compared_cost _ _ <<<"$request"
  done
  if [[ "$request_status" == PENDING ]]; then
    "$jq_bin" -n --arg id "$request_id" --arg key "$request_key" --arg vector "$request_vector" \
      '{confirm:true,expectedRequestId:$id,expectedRequestKey:$key,expectedInputVectorFingerprint:$vector}' \
      >"$tmp/cost-start.json"
    post_system_json "$SYSTEM_BATCH_PATH/practice-cost-basis-refresh/start" "$tmp/cost-start.json" "$tmp/cost-start-response.json"
  elif [[ "$request_status" != READY && "$request_status" != NO_CHANGE ]]; then
    die "latest cost request is neither startable nor certified"
  fi
  cost_ready_callback() {
    request="$(request_snapshot "$request_id")"; [[ -n "$request" ]] || return 1
    IFS='|' read -r request_id request_key request_vector request_status successor result_cost compared_cost _ _ <<<"$request"
    if [[ "$request_status" == SUPERSEDED ]]; then
      [[ -n "$successor" ]] || return 1
      request_id="$successor"; return 1
    fi
    [[ "$request_status" == READY || "$request_status" == NO_CHANGE ]] || return 1
    cost_generation="$result_cost"; [[ "$request_status" == NO_CHANGE ]] && cost_generation="$compared_cost"
    [[ -n "$cost_generation" ]] || return 1
    line="$(state_snapshot)"
    IFS='|' read -r _ _ _ _ final_cost cost_state cost_owner latest_request certified_request _rest <<<"$line"
    [[ "$cost_state" == READY && -z "$cost_owner" && "$final_cost" == "$cost_generation" &&
       "$latest_request" == "$request_id" && "$certified_request" == "$request_id" ]]
  }
  poll_until "$cost_basis_wait_seconds" "cost-basis certification" cost_ready_callback
  "$jq_bin" -n '{confirm:true}' >"$tmp/revenue-start.json"
  post_system_json "$SYSTEM_BATCH_PATH/practice-revenue-refresh/start" "$tmp/revenue-start.json" "$tmp/revenue-start-response.json"
  revenue_ready_callback() {
    after="$(state_snapshot)"
    IFS='|' read -r final_full _ _ _ final_cost _ _ _ _ final_revenue _ revenue_state revenue_owner paired_cost _rest <<<"$after"
    [[ "$revenue_state" == READY && -z "$revenue_owner" && -n "$final_revenue" &&
       "$paired_cost" == "$cost_generation" && "$final_cost" == "$cost_generation" &&
       ( -z "$prior_revenue" || "$final_revenue" != "$prior_revenue" ) ]]
  }
  poll_until "$revenue_wait_seconds" "revenue publication" revenue_ready_callback
  source_vector="$(source_vector_fingerprint)"; require_fingerprint "$source_vector" "published source vector"
  "$jq_bin" -n --arg environment "$environment" --arg backendSha "$expected_backend_sha" \
    --arg fullVersion "$final_full" --arg costGeneration "$cost_generation" --arg requestId "$request_id" \
    --arg requestKey "$request_key" --arg inputVector "$request_vector" --arg revenueGeneration "$final_revenue" \
    --arg sourceVector "$source_vector" --arg observedAt "$(captured_at)" '
    {schemaVersion:1,mode:"refresh",environment:$environment,backendSha:$backendSha,
     fullBiVersion:$fullVersion,costGenerationAt:$costGeneration,costRequestId:$requestId,
     costRequestKey:$requestKey,costInputVectorFingerprint:$inputVector,
     revenueGenerationId:$revenueGeneration,sourceVectorFingerprint:$sourceVector,
     observedAt:$observedAt,sequence:["FULL_BI","COST_BASIS","REVENUE"],passed:true}' \
    >"$tmp/evidence.json"
  destination="$(evidence_file_for refresh)"; write_evidence "$tmp/evidence.json" "$destination"
}

run_revenue_recovery_refresh() {
  local fields destination response execution_id final_state final_execution final_generation paired source_vector
  [[ -n "$expected_cost_generation_at" && -n "$expected_cost_request_id" &&
     -n "$expected_cost_request_key" && -n "$expected_cost_input_vector" &&
     -n "$expected_source_vector" && -n "$revenue_wait_seconds" ]] \
    || die "revenue recovery requires its complete frozen vector and bounded wait"
  [[ "$expected_cost_request_id" =~ ^[1-9][0-9]*$ ]] || die "invalid expected cost request ID"
  require_fingerprint "$expected_cost_request_key" "expected cost request key"
  require_fingerprint "$expected_cost_input_vector" "expected cost input vector"
  require_fingerprint "$expected_source_vector" "expected source vector"
  require_bounded_seconds "$revenue_wait_seconds" 1800 "revenue wait"
  fields="$(db_read "/* practices:recovery-precheck */ SELECT CONCAT_WS('|',
    CAST(c.refresh_enabled AS UNSIGNED),CAST(c.contribution_serving_enabled AS UNSIGNED),
    CAST(c.legacy_cost_serving_enabled AS UNSIGNED),COALESCE(c.revenue_recovery_state,''),
    COALESCE(DATE_FORMAT(o.generation_at,'%Y-%m-%dT%H:%i:%s.%fZ'),''),
    COALESCE(CAST(o.latest_cost_basis_request_id AS CHAR),''),COALESCE(r.request_key,''),
    COALESCE(r.input_vector_fingerprint,''),r.status,
    (SELECT COUNT(*) FROM practice_cost_basis_refresh_request x
      WHERE x.status IN ('PENDING','RUNNING','FAILED') OR x.request_id>r.request_id),
    COALESCE((SELECT SHA2(GROUP_CONCAT(CONCAT(w.source_name,'=',w.source_version)
      ORDER BY w.source_name SEPARATOR '|'),256) FROM practice_revenue_source_watermark w
      HAVING COUNT(*)=9 AND SUM(w.source_state<>'READY')=0),''))
  FROM practice_contribution_publication_control c
  JOIN practice_operating_cost_publication o ON o.publication_id=1
  JOIN practice_cost_basis_refresh_request r ON r.request_id=o.certified_cost_basis_request_id
  WHERE c.control_id=1;")"
  IFS='|' read -r refresh serving legacy recovery_state cost_at request_id request_key input_vector request_status blocked source_vector <<<"$fields"
  [[ "$refresh" == 0 && "$serving" == 0 && "$legacy" == 1 && -z "$recovery_state" &&
     "$cost_at" == "$expected_cost_generation_at" && "$request_id" == "$expected_cost_request_id" &&
     "$request_key" == "$expected_cost_request_key" && "$input_vector" == "$expected_cost_input_vector" &&
     ( "$request_status" == READY || "$request_status" == NO_CHANGE ) && "$blocked" == 0 &&
     "$source_vector" == "$expected_source_vector" ]] || die "revenue recovery frozen-vector precheck failed"
  "$jq_bin" -n --arg cost "$expected_cost_generation_at" --arg id "$expected_cost_request_id" \
    --arg key "$expected_cost_request_key" --arg input "$expected_cost_input_vector" \
    --arg source "$expected_source_vector" '
    {confirm:true,expectedCostGenerationAt:$cost,expectedCostRequestId:$id,
     expectedCostRequestKey:$key,expectedCostInputVectorFingerprint:$input,
     expectedSourceVectorFingerprint:$source}' >"$tmp/recovery-start.json"
  post_system_json "$SYSTEM_BATCH_PATH/practice-revenue-refresh/recovery-start" \
    "$tmp/recovery-start.json" "$tmp/recovery-start-response.json"
  execution_id="$("$jq_bin" -er '.executionId|select(type=="string" and length>0)' "$tmp/recovery-start-response.json")" \
    || die "recovery start omitted its execution ID"
  recovery_ready_callback() {
    fields="$(state_snapshot)"
    IFS='|' read -r _ _ _ _ final_cost _ _ _ _ final_generation _ publication_state owner paired _ _ _ final_state final_execution <<<"$fields"
    [[ "$publication_state" == READY && -z "$owner" && "$paired" == "$expected_cost_generation_at" &&
       "$final_cost" == "$expected_cost_generation_at" && "$final_state" == BUILT &&
       "$final_execution" == "$execution_id" && -n "$final_generation" ]]
  }
  poll_until "$revenue_wait_seconds" "incident revenue recovery" recovery_ready_callback
  "$jq_bin" -n --arg environment "$environment" --arg executionId "$execution_id" \
    --arg revenueGenerationId "$final_generation" --arg costGenerationAt "$expected_cost_generation_at" \
    --arg costRequestId "$expected_cost_request_id" --arg costRequestKey "$expected_cost_request_key" \
    --arg costInputVectorFingerprint "$expected_cost_input_vector" --arg sourceVectorFingerprint "$expected_source_vector" \
    --arg observedAt "$(captured_at)" '
    {schemaVersion:1,mode:"revenue-recovery-refresh",environment:$environment,
     recoveryExecutionId:$executionId,revenueGenerationId:$revenueGenerationId,
     pairedCostGenerationAt:$costGenerationAt,costRequestId:$costRequestId,
     costRequestKey:$costRequestKey,costInputVectorFingerprint:$costInputVectorFingerprint,
     sourceVectorFingerprint:$sourceVectorFingerprint,ordinaryFullBiInvoked:false,
     ordinaryCostBasisInvoked:false,observedAt:$observedAt,passed:true}' >"$tmp/evidence.json"
  destination="$(evidence_file_for revenue-recovery-refresh)"; write_evidence "$tmp/evidence.json" "$destination"
}

cost_reconcile_snapshot() {
  db_read "/* practices:cost-reconcile */ SELECT CONCAT_WS('|',o.refresh_state,
    COALESCE(o.active_refresh_token,''),COALESCE(DATE_FORMAT(o.generation_at,'%Y-%m-%dT%H:%i:%s.%fZ'),''),
    COALESCE(o.practice_basis_generation_id,''),COALESCE(CAST(o.latest_cost_basis_request_id AS CHAR),''),
    COALESCE(CAST(o.certified_cost_basis_request_id AS CHAR),''),COALESCE(r.status,''),
    COALESCE(r.request_key,''),COALESCE(r.input_vector_fingerprint,''),
    CAST(o.booked_available AS UNSIGNED),COALESCE(o.booked_reason,''),
    COALESCE(DATE_FORMAT(o.booked_current_start_month,'%Y-%m-%d'),''),
    COALESCE(DATE_FORMAT(o.booked_current_end_month,'%Y-%m-%d'),''),
    COALESCE(DATE_FORMAT(o.booked_prior_start_month,'%Y-%m-%d'),''),
    COALESCE(DATE_FORMAT(o.booked_prior_end_month,'%Y-%m-%d'),''),
    CAST(o.booked_plus_draft_available AS UNSIGNED),COALESCE(o.booked_plus_draft_reason,''),
    COALESCE(DATE_FORMAT(o.booked_plus_draft_current_start_month,'%Y-%m-%d'),''),
    COALESCE(DATE_FORMAT(o.booked_plus_draft_current_end_month,'%Y-%m-%d'),''),
    COALESCE(DATE_FORMAT(o.booked_plus_draft_prior_start_month,'%Y-%m-%d'),''),
    COALESCE(DATE_FORMAT(o.booked_plus_draft_prior_end_month,'%Y-%m-%d'),''),
    CAST(c.legacy_cost_serving_enabled AS UNSIGNED),
    (SELECT COUNT(*) FROM fact_practice_cost_completeness_generation_mat f
      WHERE f.generation_id=o.practice_basis_generation_id),
    (SELECT COUNT(*) FROM practice_cost_basis_refresh_request newer WHERE newer.request_id>r.request_id))
  FROM practice_operating_cost_publication o
  JOIN practice_contribution_publication_control c ON c.control_id=1
  LEFT JOIN practice_cost_basis_refresh_request r ON r.request_id=o.certified_cost_basis_request_id
  WHERE o.publication_id=1;"
}

run_reconcile() {
  local cost_fields cost_state cost_owner cost_generation basis_id latest_request certified_request request_status
  local request_key input_vector booked_available booked_reason booked_current_start booked_current_end
  local booked_prior_start booked_prior_end draft_available draft_reason draft_current_start draft_current_end
  local draft_prior_start draft_prior_end legacy_enabled completeness_count newer_count
  local revenue_fields generation paired_cost revenue_basis revenue_status item_total allocation_total gap
  local source_vector output destination source sql_output line period_count=0 difference
  require_tool "$jq_bin" jq
  scope="${scope:-full}"; [[ "$scope" == full || "$scope" == cost-only ]] || die "reconcile scope must be full or cost-only"
  validate_cost_sources
  if [[ "$allow_structured_live_cost_gaps" == true ]]; then validate_performance_evidence; else
    [[ -z "$performance_evidence$performance_purpose$performance_target_backend_sha$performance_fixture_profile$performance_fixture_task_definition$performance_fixture_image_digest$performance_max_age_seconds$performance_recovery_class" ]] \
      || die "performance evidence arguments require --allow-structured-live-cost-gaps"
  fi
  cost_fields="$(cost_reconcile_snapshot)"
  IFS='|' read -r cost_state cost_owner cost_generation basis_id latest_request certified_request request_status \
    request_key input_vector booked_available booked_reason booked_current_start booked_current_end booked_prior_start booked_prior_end \
    draft_available draft_reason draft_current_start draft_current_end draft_prior_start draft_prior_end legacy_enabled completeness_count newer_count <<<"$cost_fields"
  [[ "$cost_state" == READY && -z "$cost_owner" && -n "$cost_generation" && -n "$basis_id" &&
     -n "$latest_request" && "$latest_request" == "$certified_request" &&
     ( "$request_status" == READY || "$request_status" == NO_CHANGE ) &&
     -n "$request_key" && -n "$input_vector" && "$newer_count" == 0 ]] \
    || die "cost publication/request reconciliation failed"
  [[ -z "$expected_cost_generation_at" || "$cost_generation" == "$expected_cost_generation_at" ]] \
    || die "named cost generation is not the READY publication"
  [[ -n "$booked_current_start" && -n "$booked_current_end" && -n "$booked_prior_start" && -n "$booked_prior_end" &&
     -n "$draft_current_start" && -n "$draft_current_end" && -n "$draft_prior_start" && -n "$draft_prior_end" ]] \
    || die "cost-source windows are not immutably recorded"
  if [[ "$allow_structured_live_cost_gaps" == false ]]; then
    [[ "$booked_available" == 1 && "$draft_available" == 1 && "$completeness_count" -gt 0 ]] \
      || die "strict reconciliation found unavailable cost/FTE evidence"
  else
    [[ ( "$booked_available" == 1 || -n "$booked_reason" ) &&
       ( "$draft_available" == 1 || -n "$draft_reason" ) ]] \
      || die "structured cost gaps lack explicit reasons"
  fi
  source_vector="$(source_vector_fingerprint)"; require_fingerprint "$source_vector" "source vector"
  generation=""; paired_cost=""; revenue_basis=""; revenue_status=""
  if [[ "$scope" == full ]]; then
    revenue_fields="$(db_read "/* practices:revenue-reconcile */ SELECT CONCAT_WS('|',
      COALESCE(p.published_generation_id,''),COALESCE(DATE_FORMAT(p.paired_cost_generation_at,'%Y-%m-%dT%H:%i:%s.%fZ'),''),
      COALESCE(p.practice_basis_generation_id,''),p.status,COALESCE(CAST(p.item_control_total_dkk AS CHAR),''),
      COALESCE(CAST(p.allocation_total_dkk AS CHAR),''),COALESCE(CAST(p.reconciliation_gap_dkk AS CHAR),''),
      COALESCE(p.owner_token,''),CAST(c.contribution_serving_enabled AS UNSIGNED),
      (SELECT COUNT(*) FROM practice_revenue_source_watermark w WHERE w.source_state<>'READY'))
    FROM practice_revenue_publication p JOIN practice_contribution_publication_control c ON c.control_id=1
    WHERE p.publication_key='PRACTICE_CONTRIBUTION';")"
    IFS='|' read -r generation paired_cost revenue_basis revenue_status item_total allocation_total gap revenue_owner serving source_not_ready <<<"$revenue_fields"
    require_uuid "$generation" "published revenue generation"
    [[ "$revenue_status" == READY && -n "$generation" && -z "$revenue_owner" &&
       "$paired_cost" == "$cost_generation" && "$revenue_basis" == "$basis_id" &&
       "$item_total" == "$allocation_total" && "$gap" =~ ^-?0+(\.0+)?$ && "$source_not_ready" == 0 ]] \
      || die "revenue publication reconciliation failed"
    # Execute the checked-in aggregate-only, read-only reconciliation for each independent source window.
    : >"$tmp/reconciliation-all.tsv"
    for source in BOOKED BOOKED_PLUS_DRAFT; do
      if [[ "$source" == BOOKED ]]; then
        current_start="$booked_current_start"; current_end="$booked_current_end"
        prior_start="$booked_prior_start"; prior_end="$booked_prior_end"
      else
        current_start="$draft_current_start"; current_end="$draft_current_end"
        prior_start="$draft_prior_start"; prior_end="$draft_prior_end"
      fi
      require_month_date "$current_start" "$source current start"
      require_month_date "$current_end" "$source current end"
      require_month_date "$prior_start" "$source prior start"
      require_month_date "$prior_end" "$source prior end"
      [[ "$(sha256_file "$RECONCILIATION_SQL")" == "$RECONCILIATION_SQL_SHA256" ]] \
        || die "checked-in reconciliation SQL differs from its reviewed checksum"
      sed -e "s/:generation_id/'$generation'/g" \
          -e "s/:current_start_month/'$current_start'/g" -e "s/:current_end_month/'$current_end'/g" \
          -e "s/:prior_start_month/'$prior_start'/g" -e "s/:prior_end_month/'$prior_end'/g" \
          "$RECONCILIATION_SQL" >"$tmp/reconciliation-$source.sql"
      db_read_file "$tmp/reconciliation-$source.sql" >"$tmp/reconciliation-$source.tsv"
      while IFS=$'\t' read -r f1 f2 gl_control gl_allocation difference \
          gl_document_count invalid_gl_document_count _rest; do
        if [[ "$f1" == "$generation" && ( "$f2" == CURRENT || "$f2" == PRIOR ) ]]; then
          [[ "$gl_document_count" =~ ^[0-9]+$ && "$invalid_gl_document_count" == 0 ]] \
            || die "$source $f2 GL document evidence is not unique and cent-normalized"
          if [[ "$gl_document_count" == 0 ]]; then
            [[ ( -z "$gl_control" || "$gl_control" == NULL ) &&
               ( -z "$gl_allocation" || "$gl_allocation" == NULL ) &&
               ( -z "$difference" || "$difference" == NULL ) ]] \
              || die "$source $f2 empty GL subset must reconcile as NULL"
          else
            [[ "$gl_control" =~ ^-?[0-9]+(\.[0-9]+)?$ &&
               "$gl_allocation" =~ ^-?[0-9]+(\.[0-9]+)?$ &&
               "$difference" =~ ^-?0+(\.0+)?$ ]] \
              || die "$source $f2 GL allocation does not conserve against document controls"
          fi
          ((period_count+=1))
        fi
      done <"$tmp/reconciliation-$source.tsv"
      cat "$tmp/reconciliation-$source.tsv" >>"$tmp/reconciliation-all.tsv"
    done
    [[ "$period_count" == 4 ]] || die "reconciliation did not return both periods for both cost sources"
  fi
  "$jq_bin" -n --arg environment "$environment" --arg scope "$scope" \
    --arg revenueGenerationId "$generation" --arg pairedCostGenerationAt "$cost_generation" \
    --arg practiceBasisGenerationId "$basis_id" --arg costRequestId "$certified_request" \
    --arg costRequestKey "$request_key" --arg costInputVectorFingerprint "$input_vector" \
    --arg sourceVectorFingerprint "$source_vector" --arg bookedReason "$booked_reason" \
    --arg draftReason "$draft_reason" --argjson bookedAvailable "$booked_available" \
    --argjson draftAvailable "$draft_available" --argjson legacyEnabled "$legacy_enabled" \
    --arg observedAt "$(captured_at)" '
    {schemaVersion:1,mode:"reconcile",environment:$environment,scope:$scope,
     revenueGenerationId:(if $revenueGenerationId=="" then null else $revenueGenerationId end),
     pairedCostGenerationAt:$pairedCostGenerationAt,practiceBasisGenerationId:$practiceBasisGenerationId,
     costRequestId:$costRequestId,costRequestKey:$costRequestKey,
     costInputVectorFingerprint:$costInputVectorFingerprint,sourceVectorFingerprint:$sourceVectorFingerprint,
     costSources:{BOOKED:{available:($bookedAvailable==1),reason:(if $bookedReason=="" then null else $bookedReason end)},
       BOOKED_PLUS_DRAFT:{available:($draftAvailable==1),reason:(if $draftReason=="" then null else $draftReason end)}},
     legacyCostServingEnabled:($legacyEnabled==1),aggregateOnly:true,consultantRevenueExposed:false,
     observedAt:$observedAt,passed:true}' >"$tmp/evidence.json"
  destination="$(evidence_file_for reconcile)"; write_evidence "$tmp/evidence.json" "$destination"
}

assert_aggregate_contract() {
  local file="$1" contract="$2"
  if [[ "$contract" == contribution ]]; then
    "$jq_bin" -e '
      def exact($allowed): type=="object" and ((keys|sort)==($allowed|sort));
      def money_map: type=="object" and all(to_entries[];
        ((.key|test("^[A-Z]{3}$")) and (.value|(type=="string" and test("^-?[0-9]+\\.[0-9]{2}$")))));
      def source_versions:
        type=="object" and
        ((keys|sort)==(["INVOICE_DOCUMENT","FINANCE_GL","CURRENCY","ACCOUNT_CLASSIFICATION",
          "INVOICE_ATTRIBUTION","SELF_BILLED","PHANTOM_ATTRIBUTION","DELIVERY_EVIDENCE",
          "PRACTICE_BASIS_INPUT"]|sort)) and
        all(to_entries[]; (.value|(type=="string" and test("^[0-9]+$"))));
      def evidence:
        exact(["sourceDocumentCount","sourceItemCount","valuedItemCount","valuationItemCoveragePct",
          "missingDkkControlCount","missingNativeAmountsByCurrency","confirmedAttributedRevenueDkk",
          "estimatedRevenueDkk","partialAttributionAffectedRevenueDkk","unassignedRevenueDkk",
          "confirmedAttributedCoveragePct","attributedCoveragePct","unassignedCoveragePct",
          "duplicateRiskDocumentCount","documentGlControlDkk","allocatedRevenueDkk",
          "reconciliationDifferenceDkk","reconciliationStatus"]) and
        (.missingNativeAmountsByCurrency|money_map);
      def period:
        exact(["startMonth","endMonth","sourceStatus","sourceReason","fxStatus","fxReason",
          "attributionStatus","attributionReason","revenueAttributionMethod",
          "revenueRegisteredWorkValueAllocationCount","revenueRegisteredHoursAllocationCount",
          "revenueScheduledCapacityFallbackAllocationCount","revenueMonthEndPracticeFallbackAllocationCount",
          "costMonthEndPracticeFallbackEmployeeMonthCount","historicalPracticeFallbackAllocationCount",
          "historicalPracticeFallbackUsed","attributionExplanationCode","attributionExplanation",
          "costCompletenessStatus","costCompletenessReason","fteCompletenessStatus","fteCompletenessReason",
          "expectedFteCellCount","coveredFteCellCount","missingFteCellCount","practiceBasisStatus",
          "practiceBasisReason","contributionStatus","availabilityReason","evidence"]) and (.evidence|evidence);
      def portfolio:
        exact(["recognizedNetRevenueDkk","corePracticeRevenueDkk","revenueOnlySegmentDkk",
          "confirmedAttributedRevenueDkk","estimatedRevenueDkk","partialAttributionAffectedRevenueDkk",
          "portfolioUnassignedRevenueDkk","confirmedAttributedCoveragePct","attributedCoveragePct",
          "unassignedCoveragePct","sourceDocumentCount","sourceItemCount","valuedItemCount",
          "valuationItemCoveragePct","duplicateRiskDocumentCount","missingDkkControlCount",
          "missingNativeAmountsByCurrency","reconciliationDifferenceDkk","reconciliationStatus",
          "availabilityReason"]) and (.missingNativeAmountsByCurrency|money_map);
      def practice_metrics:
        exact(["netAttributedRevenueDkk","provisionalNetAttributedRevenueDkk","salaryDkk","opexDkk",
          "operatingCostDkk","contributionDkk","contributionMarginPct","averageFte","costPerFteDkk",
          "confirmedAttributedRevenueDkk","estimatedRevenueDkk","partialAttributionAffectedRevenueDkk",
          "unassignedRevenueDkk","sourceStatus","sourceReason","attributionCoveragePct","valuationStatus",
          "valuationReason","attributionStatus","attributionReason","costCompletenessStatus",
          "costCompletenessReason","fteCompletenessStatus","fteCompletenessReason","expectedFteCellCount",
          "coveredFteCellCount","missingFteCellCount","practiceBasisStatus","practiceBasisReason",
          "contributionStatus","availabilityReason"]);
      def practice:
        exact(["practiceId","label","current","prior","revenueDeltaDkk","revenueDeltaPct","costDeltaDkk",
          "costDeltaPct","contributionDeltaDkk","contributionDeltaPct","contributionMarginDeltaPoints"]) and
        (.current|(.==null or practice_metrics)) and (.prior|(.==null or practice_metrics));
      def segment_metrics:
        exact(["displayRevenueDkk","revenueDisplayStatus","netAttributedRevenueDkk",
          "provisionalNetAttributedRevenueDkk","confirmedAttributedRevenueDkk","estimatedRevenueDkk",
          "partialAttributionAffectedRevenueDkk","unassignedRevenueDkk","sourceStatus","sourceReason",
          "valuationStatus","valuationReason","attributionStatus","attributionReason","sourceCoveragePct",
          "availabilityReason","explanation"]);
      def segment:
        exact(["segmentId","label","current","prior","revenueDeltaDkk","revenueDeltaPct"]) and
        (.current|(.==null or segment_metrics)) and (.prior|(.==null or segment_metrics));
      exact(["scope","revenueBasis","costSource","responseStatus","responseReason","reportingThroughMonth",
        "currentPeriod","priorPeriod","revenueGenerationId","revenuePublishedAt","revenueSourceRefreshedAt",
        "fullBiRefreshVersion","sourceWatermarkVersions","pairedCostGenerationAt","costPublishedAt",
        "practiceBasisGenerationId","revenueAttributionMethod","costAttributionMethod",
        "revenueHistoryCoverageStart","costHistoryCoverageStart","practiceBasesAligned",
        "practiceBasesAlignmentReason","currentPortfolio","priorPortfolio","practices","revenueOnlySegments"]) and
      (.sourceWatermarkVersions|source_versions) and
      (.currentPeriod|(.==null or period)) and (.priorPeriod|(.==null or period)) and
      (.currentPortfolio|(.==null or portfolio)) and (.priorPortfolio|(.==null or portfolio)) and
      (.practices|(type=="array" and all(.[];practice))) and
      (.revenueOnlySegments|(type=="array" and all(.[];segment))) and
      (.responseStatus!="UNAVAILABLE_COST" or
        (all(.practices[]; .current==null and .prior==null) and
         all(.revenueOnlySegments[]; .current==null and .prior==null))) and
      ([paths(scalars) as $p | select(($p[-1]|tostring|endswith("Dkk"))) |
        getpath($p) | select(.!=null and ((type!="string") or (test("^-?[0-9]+\\.[0-9]{2}$")|not)))] | length)==0
    ' "$file" >/dev/null || die "contribution API response violated its exact aggregate DTO contract"
  else
    "$jq_bin" -e '
      def exact($allowed): type=="object" and ((keys|sort)==($allowed|sort));
      def practice: exact(["practiceId","currentSalaryDkk","currentOpexDkk","currentTotalDkk",
        "priorSalaryDkk","priorOpexDkk","priorTotalDkk","totalDeltaDkk","totalDeltaPct",
        "currentAverageFte","priorAverageFte","currentCostPerFteDkk","priorCostPerFteDkk",
        "costPerFteDeltaDkk","costPerFteDeltaPct"]);
      exact(["costSource","reportingThroughMonthKey","currentPeriodStartMonthKey","currentPeriodEndMonthKey",
        "priorPeriodStartMonthKey","priorPeriodEndMonthKey","sourceRefreshedAt","currentSalaryMonthCount",
        "currentOpexMonthCount","currentFteMonthCount","priorSalaryMonthCount","priorOpexMonthCount",
        "priorFteMonthCount","currentExpectedSalaryCellCount","currentActualSalaryCellCount",
        "currentCoveredSalaryCellCount","currentMissingSalaryCellCount","currentUnexpectedSalaryCellCount",
        "priorExpectedSalaryCellCount","priorActualSalaryCellCount","priorCoveredSalaryCellCount",
        "priorMissingSalaryCellCount","priorUnexpectedSalaryCellCount","currentExpectedFteCellCount",
        "currentCoveredFteCellCount","currentMissingFteCellCount","priorExpectedFteCellCount",
        "priorCoveredFteCellCount","priorMissingFteCellCount","currentCostComplete","currentFteComplete",
        "currentCompletenessStatus","priorCostComplete","priorFteComplete","priorCompletenessStatus",
        "completenessStatus","complete","practiceAttribution","practiceAttributionCoverageStartDate",
        "attributionNote","practices"]) and
      (.practices|(type=="array" and all(.[];practice)))
    ' "$file" >/dev/null || die "legacy cost API response violated its exact aggregate DTO contract"
  fi
}

run_api() {
  local base auth default_path destination expected_sha response status headers booked
  surface="${surface:-backend}"
  [[ "$surface" == backend || "$surface" == bff || "$surface" == legacy-bff ]] || die "unknown API surface"
  require_sha "$expected_backend_sha" "expected backend SHA"
  if [[ "$surface" != backend ]]; then require_sha "$expected_frontend_sha" "expected frontend SHA"; fi
  if [[ "$surface" == backend ]]; then
    wait_for_ecs backend "$expected_backend_sha" "${PRACTICES_API_DEPLOY_WAIT_SECONDS:-60}"
    base="$(backend_base_url)"; auth=dashboard; default_path="$CONTRIBUTION_PATH"
  else
    wait_for_ecs frontend "$expected_frontend_sha" "${PRACTICES_API_DEPLOY_WAIT_SECONDS:-60}"
    base="$(frontend_base_url)"; auth=admin-cookie
    [[ "$surface" == bff ]] && default_path="$BFF_CONTRIBUTION_PATH" || default_path="$BFF_OPERATING_COST_PATH"
  fi
  http_request "$auth" GET "$base$default_path" "" "$tmp/api-default.json" "$tmp/api-default.headers" 12
  [[ "$HTTP_STATUS" == 200 ]] || die "$surface canonical API returned HTTP $HTTP_STATUS"
  "$jq_bin" -e 'type=="object"' "$tmp/api-default.json" >/dev/null || die "API response was not JSON"
  contract=contribution; [[ "$surface" == legacy-bff ]] && contract=legacy-cost
  assert_aggregate_contract "$tmp/api-default.json" "$contract"
  http_request "$auth" GET "$base$default_path?costSource=BOOKED" "" "$tmp/api-booked.json" "$tmp/api-booked.headers" 12
  [[ "$HTTP_STATUS" == 200 ]] || die "$surface BOOKED API returned HTTP $HTTP_STATUS"
  assert_aggregate_contract "$tmp/api-booked.json" "$contract"
  if [[ "$surface" != legacy-bff ]]; then
    for query in 'costSource=' 'costSource=booked' 'costSource=UNKNOWN' 'costSource=BOOKED&costSource=BOOKED'; do
      http_request "$auth" GET "$base$default_path?$query" "" "$tmp/api-invalid.json" "$tmp/api-invalid.headers" 12
      [[ "$HTTP_STATUS" == 400 ]] || die "$surface accepted non-canonical costSource query"
    done
  fi
  if [[ "$surface" == bff || "$surface" == legacy-bff ]]; then
    grep -Eiq '^cache-control:.*private.*no-store|^cache-control:.*no-store.*private' "$tmp/api-default.headers" \
      || die "BFF response is not private/no-store"
    http_request nonadmin-cookie GET "$base$default_path" "" "$tmp/api-nonadmin.json" "$tmp/api-nonadmin.headers" 12
    [[ "$HTTP_STATUS" == 401 || "$HTTP_STATUS" == 403 ]] || die "non-ADMIN caller reached the protected BFF"
  else
    http_request system GET "$base$default_path" "" "$tmp/api-wrong-scope.json" "$tmp/api-wrong-scope.headers" 12
    [[ "$HTTP_STATUS" == 401 || "$HTTP_STATUS" == 403 ]] || die "system-only token reached dashboard aggregate API"
  fi
  "$jq_bin" -n --arg environment "$environment" --arg surface "$surface" \
    --arg backendSha "$expected_backend_sha" --arg frontendSha "$expected_frontend_sha" \
    --arg observedAt "$(captured_at)" '
    {schemaVersion:1,mode:"api",environment:$environment,surface:$surface,
     backendSha:$backendSha,frontendSha:(if $frontendSha=="" then null else $frontendSha end),
     canonicalQueryPassed:true,strictQueryRejectionPassed:true,accessControlPassed:true,
     aggregateBoundaryPassed:true,observedAt:$observedAt,passed:true}' >"$tmp/evidence.json"
  destination="$(evidence_file_for "api-$surface")"; write_evidence "$tmp/evidence.json" "$destination"
}

run_publication_control() {
  local endpoint payload action destination response_action
  [[ "$confirm_publication_control" == true ]] || die "$mode requires --confirm-publication-control"
  case "$mode" in
    publication-enable-build)
      [[ -z "$expected_generation_id$expected_cost_generation_at$expected_recovery_execution_id" ]] || die "enable-build accepts no generation input"
      endpoint="$SYSTEM_BATCH_PATH/practice-revenue-publication/enable-build"; action=ENABLE_BUILD
      "$jq_bin" -n '{confirm:true}' >"$tmp/control.json" ;;
    publication-enable-serving)
      require_uuid "$expected_generation_id" "expected revenue generation ID"
      [[ -n "$expected_cost_generation_at" ]] || die "enable-serving requires expected cost generation"
      if [[ -n "$expected_recovery_execution_id" ]]; then
        require_uuid "$expected_recovery_execution_id" "expected recovery execution ID"
        [[ "$resume_build_after_recovery" == true ]] || die "recovery completion requires --resume-build-after-recovery"
      else
        [[ "$resume_build_after_recovery" == false ]] || die "resume-build is recovery-only"
      fi
      endpoint="$SYSTEM_BATCH_PATH/practice-revenue-publication/enable-serving"; action=ENABLE_CONTRIBUTION_SERVING
      "$jq_bin" -n --arg generation "$expected_generation_id" --arg cost "$expected_cost_generation_at" \
        --arg recovery "$expected_recovery_execution_id" '
        {confirm:true,expectedGenerationId:$generation,expectedCostGenerationAt:$cost,
         expectedRecoveryExecutionId:(if $recovery=="" then null else $recovery end)}' >"$tmp/control.json" ;;
    publication-disable)
      if [[ -n "$expected_recovery_execution_id" ]]; then require_uuid "$expected_recovery_execution_id" "expected recovery execution ID"; fi
      endpoint="$SYSTEM_BATCH_PATH/practice-revenue-publication/disable"; action=DISABLE_CONTRIBUTION
      "$jq_bin" -n --arg recovery "$expected_recovery_execution_id" '
        {confirm:true,expectedRecoveryExecutionId:(if $recovery=="" then null else $recovery end)}' >"$tmp/control.json" ;;
    cost-serving-disable)
      endpoint="$SYSTEM_BATCH_PATH/practice-cost-basis-publication/disable-serving"; action=DISABLE_COST_SERVING
      "$jq_bin" -n '{confirm:true}' >"$tmp/control.json" ;;
    cost-serving-enable)
      [[ -n "$expected_cost_generation_at" ]] || die "cost serving enable requires expected generation"
      endpoint="$SYSTEM_BATCH_PATH/practice-cost-basis-publication/enable-serving"; action=ENABLE_COST_SERVING
      "$jq_bin" -n --arg cost "$expected_cost_generation_at" '{confirm:true,expectedCostGenerationAt:$cost}' >"$tmp/control.json" ;;
  esac
  post_system_json "$endpoint" "$tmp/control.json" "$tmp/control-response.json"
  response_action="$("$jq_bin" -r '.action // ""' "$tmp/control-response.json")"
  [[ "$response_action" == "$action" ]] || die "publication-control response did not confirm the closed action"
  "$jq_bin" -n --arg environment "$environment" --arg mode "$mode" --arg action "$action" \
    --arg generation "$expected_generation_id" --arg cost "$expected_cost_generation_at" \
    --arg recovery "$expected_recovery_execution_id" --arg observedAt "$(captured_at)" '
    {schemaVersion:1,mode:$mode,environment:$environment,action:$action,
     revenueGenerationId:(if $generation=="" then null else $generation end),
     costGenerationAt:(if $cost=="" then null else $cost end),
     recoveryExecutionId:(if $recovery=="" then null else $recovery end),
     observedAt:$observedAt,passed:true}' >"$tmp/evidence.json"
  destination="$(evidence_file_for "$mode")"; write_evidence "$tmp/evidence.json" "$destination"
}

run_recover_stale() {
  local endpoint execution destination state
  [[ "$confirm_stale_recovery" == true ]] || die "recover-stale requires --confirm-stale-recovery"
  [[ -n "$wait_seconds" ]] || die "recover-stale requires a bounded wait"
  require_bounded_seconds "$wait_seconds" 10800 "stale recovery wait"
  case "$category" in
    PUBLICATION|COST_BASIS|FINANCE_GL|SELF_BILLED|PHANTOM_ATTRIBUTION|DELIVERY_EVIDENCE) ;;
    *) die "unknown recovery category" ;;
  esac
  endpoint="$SYSTEM_BATCH_PATH/practice-revenue-source-recovery/$category/start"
  "$jq_bin" -n '{confirm:true,expectedOwnerToken:null}' >"$tmp/recover.json"
  post_system_json "$endpoint" "$tmp/recover.json" "$tmp/recover-response.json"
  execution="$("$jq_bin" -er '.executionId|select(type=="string" and length>0)' "$tmp/recover-response.json")" \
    || die "recovery response omitted execution ID"
  stale_recovered_callback() {
    state="$(db_read "/* practices:stale-recovery */ SELECT CASE '$category'
      WHEN 'PUBLICATION' THEN (SELECT IF(owner_token IS NULL,1,0) FROM practice_revenue_publication WHERE publication_key='PRACTICE_CONTRIBUTION')
      WHEN 'COST_BASIS' THEN (SELECT IF(active_refresh_token IS NULL,1,0) FROM practice_operating_cost_publication WHERE publication_id=1)
      ELSE (SELECT IF(source_state='READY' AND attempt_token IS NULL AND recovery_token IS NULL,1,0)
            FROM practice_revenue_source_watermark WHERE source_name='$category') END;")"
    [[ "$state" == 1 ]]
  }
  poll_until "$wait_seconds" "$category stale recovery" stale_recovered_callback
  "$jq_bin" -n --arg environment "$environment" --arg category "$category" \
    --arg executionId "$execution" --arg observedAt "$(captured_at)" '
    {schemaVersion:1,mode:"recover-stale",environment:$environment,category:$category,
     executionId:$executionId,observedAt:$observedAt,passed:true}' >"$tmp/evidence.json"
  destination="$(evidence_file_for "recover-stale-$category")"; write_evidence "$tmp/evidence.json" "$destination"
}

wait_for_workflow() {
  local timeout="$1" pre_observed_at="$2" runs
  require_tool "$gh_bin" gh
  workflow_callback() {
    runs="$tmp/workflow-runs.json"
    run_with_deadline "$(command_deadline 30)" "$gh_bin" run list --workflow "$expected_workflow_path" --commit "$expected_backend_sha" \
      --limit 20 --json databaseId,headSha,status,conclusion,workflowName,createdAt >"$runs" || return 1
    WORKFLOW_RUN_ID="$("$jq_bin" -r --arg sha "$expected_backend_sha" --arg pre "$pre_observed_at" '
      [.[]|select(.headSha==$sha and .status=="completed" and .conclusion=="success" and
        ((.createdAt|fromdateiso8601)>=($pre|fromdateiso8601)))]
      | if length==1 then .[0].databaseId else "" end' "$runs")"
    [[ -n "$WORKFLOW_RUN_ID" ]]
  }
  poll_until "$timeout" "exact deployment workflow" workflow_callback
}

run_deploy() {
  local destination observed_at workflow_expected pre_gate await_gate post_gate drain_cmd
  [[ "$component" == backend || "$component" == frontend || "$component" == performance-fixture ]] \
    || die "deploy requires component backend, frontend, or performance-fixture"
  [[ "$component" != performance-fixture || "$environment" == staging ]] \
    || die "performance fixture cannot target production"
  if [[ "$component" == backend ]]; then require_sha "$expected_backend_sha" "expected backend SHA"; fi
  if [[ "$component" == frontend ]]; then require_sha "$expected_frontend_sha" "expected frontend SHA"; fi
  if [[ "$component" == performance-fixture ]]; then
    require_sha "$expected_backend_sha" "expected backend SHA"; [[ -n "$expected_task_definition" ]] || die "fixture task definition is required"
    require_digest "$expected_image_digest" "fixture image digest"
  fi
  if [[ -n "$phase" ]]; then
    [[ "$component" == backend ]] || die "deployment phases apply only to backend"
    [[ "$phase" == pre || "$phase" == await || "$phase" == post || "$phase" == release ]] || die "unknown backend deploy phase"
  fi
  if [[ "$phase" == pre ]]; then
    [[ "$require_writer_quiescence" == true && -n "$writer_evidence_file" &&
       -n "$writer_max_age_seconds" && -n "$writer_min_validity_seconds" && -n "$deploy_wait_seconds" ]] \
      || die "backend pre gate requires active writer evidence and bounded deployment wait"
    require_bounded_seconds "$deploy_wait_seconds" 1200 "deployment wait"
    drain_cmd="${PRACTICES_WRITER_DRAIN_COMMAND:-}"
    [[ -x "$drain_cmd" ]] || die "PRACTICES_WRITER_DRAIN_COMMAND must name the approved external executable"
    validate_writer_evidence active "$writer_evidence_file"
    observed_at="$(captured_at)"
    destination="$(evidence_file_for "deploy-backend-pre-$expected_backend_sha")"
    "$jq_bin" -n --arg environment "$environment" --arg sha "$expected_backend_sha" \
      --arg lease "$("$jq_bin" -r '.drainLeaseId' "$writer_evidence_file")" \
      --arg issuer "$("$jq_bin" -r '.issuer' "$writer_evidence_file")" --arg observedAt "$observed_at" '
      {schemaVersion:1,mode:"deploy",phase:"pre",component:"backend",environment:$environment,
       backendSha:$sha,drainLeaseId:$lease,writerDrainIssuer:$issuer,observedAt:$observedAt,passed:true}' \
      >"$tmp/evidence.json"
  elif [[ "$phase" == await ]]; then
    [[ -n "$expected_workflow_path" && -n "$ci_wait_seconds" && -n "$deploy_wait_seconds" ]] \
      || die "backend await requires exact workflow and bounded CI/ECS waits"
    workflow_expected='.github/workflows/deploy.yml'; [[ "$environment" == production ]] && workflow_expected='.github/workflows/deploy-production.yml'
    [[ "$expected_workflow_path" == "$workflow_expected" ]] || die "workflow path does not match the target environment"
    require_bounded_seconds "$ci_wait_seconds" 1800 "CI wait"; require_bounded_seconds "$deploy_wait_seconds" 1200 "deployment wait"
    pre_gate="$(evidence_file_for "deploy-backend-pre-$expected_backend_sha")"; [[ -f "$pre_gate" ]] || die "backend await requires its successful pre gate"
    pre_observed_at="$("$jq_bin" -er '.observedAt|select(type=="string" and length>0)' "$pre_gate")" \
      || die "backend pre gate omitted its completion timestamp"
    wait_for_workflow "$ci_wait_seconds" "$pre_observed_at"
    wait_for_ecs backend "$expected_backend_sha" "$deploy_wait_seconds"
    observed_at="$(captured_at)"
    destination="$(evidence_file_for "deploy-backend-await-$expected_backend_sha")"
    "$jq_bin" -n --arg environment "$environment" --arg sha "$expected_backend_sha" \
      --arg workflow "$expected_workflow_path" --arg runId "$WORKFLOW_RUN_ID" \
      --arg task "$ECS_TASK_DEFINITION" --arg observedAt "$observed_at" '
      {schemaVersion:1,mode:"deploy",phase:"await",component:"backend",environment:$environment,
       backendSha:$sha,workflowPath:$workflow,workflowRunId:$runId,taskDefinitionArn:$task,
       observedAt:$observedAt,passed:true}' >"$tmp/evidence.json"
  elif [[ "$phase" == post ]]; then
    [[ "$require_writer_quiescence" == true && "$require_no_old_task" == true &&
       -n "$writer_evidence_file" && -n "$same_drain_lease_as" &&
       -n "$writer_max_age_seconds" && -n "$writer_min_validity_seconds" ]] \
      || die "backend post gate requires same-lease active evidence and no-old-task proof"
    validate_writer_evidence active "$writer_evidence_file" "$same_drain_lease_as"
    await_gate="$(evidence_file_for "deploy-backend-await-$expected_backend_sha")"; [[ -f "$await_gate" ]] || die "backend post requires its successful await gate"
    "$jq_bin" -e --slurpfile await "$await_gate" '(.capturedAt|fromdateiso8601)>=($await[0].observedAt|fromdateiso8601)' \
      "$writer_evidence_file" >/dev/null || die "writer recapture predates successful await"
    wait_for_ecs backend "$expected_backend_sha" "${PRACTICES_POST_DEPLOY_WAIT_SECONDS:-60}"
    post_state="$(db_read "/* practices:deploy-post */ SELECT CONCAT_WS('|',
      COALESCE(CAST(IS_USED_LOCK('bi_refresh') AS CHAR),''),COALESCE(b.active_refresh_token,''),
      CAST(c.refresh_enabled AS UNSIGNED),CAST(c.contribution_serving_enabled AS UNSIGNED),
      (SELECT COUNT(*) FROM INFORMATION_SCHEMA.PROCESSLIST WHERE INFO LIKE 'CALL sp_nightly_bi_refresh%'))
      FROM bi_refresh_watermark b JOIN practice_contribution_publication_control c ON c.control_id=1
      WHERE b.pipeline_name='FACT_USER_DAY';")"
    IFS='|' read -r lock_owner bi_owner refresh serving active_bi <<<"$post_state"
    [[ -z "$lock_owner" && -z "$bi_owner" && "$refresh" == 0 && "$serving" == 0 && "$active_bi" == 0 ]] \
      || die "backend post-deploy bootstrap/BI-lock conditions failed"
    observed_at="$(captured_at)"
    destination="$(evidence_file_for "deploy-backend-post-$expected_backend_sha")"
    "$jq_bin" -n --arg environment "$environment" --arg sha "$expected_backend_sha" \
      --arg lease "$("$jq_bin" -r '.drainLeaseId' "$writer_evidence_file")" \
      --arg task "$ECS_TASK_DEFINITION" --arg observedAt "$observed_at" '
      {schemaVersion:1,mode:"deploy",phase:"post",component:"backend",environment:$environment,
       backendSha:$sha,drainLeaseId:$lease,taskDefinitionArn:$task,zeroOldTasks:true,
       biLockReleased:true,bootstrapControlsDisabled:true,observedAt:$observedAt,passed:true}' >"$tmp/evidence.json"
  elif [[ "$phase" == release ]]; then
    [[ -n "$writer_release_evidence_file" && -n "$same_drain_lease_as" ]] \
      || die "backend release gate requires release evidence and its post evidence"
    validate_writer_evidence release "$writer_release_evidence_file" "$same_drain_lease_as"
    post_gate="$(evidence_file_for "deploy-backend-post-$expected_backend_sha")"; [[ -f "$post_gate" ]] || die "backend release requires its successful post gate"
    "$jq_bin" -e --slurpfile post "$post_gate" '(.releasedAt|fromdateiso8601)>=($post[0].observedAt|fromdateiso8601)' \
      "$writer_release_evidence_file" >/dev/null || die "writer release predates successful post gate"
    observed_at="$(captured_at)"
    destination="$(evidence_file_for "deploy-backend-release-$expected_backend_sha")"
    "$jq_bin" -n --arg environment "$environment" --arg sha "$expected_backend_sha" \
      --arg lease "$("$jq_bin" -r '.drainLeaseId' "$writer_release_evidence_file")" \
      --arg issuer "$("$jq_bin" -r '.issuer' "$writer_release_evidence_file")" --arg observedAt "$observed_at" '
      {schemaVersion:1,mode:"deploy",phase:"release",component:"backend",environment:$environment,
       backendSha:$sha,drainLeaseId:$lease,writerDrainIssuer:$issuer,
       affectedWritersRestored:true,observedAt:$observedAt,passed:true}' >"$tmp/evidence.json"
  elif [[ "$component" == performance-fixture ]]; then
    wait_for_ecs performance-fixture "$expected_backend_sha" "${PRACTICES_FIXTURE_DEPLOY_WAIT_SECONDS:-60}" \
      "$expected_task_definition" "$expected_image_digest"
    base="${PRACTICES_PERFORMANCE_BASE_URL:-}"; require_https_url "$base" "performance fixture base URL"; base="$(normalize_url "$base")"
    [[ "$base" != "$(backend_base_url)" ]] || die "performance fixture and live backend URLs must differ"
    http_request public GET "$base/system/practices-performance-fixture/identity" "" \
      "$tmp/fixture-identity.json" "$tmp/fixture-headers" 30
    [[ "$HTTP_STATUS" == 200 ]] || die "fixture identity returned HTTP $HTTP_STATUS"
    "$jq_bin" -e --arg sha "$expected_backend_sha" --arg task "$expected_task_definition" \
      --arg digest "$expected_image_digest" '
      .fixtureOnly==true and .backendSha==$sha and .taskDefinitionArn==$task and
      .imageDigest==$digest and (.serviceIdentity|type=="string" and length>0) and
      (.databaseIdentityHash|test("^[a-f0-9]{64}$"))' "$tmp/fixture-identity.json" >/dev/null \
      || die "fixture identity does not match the reviewed deployment"
    observed_at="$(captured_at)"
    destination="$(evidence_file_for "deploy-performance-fixture-$expected_backend_sha")"
    "$jq_bin" -n --arg environment "$environment" --arg sha "$expected_backend_sha" \
      --arg task "$expected_task_definition" --arg digest "$expected_image_digest" \
      --arg service "$("$jq_bin" -r '.serviceIdentity' "$tmp/fixture-identity.json")" \
      --arg database "$("$jq_bin" -r '.databaseIdentityHash' "$tmp/fixture-identity.json")" \
      --arg observedAt "$observed_at" '
      {schemaVersion:1,mode:"deploy",phase:null,component:"performance-fixture",environment:$environment,
       backendSha:$sha,taskDefinitionArn:$task,imageDigest:$digest,serviceIdentity:$service,
       databaseIdentityHash:$database,observedAt:$observedAt,passed:true}' >"$tmp/evidence.json"
  else
    [[ -z "$phase" ]] || die "unexpected deploy phase"
    if [[ "$component" == backend ]]; then
      wait_for_ecs backend "$expected_backend_sha" "${PRACTICES_DEPLOY_CHECK_WAIT_SECONDS:-60}"
      sha="$expected_backend_sha"
    else
      wait_for_ecs frontend "$expected_frontend_sha" "${PRACTICES_DEPLOY_CHECK_WAIT_SECONDS:-60}"
      sha="$expected_frontend_sha"
    fi
    if [[ -n "$observation_seconds" ]]; then
      require_bounded_seconds "$observation_seconds" 1800 "deployment observation"
      remaining="$observation_seconds"
      while ((remaining>0)); do
        step=60; ((remaining<step)) && step="$remaining"
        "$sleep_bin" "$step"; remaining=$((remaining-step))
        ecs_is_exact "$component" "$sha" || die "$component changed during observation"
      done
    fi
    observed_at="$(captured_at)"
    destination="$(evidence_file_for "deploy-$component-$sha")"
    "$jq_bin" -n --arg environment "$environment" --arg component "$component" --arg sha "$sha" \
      --arg task "$ECS_TASK_DEFINITION" --arg observedAt "$observed_at" \
      --arg observation "${observation_seconds:-0}" '
      {schemaVersion:1,mode:"deploy",phase:null,component:$component,environment:$environment,
       expectedSha:$sha,taskDefinitionArn:$task,observationSeconds:($observation|tonumber),
       observedAt:$observedAt,passed:true}' >"$tmp/evidence.json"
  fi
  write_evidence "$tmp/evidence.json" "$destination"
}

require_tool "$jq_bin" jq
require_tool "$clock_bin" clock
case "$mode" in
  schema) run_schema ;;
  preflight) run_preflight ;;
  refresh) run_refresh ;;
  revenue-recovery-refresh) run_revenue_recovery_refresh ;;
  reconcile) run_reconcile ;;
  api) run_api ;;
  publication-enable-build|publication-enable-serving|publication-disable|cost-serving-disable|cost-serving-enable)
    run_publication_control ;;
  recover-stale) run_recover_stale ;;
  deploy) run_deploy ;;
esac
