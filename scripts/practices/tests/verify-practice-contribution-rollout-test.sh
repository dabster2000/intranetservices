#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/../../.." && pwd -P)"
verifier="$root/scripts/practices/verify-practice-contribution-rollout.sh"
reconciliation_sql="$root/scripts/practices/practice-contribution-reconciliation.sql"
performance_profile="$root/scripts/practices/fixtures/practice-revenue-performance-profile-v1.json"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/bin" "$tmp/evidence"

backend_sha='0123456789012345678901234567890123456789'
frontend_sha='abcdefabcdefabcdefabcdefabcdefabcdefabcd'
fingerprint='aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
source_fingerprint='dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd'
generation='11111111-1111-4111-8111-111111111111'
basis='22222222-2222-4222-8222-222222222222'
old_generation='33333333-3333-4333-8333-333333333333'
cost_at='2026-07-16T00:00:00.000000Z'
old_cost_at='2026-07-15T00:00:00.000000Z'
task_definition='arn:aws:ecs:eu-west-1:111111111111:task-definition/practices:7'
image_digest='sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee'
requested_by='44444444-4444-4444-8444-444444444444'
system_token='stub.system.token'
dashboard_token='stub.dashboard.token'

printf '%s\n' "$requested_by" >"$tmp/requested-by"
printf '%s\n' "$system_token" >"$tmp/system-token"
printf '%s\n' "$dashboard_token" >"$tmp/dashboard-token"
printf '%s\n' '# Netscape HTTP Cookie File' >"$tmp/admin-cookie"
printf '%s\n' '# Netscape HTTP Cookie File' >"$tmp/nonadmin-cookie"
chmod 600 "$tmp/requested-by" "$tmp/system-token" "$tmp/dashboard-token" \
  "$tmp/admin-cookie" "$tmp/nonadmin-cookie"

jq -n '{scope:"CONSOLIDATED",revenueBasis:"NET_ATTRIBUTED",costSource:"BOOKED",
  responseStatus:"UNAVAILABLE_COST",responseReason:"COST_EVIDENCE_UNAVAILABLE",reportingThroughMonth:"2026-06",
  currentPeriod:null,priorPeriod:null,revenueGenerationId:"generation",revenuePublishedAt:null,
  revenueSourceRefreshedAt:null,fullBiRefreshVersion:"1",sourceWatermarkVersions:{INVOICE_DOCUMENT:"1",
    FINANCE_GL:"1",CURRENCY:"1",ACCOUNT_CLASSIFICATION:"1",INVOICE_ATTRIBUTION:"1",SELF_BILLED:"1",
    PHANTOM_ATTRIBUTION:"1",DELIVERY_EVIDENCE:"1",PRACTICE_BASIS_INPUT:"1"},
  pairedCostGenerationAt:null,costPublishedAt:null,practiceBasisGenerationId:"basis",
  revenueAttributionMethod:"DELIVERY_EVIDENCE",costAttributionMethod:"SIGNED_OPERATING_COST",
  revenueHistoryCoverageStart:null,costHistoryCoverageStart:null,practiceBasesAligned:true,
  practiceBasesAlignmentReason:null,currentPortfolio:null,priorPortfolio:null,
  practices:[{practiceId:"PM",label:"Project Management",current:null,prior:null,revenueDeltaDkk:null,
    revenueDeltaPct:null,costDeltaDkk:null,costDeltaPct:null,contributionDeltaDkk:null,
    contributionDeltaPct:null,contributionMarginDeltaPoints:null}],
  revenueOnlySegments:[{segmentId:"JK",label:"JK",current:null,prior:null,revenueDeltaDkk:null,
    revenueDeltaPct:null}]}' >"$tmp/contribution-response.json"

cat >"$tmp/bin/clock" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
case "$*" in
  *%s*) printf '%s\n' "${STUB_CLOCK_EPOCH:-1784160000}" ;;
  *) printf '%s\n' "${STUB_CLOCK_ISO:-2026-07-16T00:00:00Z}" ;;
esac
STUB

cat >"$tmp/bin/sleep" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
printf 'sleep %s\n' "$*" >>"$STUB_LOG"
STUB

cat >"$tmp/bin/aws" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
printf 'aws' >>"$STUB_LOG"; printf ' %q' "$@" >>"$STUB_LOG"; printf '\n' >>"$STUB_LOG"
case "$1 $2" in
  'ecs describe-services')
    jq -n --arg task "$STUB_TASK" '{failures:[],services:[{desiredCount:1,runningCount:1,pendingCount:0,
      taskDefinition:$task,deployments:[{status:"PRIMARY",rolloutState:"COMPLETED"}]}]}' ;;
  'ecs list-tasks') printf '%s\n' '{"taskArns":["arn:aws:ecs:task/one"]}' ;;
  'ecs describe-tasks')
    jq -n --arg task "$STUB_TASK" --arg digest "$STUB_DIGEST" \
      '{failures:[],tasks:[{lastStatus:"RUNNING",taskDefinitionArn:$task,
        containers:[{name:"application",imageDigest:$digest}]}]}' ;;
  'ecs describe-task-definition')
    jq -n --arg sha "$STUB_ECS_SHA" \
      '{tags:[{key:"git-sha",value:$sha}],taskDefinition:{containerDefinitions:[
        {name:"application",image:"repository/application:reviewed",environment:[]}]}}' ;;
  *) exit 31 ;;
esac
STUB

cat >"$tmp/bin/gh" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
printf 'gh' >>"$STUB_LOG"; printf ' %q' "$@" >>"$STUB_LOG"; printf '\n' >>"$STUB_LOG"
jq -n --arg sha "$STUB_ECS_SHA" '[{databaseId:77,headSha:$sha,status:"completed",
  conclusion:"success",workflowName:"Deploy",
  createdAt:(env.STUB_WORKFLOW_CREATED_AT // "2026-07-16T00:00:00Z")}]'
STUB

cat >"$tmp/bin/curl" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
printf 'curl' >>"$STUB_LOG"; printf ' %q' "$@" >>"$STUB_LOG"; printf '\n' >>"$STUB_LOG"
url='' output='' headers='' data='' auth='' status=200
while (($#)); do
  case "$1" in
    --url) url="$2"; shift 2 ;;
    --output) output="$2"; shift 2 ;;
    --dump-header) headers="$2"; shift 2 ;;
    --data-binary) data="$2"; shift 2 ;;
    --config) auth="$2"; shift 2 ;;
    --cookie) auth="$2"; shift 2 ;;
    --connect-timeout|--max-time|--request|--header|--write-out) shift 2 ;;
    --silent|--show-error) shift ;;
    *) shift ;;
  esac
done
printf '%s\n' 'HTTP/2 200' 'Content-Type: application/json' 'Cache-Control: private, no-store' >"$headers"
case "$url" in
  */practice-cost-basis-refresh/start)
    touch "$STUB_COST_STARTED"; printf '%s\n' '{"action":"practice-cost-basis-refresh","executionId":"1"}' >"$output" ;;
  */practice-revenue-refresh/start)
    [[ -e "$STUB_COST_STARTED" ]] || exit 32
    touch "$STUB_REVENUE_STARTED"; printf '%s\n' '{"action":"practice-revenue-refresh","executionId":"2"}' >"$output" ;;
  */practice-revenue-publication/enable-build)
    printf '%s\n' '{"action":"ENABLE_BUILD"}' >"$output" ;;
  */practice-revenue-publication/disable)
    printf '%s\n' '{"action":"DISABLE_CONTRIBUTION"}' >"$output" ;;
  */identity)
    jq -n --arg sha "$STUB_ECS_SHA" --arg task "$STUB_TASK" --arg digest "$STUB_DIGEST" \
      '{fixtureOnly:true,backendSha:$sha,taskDefinitionArn:$task,imageDigest:$digest,
        serviceIdentity:"fixture-service",databaseIdentityHash:"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}' >"$output" ;;
  */practices/cxo/contribution*)
    if [[ "$url" == *'costSource=booked'* || "$url" == *'costSource=UNKNOWN'* ||
          "$url" == *'costSource=BOOKED&costSource=BOOKED'* || "$url" == *'costSource=' ]]; then
      status=400; printf '%s\n' '{"code":"BAD_REQUEST"}' >"$output"
    elif [[ -n "$auth" ]] && grep -Fq 'stub.system.token' "$auth"; then
      status=403; printf '%s\n' '{"code":"FORBIDDEN"}' >"$output"
    else
      cp "$STUB_CONTRIBUTION_RESPONSE" "$output"
    fi ;;
  *) printf '%s\n' '{}' >"$output" ;;
esac
printf '%s' "$status"
STUB

cat >"$tmp/bin/mariadb" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
sql=''
while (($#)); do
  case "$1" in
    --execute) sql="$2"; shift 2 ;;
    *) shift ;;
  esac
done
if [[ -z "$sql" ]]; then sql="$(cat)"; fi
printf 'mariadb %q\n' "$sql" >>"$STUB_LOG"
case "$sql" in
  *'CALL sp_nightly_bi_refresh(3,24)'*)
    if [[ "${STUB_HANG_FULL_BI:-}" == true ]]; then sleep 5; else touch "$STUB_BI_STARTED"; fi ;;
  *'practices:schema-controls'*) printf '%s\n' '0|0|1|||READY|9|0' ;;
  *'practices:schema '*)
    printf '411\tAlign practice cost to effective history\t111\t1\n'
    printf '412\tCreate practice revenue publication\t222\t1\n'
    printf '413\tRepair practice contribution delivery lifecycle\t333\t1\n' ;;
  *'practices:preflight'*) printf '%s\n' '9|9|0|0|0|0|0' ;;
  *'practices:latest-request'*)
    printf '2|%s|%s|PENDING|||||\n' "$STUB_FINGERPRINT" "$STUB_FINGERPRINT" ;;
  *'practices:request-snapshot'*)
    if [[ -e "$STUB_COST_STARTED" ]]; then
      printf '2|%s|%s|READY||%s||%s|\n' "$STUB_FINGERPRINT" "$STUB_FINGERPRINT" "$STUB_COST_AT" "$STUB_BASIS"
    else
      printf '2|%s|%s|PENDING|||||\n' "$STUB_FINGERPRINT" "$STUB_FINGERPRINT"
    fi ;;
  *'practices:state-snapshot'*)
    if [[ ! -e "$STUB_BI_STARTED" ]]; then
      printf '1|READY||2026-07-15|%s|READY||1|1|%s||READY||%s|1|0|1||\n' \
        "$STUB_OLD_COST_AT" "$STUB_OLD_GENERATION" "$STUB_OLD_COST_AT"
    elif [[ -e "$STUB_REVENUE_STARTED" ]]; then
      printf '2|READY||2026-07-15|%s|READY||2|2|%s|%s|READY||%s|1|0|1||\n' \
        "$STUB_COST_AT" "$STUB_GENERATION" "$STUB_OLD_GENERATION" "$STUB_COST_AT"
    elif [[ -e "$STUB_COST_STARTED" ]]; then
      printf '2|READY||2026-07-15|%s|READY||2|2|%s||READY||%s|1|0|1||\n' \
        "$STUB_COST_AT" "$STUB_OLD_GENERATION" "$STUB_OLD_COST_AT"
    else
      printf '2|READY||2026-07-15|%s|READY||2|1|%s||READY||%s|1|0|1||\n' \
        "$STUB_OLD_COST_AT" "$STUB_OLD_GENERATION" "$STUB_OLD_COST_AT"
    fi ;;
  *'practices:source-vector'*) printf '%s\n' "$STUB_SOURCE_FINGERPRINT" ;;
  *'practices:cost-reconcile'*)
    window_date="${STUB_WINDOW_DATE:-2025-07-01}"
    printf 'READY||%s|%s|2|2|READY|%s|%s|1||%s|2026-06-01|2024-07-01|2025-06-01|1||2025-07-01|2026-06-01|2024-07-01|2025-06-01|1|20|0\n' \
      "$STUB_COST_AT" "$STUB_BASIS" "$STUB_FINGERPRINT" "$STUB_FINGERPRINT" "$window_date" ;;
  *'practices:revenue-reconcile'*)
    printf '%s|%s|%s|READY|100.00|100.00|0.00||0|0\n' \
      "$STUB_GENERATION" "$STUB_COST_AT" "$STUB_BASIS" ;;
  *'practices:deploy-post'*) printf '%s\n' '||0|0|0' ;;
  *'WITH item_controls AS'*)
    if [[ "${STUB_RECONCILIATION_MODE:-}" == invalid-document ]]; then
      printf '%s\tCURRENT\t50.00\t50.00\t0.00\t1\t1\t1\t1\t1\t0\t0\t0\n' "$STUB_GENERATION"
      printf '%s\tPRIOR\t50.00\t50.00\t0.00\t1\t0\t1\t1\t1\t0\t0\t0\n' "$STUB_GENERATION"
    else
      printf '%s\tCURRENT\t50.00\t50.00\t0.00\t1\t0\t1\t1\t1\t0\t0\t0\n' "$STUB_GENERATION"
      printf '%s\tPRIOR\t50.00\t50.00\t0.00\t1\t0\t1\t1\t1\t0\t0\t0\n' "$STUB_GENERATION"
    fi
    printf 'PRACTICE_CONTRIBUTION\tREADY\t%s\t%s\t%s\t100.00\t100.00\t0.00\t0\t1\tREADY\t%s\t%s\t2\t2\n' \
      "$STUB_GENERATION" "$STUB_COST_AT" "$STUB_BASIS" "$STUB_COST_AT" "$STUB_BASIS" ;;
  *) printf '%s\n' "unhandled SQL: $sql" >&2; exit 33 ;;
esac
STUB

cat >"$tmp/bin/drain" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
printf 'DRAIN EXECUTED\n' >>"$STUB_LOG"
exit 34
STUB

chmod +x "$tmp/bin/"*

export STUB_LOG="$tmp/tools.log"
export STUB_BI_STARTED="$tmp/bi-started"
export STUB_COST_STARTED="$tmp/cost-started"
export STUB_REVENUE_STARTED="$tmp/revenue-started"
export STUB_ECS_SHA="$backend_sha"
export STUB_TASK="$task_definition"
export STUB_DIGEST="$image_digest"
export STUB_FINGERPRINT="$fingerprint"
export STUB_SOURCE_FINGERPRINT="$source_fingerprint"
export STUB_GENERATION="$generation"
export STUB_OLD_GENERATION="$old_generation"
export STUB_BASIS="$basis"
export STUB_COST_AT="$cost_at"
export STUB_OLD_COST_AT="$old_cost_at"
export STUB_CONTRIBUTION_RESPONSE="$tmp/contribution-response.json"
export PRACTICES_CURL_BIN="$tmp/bin/curl"
export PRACTICES_MARIADB_BIN="$tmp/bin/mariadb"
export PRACTICES_GH_BIN="$tmp/bin/gh"
export PRACTICES_AWS_BIN="$tmp/bin/aws"
export PRACTICES_CLOCK_BIN="$tmp/bin/clock"
export PRACTICES_SLEEP_BIN="$tmp/bin/sleep"
export PRACTICES_POLL_INTERVAL_SECONDS=1
export PRACTICES_TARGET_ENV=staging
export PRACTICES_BACKEND_BASE_URL=https://backend.invalid
export PRACTICES_FRONTEND_BASE_URL=https://frontend.invalid
export PRACTICES_DB_LOGIN_PATH=read-only-db
export PRACTICES_BI_DB_LOGIN_PATH=bi-writer-db
export PRACTICES_SYSTEM_TOKEN_FILE="$tmp/system-token"
export PRACTICES_DASHBOARD_TOKEN_FILE="$tmp/dashboard-token"
export PRACTICES_REQUESTED_BY_FILE="$tmp/requested-by"
export PRACTICES_ADMIN_COOKIE_FILE="$tmp/admin-cookie"
export PRACTICES_NONADMIN_COOKIE_FILE="$tmp/nonadmin-cookie"
export PRACTICES_EVIDENCE_DIR="$tmp/evidence"
export PRACTICES_BACKEND_ECS_CLUSTER=backend-cluster
export PRACTICES_BACKEND_ECS_SERVICE=backend-service
export PRACTICES_FRONTEND_ECS_CLUSTER=frontend-cluster
export PRACTICES_FRONTEND_ECS_SERVICE=frontend-service
export PRACTICES_PERFORMANCE_ECS_CLUSTER=fixture-cluster
export PRACTICES_PERFORMANCE_ECS_SERVICE=fixture-service
export PRACTICES_PERFORMANCE_BASE_URL=https://fixture.invalid
export PRACTICES_WRITER_DRAIN_COMMAND="$tmp/bin/drain"
export PRACTICES_PREFLIGHT_WAIT_SECONDS=2
export PRACTICES_DEPLOY_CHECK_WAIT_SECONDS=2
export PRACTICES_FIXTURE_DEPLOY_WAIT_SECONDS=2
export PRACTICES_POST_DEPLOY_WAIT_SECONDS=2

expect_failure() {
  local label="$1"; shift
  if "$@" >"$tmp/stdout" 2>"$tmp/stderr"; then
    printf '%s\n' "$label unexpectedly passed" >&2
    exit 1
  fi
}

expect_failure 'ambiguous aggregate mode' env PRACTICES_TARGET_ENV=staging "$verifier" aggregate
expect_failure 'unknown environment' env PRACTICES_TARGET_ENV=unknown "$verifier" schema --expected-migration 411
expect_failure 'publication mutation without confirmation' "$verifier" publication-enable-build
expect_failure 'confirmation flag given a value' "$verifier" publication-enable-build --confirm-publication-control true
expect_failure 'unknown argument' "$verifier" preflight --expected-backend-sha "$backend_sha" --job-name anything
expect_failure 'production fixture target' env PRACTICES_TARGET_ENV=production "$verifier" deploy \
  --component performance-fixture --expected-backend-sha "$backend_sha" \
  --expected-task-definition "$task_definition" --expected-image-digest "$image_digest"
expect_failure 'incomplete revenue recovery vector' "$verifier" revenue-recovery-refresh \
  --expected-cost-generation-at "$cost_at" --revenue-wait-seconds 2
expect_failure 'single cost source' "$verifier" reconcile --scope cost-only --cost-source BOOKED
expect_failure 'repeated cost source' "$verifier" reconcile --scope cost-only \
  --cost-source BOOKED --cost-source BOOKED

: >"$STUB_LOG"
"$verifier" publication-enable-build --confirm-publication-control >"$tmp/publication-output"
jq -e '.mode=="publication-enable-build" and .action=="ENABLE_BUILD" and .passed==true' \
  "$tmp/evidence/publication-enable-build.json" >/dev/null
grep -q '/system/batch/practice-revenue-publication/enable-build' "$STUB_LOG"
if grep -Fq "$system_token" "$STUB_LOG"; then
  printf '%s\n' 'system token leaked into tool arguments' >&2; exit 1
fi

: >"$STUB_LOG"
"$verifier" schema --expected-migration 411 --expected-migration 412 \
  --expected-migration 413 \
  --expect-refresh-enabled false --expect-contribution-serving-enabled false \
  --expect-legacy-cost-serving-enabled true --expect-bi-refresh-lock-released >/dev/null
jq -e '.migrations|map(.version)|index("411")!=null and index("412")!=null and index("413")!=null' \
  "$tmp/evidence/schema.json" >/dev/null
if grep -Eiq 'mariadb .*\b(INSERT|UPDATE|DELETE|REPLACE|ALTER|DROP|TRUNCATE|CREATE|CALL)\b' "$STUB_LOG"; then
  printf '%s\n' 'schema mode issued mutating SQL' >&2; exit 1
fi

: >"$STUB_LOG"
rm -f "$STUB_BI_STARTED" "$STUB_COST_STARTED" "$STUB_REVENUE_STARTED"
"$verifier" refresh --expected-backend-sha "$backend_sha" --full-bi-mode invoke \
  --full-bi-wait-seconds 2 --cost-basis-wait-seconds 2 --revenue-wait-seconds 2 >/dev/null
jq -e --arg generation "$generation" --arg cost "$cost_at" '
  .sequence==["FULL_BI","COST_BASIS","REVENUE"] and
  .revenueGenerationId==$generation and .costGenerationAt==$cost and .passed==true' \
  "$tmp/evidence/refresh.json" >/dev/null
bi_line="$(grep -n 'sp_nightly_bi_refresh' "$STUB_LOG" | head -1 | cut -d: -f1)"
cost_line="$(grep -n 'practice-cost-basis-refresh/start' "$STUB_LOG" | head -1 | cut -d: -f1)"
revenue_line="$(grep -n 'practice-revenue-refresh/start' "$STUB_LOG" | head -1 | cut -d: -f1)"
((bi_line < cost_line && cost_line < revenue_line))

: >"$STUB_LOG"
"$verifier" reconcile --scope full --expected-cost-generation-at "$cost_at" \
  --cost-source BOOKED --cost-source BOOKED_PLUS_DRAFT >/dev/null
jq -e --arg generation "$generation" --arg cost "$cost_at" '
  .scope=="full" and .revenueGenerationId==$generation and
  .pairedCostGenerationAt==$cost and .aggregateOnly==true and
  .consultantRevenueExposed==false and .passed==true' "$tmp/evidence/reconcile.json" >/dev/null
if grep -Eiq 'mariadb .*\b(INSERT|UPDATE|DELETE|REPLACE|ALTER|DROP|TRUNCATE|CREATE|CALL)\b' "$STUB_LOG"; then
  printf '%s\n' 'reconcile mode issued mutating SQL' >&2; exit 1
fi
export STUB_RECONCILIATION_MODE=invalid-document
expect_failure 'invalid GL document evidence despite matching totals' "$verifier" reconcile \
  --scope full --expected-cost-generation-at "$cost_at" \
  --cost-source BOOKED --cost-source BOOKED_PLUS_DRAFT
unset STUB_RECONCILIATION_MODE

saved_generation="$STUB_GENERATION"
export STUB_GENERATION="00000000-0000-4000-8000-000000000000';DROP TABLE invoices;--"
expect_failure 'non-UUID reconciliation substitution' "$verifier" reconcile --scope full \
  --expected-cost-generation-at "$cost_at" --cost-source BOOKED --cost-source BOOKED_PLUS_DRAFT
export STUB_GENERATION="$saved_generation"
export STUB_WINDOW_DATE='2025-07-01;DROP TABLE invoices'
expect_failure 'non-date reconciliation substitution' "$verifier" reconcile --scope full \
  --expected-cost-generation-at "$cost_at" --cost-source BOOKED --cost-source BOOKED_PLUS_DRAFT
unset STUB_WINDOW_DATE

grep -Fq 'MAX(i.document_control_dkk) AS document_gl_revenue_dkk' "$reconciliation_sql"
grep -Fq 'SUM(a.allocated_revenue_dkk) - SUM(d.document_gl_revenue_dkk) AS difference_dkk' \
  "$reconciliation_sql"
grep -Fq 'invalid_gl_document_count' "$reconciliation_sql"
grep -Fq 'INTO[[:space:]]+(OUTFILE|DUMPFILE)' "$verifier"
if grep -Fq "THEN i.item_control_dkk ELSE 0 END) AS confirmed_gl_control_dkk" \
    "$reconciliation_sql"; then
  printf '%s\n' 'reconciliation repeats the document control at item grain' >&2; exit 1
fi

writer_categories='["INVOICE_DOCUMENT_ITEM_PRICING","INVOICE_ATTRIBUTION","FINANCE_GL_AND_MULTI_TRANSACTION_IMPORTS","CURRENCY_ACCOUNT_CLASSIFICATION","SELF_BILLED","PHANTOM_ATTRIBUTION","WORK_CONTRACT_DELIVERY_ROUTING","PRACTICE_STATUS_HISTORY","BI_FULL_INCREMENTAL_EVENTS","PRACTICE_COST_PUBLICATION_JOBS","PRACTICE_DIRTY_RETENTION_JOBS"]'
jq -n --arg environment staging --arg sha "$backend_sha" --argjson categories "$writer_categories" '
  {schemaVersion:1,environment:$environment,targetBackendSha:$sha,
   capturedAt:"2026-07-15T23:59:30Z",validUntil:"2026-07-16T01:00:00Z",active:true,
   affectedWritersDrained:true,writerCategories:$categories,drainLeaseId:"lease-1",
   issuer:"approved-operations",oldTaskCountAtCapture:2}' >"$tmp/writer-pre.json"
chmod 600 "$tmp/writer-pre.json"
: >"$STUB_LOG"
"$verifier" deploy --phase pre --component backend --expected-backend-sha "$backend_sha" \
  --require-writer-quiescence --writer-quiescence-evidence-file "$tmp/writer-pre.json" \
  --writer-quiescence-max-age-seconds 300 --writer-quiescence-min-validity-seconds 3300 \
  --deploy-wait-seconds 1200 >/dev/null
jq -e '.phase=="pre" and .drainLeaseId=="lease-1" and .passed==true' \
  "$tmp/evidence/deploy-backend-pre-$backend_sha.json" >/dev/null
if grep -q 'DRAIN EXECUTED' "$STUB_LOG"; then
  printf '%s\n' 'verifier invoked the external drain executable' >&2; exit 1
fi

export STUB_CLOCK_ISO='2026-07-16T00:10:00Z'
export STUB_CLOCK_EPOCH=1784160600
export STUB_WORKFLOW_CREATED_AT='2026-07-15T23:59:00Z'
expect_failure 'stale successful workflow for same SHA' "$verifier" deploy --phase await \
  --component backend --expected-backend-sha "$backend_sha" \
  --expected-workflow-path .github/workflows/deploy.yml --ci-wait-seconds 1 \
  --deploy-wait-seconds 2
unset STUB_WORKFLOW_CREATED_AT
"$verifier" deploy --phase await --component backend --expected-backend-sha "$backend_sha" \
  --expected-workflow-path .github/workflows/deploy.yml --ci-wait-seconds 2 \
  --deploy-wait-seconds 2 >/dev/null
await_evidence="$tmp/evidence/deploy-backend-await-$backend_sha.json"
jq -e '.phase=="await" and .observedAt=="2026-07-16T00:10:00Z" and .passed==true' \
  "$await_evidence" >/dev/null

jq -n --arg environment staging --arg sha "$backend_sha" --argjson categories "$writer_categories" '
  {schemaVersion:1,environment:$environment,targetBackendSha:$sha,
   capturedAt:"2026-07-16T00:05:00Z",validUntil:"2026-07-16T02:00:00Z",active:true,
   affectedWritersDrained:true,writerCategories:$categories,drainLeaseId:"lease-1",
   issuer:"approved-operations",oldTaskCountAtCapture:0}' >"$tmp/writer-between-await.json"
chmod 600 "$tmp/writer-between-await.json"
expect_failure 'writer recapture before await completion' "$verifier" deploy --phase post \
  --component backend --expected-backend-sha "$backend_sha" --require-writer-quiescence \
  --require-no-old-task --writer-quiescence-evidence-file "$tmp/writer-between-await.json" \
  --same-drain-lease-as "$tmp/writer-pre.json" --writer-quiescence-max-age-seconds 300 \
  --writer-quiescence-min-validity-seconds 3300

jq -n --arg environment staging --arg sha "$backend_sha" --argjson categories "$writer_categories" '
  {schemaVersion:1,environment:$environment,targetBackendSha:$sha,
   capturedAt:"2026-07-16T00:11:00Z",validUntil:"2026-07-16T02:30:00Z",active:true,
   affectedWritersDrained:true,writerCategories:$categories,drainLeaseId:"lease-1",
   issuer:"approved-operations",oldTaskCountAtCapture:0}' >"$tmp/writer-post.json"
chmod 600 "$tmp/writer-post.json"
export STUB_CLOCK_ISO='2026-07-16T00:12:00Z'
export STUB_CLOCK_EPOCH=1784160720
"$verifier" deploy --phase post --component backend --expected-backend-sha "$backend_sha" \
  --require-writer-quiescence --require-no-old-task \
  --writer-quiescence-evidence-file "$tmp/writer-post.json" \
  --same-drain-lease-as "$tmp/writer-pre.json" --writer-quiescence-max-age-seconds 300 \
  --writer-quiescence-min-validity-seconds 3300 >/dev/null
post_evidence="$tmp/evidence/deploy-backend-post-$backend_sha.json"
jq -e '.phase=="post" and .observedAt=="2026-07-16T00:12:00Z" and .passed==true' \
  "$post_evidence" >/dev/null

jq -n --arg environment staging --arg sha "$backend_sha" --argjson categories "$writer_categories" '
  {schemaVersion:1,environment:$environment,targetBackendSha:$sha,
   releasedAt:"2026-07-16T00:11:30Z",active:false,affectedWritersRestored:true,
   writerCategories:$categories,drainLeaseId:"lease-1",issuer:"approved-operations"}' \
  >"$tmp/writer-release-before-post-completion.json"
chmod 600 "$tmp/writer-release-before-post-completion.json"
export STUB_CLOCK_ISO='2026-07-16T00:13:00Z'
export STUB_CLOCK_EPOCH=1784160780
expect_failure 'writer release before post completion' "$verifier" deploy --phase release \
  --component backend --expected-backend-sha "$backend_sha" \
  --writer-quiescence-release-evidence-file "$tmp/writer-release-before-post-completion.json" \
  --same-drain-lease-as "$tmp/writer-post.json"

"$verifier" deploy --component performance-fixture --expected-backend-sha "$backend_sha" \
  --expected-task-definition "$task_definition" --expected-image-digest "$image_digest" >/dev/null
fixture_deploy="$tmp/evidence/deploy-performance-fixture-$backend_sha.json"
jq -e '.serviceIdentity=="fixture-service" and
  .databaseIdentityHash=="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"' \
  "$fixture_deploy" >/dev/null

profile_sha="$(shasum -a 256 "$performance_profile" | awk '{print $1}')"
jq -n --slurpfile p "$performance_profile" --arg sha "$backend_sha" --arg profileSha "$profile_sha" \
  --arg task "$task_definition" --arg digest "$image_digest" '
  {schemaVersion:1,evidenceId:"aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa",environment:"staging",
   purpose:"INITIAL_ROLLOUT",targetBackendSha:$sha,recoveryClass:null,
   capturedAt:"2026-07-16T00:13:00Z",productionShapedFixture:true,fixtureProfileVersion:1,
   fixtureProfileSha256:$profileSha,fixtureSeedFingerprint:$p[0].expectedSeedFingerprint,
   fixtureSeedCounts:{completedInvoiceMonths:60,companyCount:3,consultants:$p[0].consultants,
     documents:$p[0].documents,itemControls:$p[0].itemControls,allocationRowCount:150000,
     dependencyRowCount:250000,canonicalDeliveryRowCount:450000,datedCapacityRowCount:420000,
     employeeMonthSalaryRowCount:19200,companyMonthControls:$p[0].companyMonthControls},
   valuationDistribution:$p[0].valuationDistribution,
   attributionDistribution:$p[0].attributionDistribution,segmentDistribution:$p[0].segmentDistribution,
   fixtureServiceIdentity:"fixture-service",
   fixtureDatabaseIdentityHash:"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
   fixtureTaskDefinitionArn:$task,fixtureImageDigest:$digest,latencyEndpoint:"POST /work",
   latencyClientSettings:{keepAlive:true,concurrency:1},baselineWarmupCount:250,
   candidateWarmupCount:250,baselineSampleCount:2000,candidateLatencySampleCount:4800,
   totalWriteCount:10000,writeDistribution:$p[0].workload.writeDistribution,writeRatePerSecond:1.1,
   writeBurst:1,percentileMethod:"NEAREST_RANK_INTEGER_MICROSECONDS",phaseOverlap:true,
   costBasisRunCount:3,revenueRunCount:3,maxCostBasisSeconds:1440,maxRevenueSeconds:1440,
   costBasisRunDurationsSeconds:[100,101,102],revenueRunDurationsSeconds:[200,201,202],
   maxP95AddedMs:5,maxP95AddedPercent:10,maxP99LockMs:10,maxLockReleaseSeconds:5,
   observedBaselineP95Micros:1000,observedCandidateP95Micros:1050,observedP95AddedMs:0.05,
   observedP95AddedPercent:5,observedP99LockMs:2,observedMaxLockReleaseSeconds:1,
   deadlockCount:0,currentMonthEndpointStayedAvailable:true,historicalChangeRepublished:true,
   fixtureCleanupPassed:true,passed:true}' >"$tmp/performance-evidence.json"
chmod 600 "$tmp/performance-evidence.json"
performance_evidence_dir="$tmp/evidence-performance"
mkdir -p "$performance_evidence_dir"
cp "$fixture_deploy" "$performance_evidence_dir/$(basename "$fixture_deploy")"
chmod 600 "$performance_evidence_dir/$(basename "$fixture_deploy")"
performance_args=(reconcile --scope full --expected-cost-generation-at "$cost_at"
  --cost-source BOOKED --cost-source BOOKED_PLUS_DRAFT --allow-structured-live-cost-gaps
  --require-performance-evidence "$tmp/performance-evidence.json"
  --performance-purpose INITIAL_ROLLOUT --performance-target-backend-sha "$backend_sha"
  --performance-fixture-profile "$performance_profile"
  --performance-fixture-task-definition "$task_definition"
  --performance-fixture-image-digest "$image_digest" --performance-max-age-seconds 21600)
env PRACTICES_EVIDENCE_DIR="$performance_evidence_dir" "$verifier" "${performance_args[@]}" >/dev/null
jq -e '.passed==true and .scope=="full"' "$performance_evidence_dir/reconcile.json" >/dev/null

for mismatch in service database extra; do
  case "$mismatch" in
    service) jq '.fixtureServiceIdentity="different-fixture"' "$tmp/performance-evidence.json" >"$tmp/performance-$mismatch.json" ;;
    database) jq '.fixtureDatabaseIdentityHash="cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"' "$tmp/performance-evidence.json" >"$tmp/performance-$mismatch.json" ;;
    extra) jq '.unexpectedField=true' "$tmp/performance-evidence.json" >"$tmp/performance-$mismatch.json" ;;
  esac
  chmod 600 "$tmp/performance-$mismatch.json"
  bad_args=("${performance_args[@]}")
  for ((i=0; i<${#bad_args[@]}; i++)); do
    [[ "${bad_args[i]}" == --require-performance-evidence ]] && bad_args[i+1]="$tmp/performance-$mismatch.json"
  done
  expect_failure "performance $mismatch binding" env PRACTICES_EVIDENCE_DIR="$performance_evidence_dir" \
    "$verifier" "${bad_args[@]}"
done

invalid_numeric_fields=(
  observedBaselineP95Micros observedCandidateP95Micros observedP95AddedMs
  observedP95AddedPercent observedP99LockMs observedMaxLockReleaseSeconds
)
for ((i=0; i<${#invalid_numeric_fields[@]}; i++)); do
  field="${invalid_numeric_fields[i]}"
  invalid_value=null
  ((i % 2 == 1)) && invalid_value=false
  jq --arg field "$field" --argjson value "$invalid_value" '.[$field]=$value' \
    "$tmp/performance-evidence.json" >"$tmp/performance-$field.json"
  chmod 600 "$tmp/performance-$field.json"
  bad_args=("${performance_args[@]}")
  for ((j=0; j<${#bad_args[@]}; j++)); do
    [[ "${bad_args[j]}" == --require-performance-evidence ]] \
      && bad_args[j+1]="$tmp/performance-$field.json"
  done
  expect_failure "performance $field rejects $invalid_value" \
    env PRACTICES_EVIDENCE_DIR="$performance_evidence_dir" "$verifier" "${bad_args[@]}"
done

"$verifier" api --surface backend --expected-backend-sha "$backend_sha" >/dev/null
jq -e '.surface=="backend" and .aggregateBoundaryPassed==true and .passed==true' \
  "$tmp/evidence/api-backend.json" >/dev/null
jq '. + {consultantName:"leaked person",invoiceDescription:"raw invoice"}' \
  "$tmp/contribution-response.json" >"$tmp/contribution-response-malicious.json"
export STUB_CONTRIBUTION_RESPONSE="$tmp/contribution-response-malicious.json"
expect_failure 'unexpected consultant and invoice fields' "$verifier" api \
  --surface backend --expected-backend-sha "$backend_sha"
export STUB_CONTRIBUTION_RESPONSE="$tmp/contribution-response.json"

rm -f "$STUB_BI_STARTED" "$STUB_COST_STARTED" "$STUB_REVENUE_STARTED"
deadline_started=$SECONDS
expect_failure 'hanging full BI invocation' env STUB_HANG_FULL_BI=true "$verifier" refresh \
  --expected-backend-sha "$backend_sha" --full-bi-mode invoke --full-bi-wait-seconds 1 \
  --cost-basis-wait-seconds 2 --revenue-wait-seconds 2
deadline_elapsed=$((SECONDS - deadline_started))
((deadline_elapsed < 4)) || { printf '%s\n' 'full BI deadline was not enforced' >&2; exit 1; }

: >"$STUB_LOG"
"$verifier" preflight --expected-backend-sha "$backend_sha" >/dev/null
jq -e '.backendSha=="0123456789012345678901234567890123456789" and .sourceReadyCount==9' \
  "$tmp/evidence/preflight.json" >/dev/null
grep -q 'aws ecs describe-services' "$STUB_LOG"

if grep -En '"\$driver"|^[[:space:]]*(eval|source)[[:space:]]' "$verifier" >/dev/null; then
  printf '%s\n' 'rollout verifier still delegates or dynamically executes operator input' >&2; exit 1
fi
if grep -Fq "$system_token" "$tmp/evidence/refresh.json" || \
   grep -Fq "$requested_by" "$tmp/evidence/refresh.json"; then
  printf '%s\n' 'secret or requested-user identifier leaked into evidence' >&2; exit 1
fi

printf '%s\n' 'rollout verifier direct-orchestration contract tests passed'
