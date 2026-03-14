#!/usr/bin/env bash
# ==============================================================================
# scope-enforcement-tests.sh
#
# Comprehensive security test suite for API Client Scope Enforcement.
#
# Tests that a client with ONLY [expenses:read, users:read, expenses:write]:
#   A) Can authenticate and get correct JWT claims
#   B) Can access endpoints its scopes permit
#   C) Gets 403 on ALL endpoints requiring other scopes
#   D) Does NOT receive leaked data (salaries, bank info, CPR, password, etc.)
#   E) Cannot bypass auth with tampered/missing tokens
#
# Prerequisites:
#   - curl, jq
#   - Quarkus backend running at API_URL (default: http://localhost:9093)
#   - Test client registered: client_id=test, scopes=[expenses:read, users:read, expenses:write]
#
# Usage:
#   bash scope-enforcement-tests.sh
#   bash scope-enforcement-tests.sh 2>&1 | tee security-test-results.txt
# ==============================================================================
set -euo pipefail

# ==============================================================================
# Configuration
# ==============================================================================
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BOLD='\033[1m'
NC='\033[0m'

API_URL="${API_URL:-http://localhost:9093}"
CLIENT_ID="test"
CLIENT_SECRET="AeKQwFZgcCJHkD9Cwz0jqmsar-QDZe1uWy-Hjh1N4Hw"
COMPANY_UUID="40c94f77-b62c-46a5-8eb1-4e9c0a6b0e11"
USER_UUID=""
TOKEN=""

PASS_COUNT=0
FAIL_COUNT=0
SKIP_COUNT=0

# ==============================================================================
# Helper functions
# ==============================================================================

check_dependencies() {
    local missing=0
    for cmd in curl jq; do
        if ! command -v "$cmd" &>/dev/null; then
            echo -e "${RED}FATAL: required tool '${cmd}' is not installed.${NC}" >&2
            missing=1
        fi
    done
    if [[ $missing -ne 0 ]]; then exit 1; fi
}

get_token() {
    local response http_code body
    response=$(curl -s -w "\n%{http_code}" \
        -X POST "${API_URL}/auth/token" \
        -H "Content-Type: application/json" \
        -d "{\"client_id\":\"${CLIENT_ID}\",\"client_secret\":\"${CLIENT_SECRET}\"}" \
        2>&1) || {
            echo -e "${RED}FATAL: curl failed when calling /auth/token${NC}" >&2
            exit 1
        }
    http_code=$(printf '%s' "$response" | tail -n1)
    body=$(printf '%s' "$response" | sed '$d')
    if [[ "$http_code" != "200" ]]; then
        echo -e "${RED}FATAL: /auth/token returned HTTP ${http_code}. Cannot continue.${NC}" >&2
        echo "Response body: $body" >&2
        exit 1
    fi
    TOKEN=$(printf '%s' "$body" | jq -r '.access_token // empty' 2>/dev/null || true)
    if [[ -z "$TOKEN" ]]; then
        echo -e "${RED}FATAL: access_token missing from /auth/token response.${NC}" >&2
        exit 1
    fi
}

assert_status() {
    local test_id="$1" description="$2" expected="$3" actual="$4"
    if [[ "$actual" == "$expected" ]]; then
        echo -e "  ${GREEN}PASS${NC} [${test_id}] ${description} (HTTP ${actual})"
        ((PASS_COUNT++)) || true
    else
        echo -e "  ${RED}FAIL${NC} [${test_id}] ${description} — expected HTTP ${expected}, got ${actual}"
        ((FAIL_COUNT++)) || true
    fi
}

LAST_RESPONSE_BODY=""
run_test() {
    local test_id="$1" description="$2" method="$3" url="$4" expected_status="$5" body="${6:-}"
    local curl_args=(-s -w "\n%{http_code}" -X "$method" -H "Content-Type: application/json")
    if [[ -n "$TOKEN" ]]; then
        curl_args+=(-H "Authorization: Bearer ${TOKEN}")
    fi
    if [[ -n "$body" ]]; then
        curl_args+=(-d "$body")
    fi
    local response http_code
    response=$(curl "${curl_args[@]}" "$url" 2>&1) || {
        echo -e "  ${RED}FAIL${NC} [${test_id}] ${description} — curl error"
        ((FAIL_COUNT++)) || true
        LAST_RESPONSE_BODY=""
        return
    }
    http_code=$(printf '%s' "$response" | tail -n1)
    LAST_RESPONSE_BODY=$(printf '%s' "$response" | sed '$d')
    assert_status "$test_id" "$description" "$expected_status" "$http_code"
}

decode_jwt_payload() {
    local token="$1"
    local payload_b64
    payload_b64=$(printf '%s' "$token" | cut -d'.' -f2)
    local padded
    padded=$(printf '%s' "$payload_b64" | tr '_-' '/+')
    local mod=$(( ${#padded} % 4 ))
    if [[ $mod -eq 2 ]]; then padded="${padded}=="; fi
    if [[ $mod -eq 3 ]]; then padded="${padded}="; fi
    printf '%s' "$padded" | base64 --decode 2>/dev/null | jq '.' 2>/dev/null || true
}

# Data leakage helpers
_check_array_leakage() {
    local field="$1" body="$2"
    local leaked
    leaked=$(printf '%s' "$body" | jq -e \
        '[.[] | select(
            (.["'"$field"'"] != null) and
            (.["'"$field"'"] != "") and
            (.["'"$field"'"] != []) and
            (.["'"$field"'"] != {})
         )] | length > 0' 2>/dev/null)
    if [ "$leaked" = "true" ]; then return 1; fi
    return 0
}

_check_single_leakage() {
    local field="$1" body="$2"
    local leaked
    leaked=$(printf '%s' "$body" | jq -e \
        '(.["'"$field"'"] != null) and
         (.["'"$field"'"] != "") and
         (.["'"$field"'"] != []) and
         (.["'"$field"'"] != {})' 2>/dev/null)
    if [ "$leaked" = "true" ]; then return 1; fi
    return 0
}

_report_leakage_result() {
    local test_id="$1" description="$2" field="$3" is_leaked="$4" sample="$5"
    if [ "$is_leaked" = "leaked" ]; then
        echo -e "  ${RED}FAIL${NC} [${test_id}] ${description}"
        echo "         Field '${field}' contains non-empty data. Sample: ${sample}"
        FAIL_COUNT=$((FAIL_COUNT + 1))
    else
        echo -e "  ${GREEN}PASS${NC} [${test_id}] ${description}: field '${field}' absent/empty"
        PASS_COUNT=$((PASS_COUNT + 1))
    fi
}

_run_array_endpoint_checks() {
    local test_prefix="$1" endpoint_desc="$2" body="$3"
    local fields=("salaries" "userBankInfos" "statuses" "teams" "careerLevels" "cpr" "password" "roleList")
    local labels=("Salary data" "Bank info" "Status history" "Team assignments" "Career levels" "CPR/SSN" "Password hash" "Role list")
    local sub_ids=("a" "b" "c" "d" "e" "f" "g" "h")
    for i in "${!fields[@]}"; do
        local field="${fields[$i]}" label="${labels[$i]}" test_id="${test_prefix}${sub_ids[$i]}"
        if _check_array_leakage "$field" "$body"; then
            _report_leakage_result "$test_id" "$endpoint_desc — $label" "$field" "clean" ""
        else
            local sample
            sample=$(printf '%s' "$body" | jq -r \
                "[.[] | select(.\"$field\" != null and .\"$field\" != [] and .\"$field\" != \"\") | .\"$field\"] | first | tostring" \
                2>/dev/null | head -c 120)
            _report_leakage_result "$test_id" "$endpoint_desc — $label" "$field" "leaked" "$sample"
        fi
    done
}

_run_single_endpoint_checks() {
    local test_prefix="$1" endpoint_desc="$2" body="$3"
    local fields=("salaries" "userBankInfos" "statuses" "teams" "careerLevels" "cpr" "password" "roleList")
    local labels=("Salary data" "Bank info" "Status history" "Team assignments" "Career levels" "CPR/SSN" "Password hash" "Role list")
    local sub_ids=("a" "b" "c" "d" "e" "f" "g" "h")
    for i in "${!fields[@]}"; do
        local field="${fields[$i]}" label="${labels[$i]}" test_id="${test_prefix}${sub_ids[$i]}"
        if _check_single_leakage "$field" "$body"; then
            _report_leakage_result "$test_id" "$endpoint_desc — $label" "$field" "clean" ""
        else
            local sample
            sample=$(printf '%s' "$body" | jq -r ".\"$field\" | tostring" 2>/dev/null | head -c 120)
            _report_leakage_result "$test_id" "$endpoint_desc — $label" "$field" "leaked" "$sample"
        fi
    done
}

# ==============================================================================
# Pre-flight
# ==============================================================================
check_dependencies

echo ""
echo -e "${BOLD}======================================================"
echo "  Scope Enforcement Security Test Suite"
echo "  API_URL : ${API_URL}"
echo "  CLIENT  : ${CLIENT_ID}"
echo "  SCOPES  : expenses:read, users:read, expenses:write"
echo -e "======================================================${NC}"

# ==============================================================================
# CATEGORY A: Token & Authentication Tests
# ==============================================================================
echo ""
echo -e "${BOLD}--- Category A: Token & Authentication Tests ---${NC}"
echo ""

# A1: Valid credentials
get_token
if [[ -n "$TOKEN" ]]; then
    echo -e "  ${GREEN}PASS${NC} [A1] Valid credentials — access_token received"
    ((PASS_COUNT++)) || true
else
    echo -e "  ${RED}FAIL${NC} [A1] Valid credentials — access_token is empty"
    ((FAIL_COUNT++)) || true
fi

# A2: Wrong secret
run_test "A2" "Wrong client_secret → 401" POST "${API_URL}/auth/token" 401 \
    '{"client_id":"test","client_secret":"totally-wrong-secret-xyz"}'
A2_ERROR=$(printf '%s' "$LAST_RESPONSE_BODY" | jq -r '.error // empty' 2>/dev/null || true)
if [[ "$A2_ERROR" == "invalid_client" ]]; then
    echo -e "  ${GREEN}PASS${NC} [A2b] error=invalid_client in response"
    ((PASS_COUNT++)) || true
else
    echo -e "  ${RED}FAIL${NC} [A2b] expected error=invalid_client, got: ${A2_ERROR}"
    ((FAIL_COUNT++)) || true
fi

# A3: Unknown client_id
run_test "A3" "Unknown client_id → 401" POST "${API_URL}/auth/token" 401 \
    '{"client_id":"does-not-exist","client_secret":"irrelevant"}'
A3_ERROR=$(printf '%s' "$LAST_RESPONSE_BODY" | jq -r '.error // empty' 2>/dev/null || true)
if [[ "$A3_ERROR" == "invalid_client" ]]; then
    echo -e "  ${GREEN}PASS${NC} [A3b] error=invalid_client in response"
    ((PASS_COUNT++)) || true
else
    echo -e "  ${RED}FAIL${NC} [A3b] expected error=invalid_client, got: ${A3_ERROR}"
    ((FAIL_COUNT++)) || true
fi

# A4: Empty body
run_test "A4" "Empty JSON body → 400" POST "${API_URL}/auth/token" 400 '{}'
A4_ERROR=$(printf '%s' "$LAST_RESPONSE_BODY" | jq -r '.error // empty' 2>/dev/null || true)
if [[ "$A4_ERROR" == "invalid_request" ]]; then
    echo -e "  ${GREEN}PASS${NC} [A4b] error=invalid_request in response"
    ((PASS_COUNT++)) || true
else
    echo -e "  ${RED}FAIL${NC} [A4b] expected error=invalid_request, got: ${A4_ERROR}"
    ((FAIL_COUNT++)) || true
fi

# A5: JWT groups claim
JWT_PAYLOAD=$(decode_jwt_payload "$TOKEN")
if [[ -z "$JWT_PAYLOAD" ]]; then
    echo -e "  ${RED}FAIL${NC} [A5] Could not decode JWT payload"
    ((FAIL_COUNT++)) || true
else
    ACTUAL_GROUPS=$(printf '%s' "$JWT_PAYLOAD" | jq -r '.groups // [] | sort[]' 2>/dev/null || true)
    EXPECTED_GROUPS=$(printf '%s\n%s\n%s' "expenses:read" "expenses:write" "users:read" | sort)
    if [[ "$ACTUAL_GROUPS" == "$EXPECTED_GROUPS" ]]; then
        echo -e "  ${GREEN}PASS${NC} [A5] groups claim contains exactly: expenses:read, expenses:write, users:read"
        ((PASS_COUNT++)) || true
    else
        echo -e "  ${RED}FAIL${NC} [A5] groups mismatch — expected: $(echo "$EXPECTED_GROUPS" | tr '\n' ' '), got: $(echo "$ACTUAL_GROUPS" | tr '\n' ' ')"
        ((FAIL_COUNT++)) || true
    fi
fi

# A6: sub and iss claims
if [[ -n "$JWT_PAYLOAD" ]]; then
    JWT_SUB=$(printf '%s' "$JWT_PAYLOAD" | jq -r '.sub // empty' 2>/dev/null || true)
    JWT_ISS=$(printf '%s' "$JWT_PAYLOAD" | jq -r '.iss // empty' 2>/dev/null || true)
    if [[ "$JWT_SUB" == "$CLIENT_ID" ]]; then
        echo -e "  ${GREEN}PASS${NC} [A6a] sub='${CLIENT_ID}'"
        ((PASS_COUNT++)) || true
    else
        echo -e "  ${RED}FAIL${NC} [A6a] sub — expected '${CLIENT_ID}', got '${JWT_SUB}'"
        ((FAIL_COUNT++)) || true
    fi
    if [[ "$JWT_ISS" == "https://trustworks.dk" ]]; then
        echo -e "  ${GREEN}PASS${NC} [A6b] iss='https://trustworks.dk'"
        ((PASS_COUNT++)) || true
    else
        echo -e "  ${RED}FAIL${NC} [A6b] iss — expected 'https://trustworks.dk', got '${JWT_ISS}'"
        ((FAIL_COUNT++)) || true
    fi
fi

# ==============================================================================
# CATEGORY B: Positive Access Tests
# ==============================================================================
echo ""
echo -e "${BOLD}--- Category B: Positive Access Tests ---${NC}"
echo ""

# Bootstrap: resolve USER_UUID
BOOTSTRAP_RESPONSE=$(curl -s -w "\n%{http_code}" \
    -X GET "${API_URL}/users" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Content-Type: application/json" 2>&1) || true
BOOTSTRAP_CODE=$(printf '%s' "$BOOTSTRAP_RESPONSE" | tail -n1)
BOOTSTRAP_BODY=$(printf '%s' "$BOOTSTRAP_RESPONSE" | sed '$d')
if [[ "$BOOTSTRAP_CODE" == "200" ]]; then
    USER_UUID=$(printf '%s' "$BOOTSTRAP_BODY" | jq -r 'if type == "array" then .[0].uuid // empty else .uuid // empty end' 2>/dev/null || true)
    if [[ -n "$USER_UUID" && "$USER_UUID" != "null" ]]; then
        echo "  Resolved USER_UUID: ${USER_UUID}"
    else
        echo -e "  ${YELLOW}WARNING${NC} Could not extract UUID from /users. Per-user tests will skip."
        USER_UUID=""
    fi
else
    echo -e "  ${YELLOW}WARNING${NC} GET /users returned HTTP ${BOOTSTRAP_CODE}. Per-user tests will skip."
fi
echo ""

run_test "B1" "GET /users → 200" GET "${API_URL}/users" 200

if [[ -n "$USER_UUID" ]]; then
    run_test "B2" "GET /users/${USER_UUID} → 200" GET "${API_URL}/users/${USER_UUID}" 200
else
    echo -e "  ${YELLOW}SKIP${NC} [B2] USER_UUID not resolved"; ((SKIP_COUNT++)) || true
fi

run_test "B3" "GET /users/search/findUsersByDateAndStatusListAndTypes → 200" \
    GET "${API_URL}/users/search/findUsersByDateAndStatusListAndTypes?date=2026-01-01&consultantStatusList=ACTIVE&consultantTypes=CONSULTANT" 200

run_test "B4" "GET /users/employed/all → 200" GET "${API_URL}/users/employed/all" 200

if [[ -n "$USER_UUID" ]]; then
    run_test "B5" "GET /expenses/user/{uuid}/search/period → 200" \
        GET "${API_URL}/expenses/user/${USER_UUID}/search/period?fromdate=2025-01-01&todate=2026-01-01" 200
    run_test "B6" "GET /user-accounts/{uuid} → 200" \
        GET "${API_URL}/user-accounts/${USER_UUID}" 200
else
    echo -e "  ${YELLOW}SKIP${NC} [B5] USER_UUID not resolved"; ((SKIP_COUNT++)) || true
    echo -e "  ${YELLOW}SKIP${NC} [B6] USER_UUID not resolved"; ((SKIP_COUNT++)) || true
fi

# ==============================================================================
# CATEGORY C: Negative Access Tests (all must return 403)
# ==============================================================================
echo ""
echo -e "${BOLD}--- Category C: Negative Access Tests (expect 403) ---${NC}"
echo ""

# Salary domain (needs salaries:read)
run_test "C01" "GET /users/{uuid}/salaries → 403 (salaries:read)" GET "${API_URL}/users/${USER_UUID}/salaries" 403
run_test "C02" "GET /users/{uuid}/bankinfos → 403 (salaries:read)" GET "${API_URL}/users/${USER_UUID}/bankinfos" 403
run_test "C03" "GET /users/{uuid}/pensions → 403 (salaries:read)" GET "${API_URL}/users/${USER_UUID}/pensions" 403
run_test "C04" "GET /users/{uuid}/salarysupplements → 403 (salaries:read)" GET "${API_URL}/users/${USER_UUID}/salarysupplements" 403
run_test "C05" "GET /users/{uuid}/salarylumpsums → 403 (salaries:read)" GET "${API_URL}/users/${USER_UUID}/salarylumpsums" 403
run_test "C06" "GET /users/{uuid}/salaries/payments → 403 (salaries:read)" GET "${API_URL}/users/${USER_UUID}/salaries/payments/2026-01" 403

# Revenue domain (needs revenue:read)
run_test "C07" "GET /company/{uuid}/revenue/registered → 403 (revenue:read)" GET "${API_URL}/company/${COMPANY_UUID}/revenue/registered?fromdate=2025-01-01&todate=2026-01-01" 403
run_test "C08" "GET /users/{uuid}/revenue/registered → 403 (revenue:read)" GET "${API_URL}/users/${USER_UUID}/revenue/registered?periodFrom=2025-01-01&periodTo=2026-01-01" 403

# Invoicing domain (needs invoices:read)
run_test "C09" "GET /invoices → 403 (invoices:read)" GET "${API_URL}/invoices" 403

# Contracts domain (needs contracts:read)
run_test "C10" "GET /contracts → 403 (contracts:read)" GET "${API_URL}/contracts" 403

# CRM domain (needs crm:read)
run_test "C11" "GET /sales/leads → 403 (crm:read)" GET "${API_URL}/sales/leads" 403
run_test "C12" "GET /company/{uuid}/crm → 403 (crm:read)" GET "${API_URL}/company/${COMPANY_UUID}/crm" 403

# Bonus domains
run_test "C13" "GET /bonus/yourpartoftrustworks/basis → 403 (bonus:read)" GET "${API_URL}/bonus/yourpartoftrustworks/basis?fiscalstartyear=2025" 403
run_test "C14" "GET /invoices/eligibility → 403 (partnerbonus:read)" GET "${API_URL}/invoices/eligibility" 403

# Admin endpoints (needs admin:*)
run_test "C15" "GET /auth/clients → 403 (admin:*)" GET "${API_URL}/auth/clients" 403
run_test "C16" "GET /auth/audit-log → 403 (admin:*)" GET "${API_URL}/auth/audit-log" 403
run_test "C17" "GET /auth/endpoint-registry → 403 (admin:*)" GET "${API_URL}/auth/endpoint-registry" 403

# Budget domain (needs budgets:read)
run_test "C18" "GET /users/{uuid}/budgets → 403 (budgets:read)" GET "${API_URL}/users/${USER_UUID}/budgets" 403

# Knowledge domain (needs knowledge:read)
run_test "C19" "GET /knowledge/certifications → 403 (knowledge:read)" GET "${API_URL}/knowledge/certifications" 403

# Users write (test client does NOT have users:write)
run_test "C20" "PUT /users/{uuid} → 403 (users:write)" PUT "${API_URL}/users/${USER_UUID}" 403

# Utilization domain (needs utilization:read)
run_test "C21" "GET /company/{uuid}/utilization/budget → 403 (utilization:read)" GET "${API_URL}/company/${COMPANY_UUID}/utilization/budget?fromdate=2025-01-01&todate=2026-01-01" 403

# Signing domain (needs signing:read)
run_test "C22" "GET /utils/signing/cases → 403 (signing:read)" GET "${API_URL}/utils/signing/cases" 403

# User status domain (needs userstatus:read)
run_test "C23" "GET /users/{uuid}/statuses → 403 (userstatus:read)" GET "${API_URL}/users/${USER_UUID}/statuses" 403

# Teams domain (needs teams:read)
run_test "C24" "GET /teams → 403 (teams:read)" GET "${API_URL}/teams" 403

# Career level domain (needs careerlevel:read)
run_test "C25" "GET /users/{uuid}/careerlevels → 403 (careerlevel:read)" GET "${API_URL}/users/${USER_UUID}/careerlevels" 403

# Vacation domain (needs vacation:read)
run_test "C26" "GET /users/{uuid}/vacation → 403 (vacation:read)" GET "${API_URL}/users/${USER_UUID}/vacation?year=2025" 403

# Dashboard domain (needs dashboard:read)
run_test "C27" "GET /finance/cxo/revenue-margin-trend → 403 (dashboard:read)" GET "${API_URL}/finance/cxo/revenue-margin-trend" 403

# ==============================================================================
# CATEGORY D: Data Leakage Tests
# ==============================================================================
echo ""
echo -e "${BOLD}--- Category D: Data Leakage Tests ---${NC}"
echo -e "${BOLD}    Blocked fields: salaries, userBankInfos, statuses, teams,${NC}"
echo -e "${BOLD}    careerLevels, cpr, password, roleList${NC}"
echo ""

TMPFILE=$(mktemp)

# D1: GET /users (array)
echo "  D1: GET /users"
HTTP_STATUS=$(curl -s -o "$TMPFILE" -w "%{http_code}" \
    -H "Authorization: Bearer ${TOKEN}" -H "Accept: application/json" \
    "${API_URL}/users")
if [ "$HTTP_STATUS" -ne 200 ]; then
    echo -e "  ${YELLOW}SKIP${NC} D1 — HTTP $HTTP_STATUS"; SKIP_COUNT=$((SKIP_COUNT + 1))
else
    _run_array_endpoint_checks "D1" "GET /users" "$(cat "$TMPFILE")"
fi
echo ""

# D2: GET /users/{uuid} (single, default shallow)
if [[ -n "$USER_UUID" ]]; then
    echo "  D2: GET /users/${USER_UUID}"
    HTTP_STATUS=$(curl -s -o "$TMPFILE" -w "%{http_code}" \
        -H "Authorization: Bearer ${TOKEN}" -H "Accept: application/json" \
        "${API_URL}/users/${USER_UUID}")
    if [ "$HTTP_STATUS" -ne 200 ]; then
        echo -e "  ${YELLOW}SKIP${NC} D2 — HTTP $HTTP_STATUS"; SKIP_COUNT=$((SKIP_COUNT + 1))
    else
        _run_single_endpoint_checks "D2" "GET /users/{uuid}" "$(cat "$TMPFILE")"
    fi
    echo ""
else
    echo -e "  ${YELLOW}SKIP${NC} D2 — USER_UUID not resolved"; SKIP_COUNT=$((SKIP_COUNT + 1))
fi

# D3: GET /users/{uuid}?shallow=false (DEEP LOAD — highest leakage risk)
if [[ -n "$USER_UUID" ]]; then
    echo -e "  D3: GET /users/${USER_UUID}?shallow=false ${RED}(DEEP LOAD — highest risk)${NC}"
    HTTP_STATUS=$(curl -s -o "$TMPFILE" -w "%{http_code}" \
        -H "Authorization: Bearer ${TOKEN}" -H "Accept: application/json" \
        "${API_URL}/users/${USER_UUID}?shallow=false")
    if [ "$HTTP_STATUS" -ne 200 ]; then
        echo -e "  ${YELLOW}SKIP${NC} D3 — HTTP $HTTP_STATUS"; SKIP_COUNT=$((SKIP_COUNT + 1))
    else
        _run_single_endpoint_checks "D3" "GET /users/{uuid}?shallow=false" "$(cat "$TMPFILE")"
    fi
    echo ""
else
    echo -e "  ${YELLOW}SKIP${NC} D3 — USER_UUID not resolved"; SKIP_COUNT=$((SKIP_COUNT + 1))
fi

# D4: GET /users/employed/all (array)
echo "  D4: GET /users/employed/all"
HTTP_STATUS=$(curl -s -o "$TMPFILE" -w "%{http_code}" \
    -H "Authorization: Bearer ${TOKEN}" -H "Accept: application/json" \
    "${API_URL}/users/employed/all")
if [ "$HTTP_STATUS" -ne 200 ]; then
    echo -e "  ${YELLOW}SKIP${NC} D4 — HTTP $HTTP_STATUS"; SKIP_COUNT=$((SKIP_COUNT + 1))
else
    _run_array_endpoint_checks "D4" "GET /users/employed/all" "$(cat "$TMPFILE")"
fi
echo ""

# D5: GET /users/search/findUsersByDateAndStatusListAndTypes (array)
echo "  D5: GET /users/search/findUsersByDateAndStatusListAndTypes"
HTTP_STATUS=$(curl -s -o "$TMPFILE" -w "%{http_code}" \
    -H "Authorization: Bearer ${TOKEN}" -H "Accept: application/json" \
    "${API_URL}/users/search/findUsersByDateAndStatusListAndTypes?date=2026-01-01&consultantStatusList=ACTIVE&consultantTypes=CONSULTANT")
if [ "$HTTP_STATUS" -ne 200 ]; then
    echo -e "  ${YELLOW}SKIP${NC} D5 — HTTP $HTTP_STATUS"; SKIP_COUNT=$((SKIP_COUNT + 1))
else
    _run_array_endpoint_checks "D5" "GET /users/search/findUsersByDateAndStatusListAndTypes" "$(cat "$TMPFILE")"
fi
echo ""

# D6: GET /company/{uuid}/users (array)
echo "  D6: GET /company/${COMPANY_UUID}/users"
HTTP_STATUS=$(curl -s -o "$TMPFILE" -w "%{http_code}" \
    -H "Authorization: Bearer ${TOKEN}" -H "Accept: application/json" \
    "${API_URL}/company/${COMPANY_UUID}/users")
if [ "$HTTP_STATUS" -ne 200 ]; then
    echo -e "  ${YELLOW}SKIP${NC} D6 — HTTP $HTTP_STATUS"; SKIP_COUNT=$((SKIP_COUNT + 1))
else
    _run_array_endpoint_checks "D6" "GET /company/{uuid}/users" "$(cat "$TMPFILE")"
fi
echo ""

# D7: GET /users/expenses?month=2026-01 (UserFinanceDocument)
echo "  D7: GET /users/expenses?month=2026-01 (UserFinanceDocument)"
HTTP_STATUS=$(curl -s -o "$TMPFILE" -w "%{http_code}" \
    -H "Authorization: Bearer ${TOKEN}" -H "Accept: application/json" \
    "${API_URL}/users/expenses?month=2026-01")
if [ "$HTTP_STATUS" -ne 200 ]; then
    echo -e "  ${YELLOW}SKIP${NC} D7 — HTTP $HTTP_STATUS"; SKIP_COUNT=$((SKIP_COUNT + 1))
else
    BODY_D7=$(cat "$TMPFILE")
    # D7a: salary field must be 0
    leaked_salary=$(printf '%s' "$BODY_D7" | jq -e '[.[] | select(.salary != null and .salary != 0)] | length > 0' 2>/dev/null)
    if [ "$leaked_salary" = "true" ]; then
        sample=$(printf '%s' "$BODY_D7" | jq -r '[.[] | .salary] | first | tostring' 2>/dev/null)
        _report_leakage_result "D7a" "UserFinanceDocument — salary exposed" "salary" "leaked" "$sample"
    else
        _report_leakage_result "D7a" "UserFinanceDocument — salary masked" "salary" "clean" ""
    fi
    # D7b: staffSalaries must be 0
    leaked_staff=$(printf '%s' "$BODY_D7" | jq -e '[.[] | select(.staffSalaries != null and .staffSalaries != 0)] | length > 0' 2>/dev/null)
    if [ "$leaked_staff" = "true" ]; then
        sample=$(printf '%s' "$BODY_D7" | jq -r '[.[] | .staffSalaries] | first | tostring' 2>/dev/null)
        _report_leakage_result "D7b" "UserFinanceDocument — staffSalaries exposed" "staffSalaries" "leaked" "$sample"
    else
        _report_leakage_result "D7b" "UserFinanceDocument — staffSalaries masked" "staffSalaries" "clean" ""
    fi
    # D7c: embedded user objects must not have salary/bank data
    leaked_user=$(printf '%s' "$BODY_D7" | jq -e '[.[] | .user | select((.salaries != null and .salaries != []) or (.userBankInfos != null and .userBankInfos != []))] | length > 0' 2>/dev/null)
    if [ "$leaked_user" = "true" ]; then
        _report_leakage_result "D7c" "Embedded user salary/bank data" "user.salaries|userBankInfos" "leaked" "(see response)"
    else
        _report_leakage_result "D7c" "Embedded user salary/bank data absent" "user.salaries|userBankInfos" "clean" ""
    fi
    # D7d: embedded user must not have CPR or password
    leaked_sensitive=$(printf '%s' "$BODY_D7" | jq -e '[.[] | .user | select((.cpr != null and .cpr != "") or (.password != null and .password != ""))] | length > 0' 2>/dev/null)
    if [ "$leaked_sensitive" = "true" ]; then
        sample=$(printf '%s' "$BODY_D7" | jq -r '[.[] | .user | {cpr: .cpr, password: .password}] | first | tostring' 2>/dev/null | head -c 80)
        _report_leakage_result "D7d" "Embedded user CPR/password" "user.cpr|password" "leaked" "$sample"
    else
        _report_leakage_result "D7d" "Embedded user CPR/password absent" "user.cpr|password" "clean" ""
    fi
fi
echo ""

# D8: GET /users/consultants/search/findByFiscalYear (array)
echo "  D8: GET /users/consultants/search/findByFiscalYear?fiscalyear=2025"
HTTP_STATUS=$(curl -s -o "$TMPFILE" -w "%{http_code}" \
    -H "Authorization: Bearer ${TOKEN}" -H "Accept: application/json" \
    "${API_URL}/users/consultants/search/findByFiscalYear?fiscalyear=2025")
if [ "$HTTP_STATUS" -ne 200 ]; then
    echo -e "  ${YELLOW}SKIP${NC} D8 — HTTP $HTTP_STATUS"; SKIP_COUNT=$((SKIP_COUNT + 1))
else
    _run_array_endpoint_checks "D8" "GET findByFiscalYear" "$(cat "$TMPFILE")"
fi
echo ""

rm -f "$TMPFILE"

# ==============================================================================
# CATEGORY E: Token Integrity Tests
# ==============================================================================
echo ""
echo -e "${BOLD}--- Category E: Token Integrity Tests ---${NC}"
echo ""

PROBE_URL="${API_URL}/users/employed/all"
TMPFILE_E=$(mktemp)

# E1: No Authorization header
HTTP_STATUS=$(curl -s -o "$TMPFILE_E" -w "%{http_code}" -H "Accept: application/json" "$PROBE_URL")
if [ "$HTTP_STATUS" -eq 401 ]; then
    echo -e "  ${GREEN}PASS${NC} [E1] No auth header → 401"
    PASS_COUNT=$((PASS_COUNT + 1))
else
    echo -e "  ${RED}FAIL${NC} [E1] No auth header — expected 401, got $HTTP_STATUS"
    FAIL_COUNT=$((FAIL_COUNT + 1))
fi

# E2: Garbage bearer token
HTTP_STATUS=$(curl -s -o "$TMPFILE_E" -w "%{http_code}" \
    -H "Authorization: Bearer garbage123notavalidtoken" -H "Accept: application/json" "$PROBE_URL")
if [ "$HTTP_STATUS" -eq 401 ]; then
    echo -e "  ${GREEN}PASS${NC} [E2] Garbage token → 401"
    PASS_COUNT=$((PASS_COUNT + 1))
else
    echo -e "  ${RED}FAIL${NC} [E2] Garbage token — expected 401, got $HTTP_STATUS"
    FAIL_COUNT=$((FAIL_COUNT + 1))
fi

# E3: Tampered JWT (modified payload, original signature)
JWT_HEADER_E=$(printf '%s' "$TOKEN" | cut -d. -f1)
JWT_PAYLOAD_E=$(printf '%s' "$TOKEN" | cut -d. -f2)
JWT_SIGNATURE_E=$(printf '%s' "$TOKEN" | cut -d. -f3)
PADDED_E="${JWT_PAYLOAD_E}==="
DECODED_E=$(printf '%s' "$PADDED_E" | tr '_-' '/+' | base64 -d 2>/dev/null)
if [ -z "$DECODED_E" ]; then
    echo -e "  ${YELLOW}SKIP${NC} [E3] Could not decode JWT payload"
    SKIP_COUNT=$((SKIP_COUNT + 1))
else
    MODIFIED_E=$(printf '%s' "$DECODED_E" | jq '.sub = "hacker-tampered"' 2>/dev/null)
    if [ -z "$MODIFIED_E" ]; then
        echo -e "  ${YELLOW}SKIP${NC} [E3] JWT payload not valid JSON"
        SKIP_COUNT=$((SKIP_COUNT + 1))
    else
        MODIFIED_PAYLOAD_E=$(printf '%s' "$MODIFIED_E" | base64 | tr -d '\n=' | tr '+/' '-_')
        TAMPERED_TOKEN="${JWT_HEADER_E}.${MODIFIED_PAYLOAD_E}.${JWT_SIGNATURE_E}"
        HTTP_STATUS=$(curl -s -o "$TMPFILE_E" -w "%{http_code}" \
            -H "Authorization: Bearer ${TAMPERED_TOKEN}" -H "Accept: application/json" "$PROBE_URL")
        if [ "$HTTP_STATUS" -eq 401 ]; then
            echo -e "  ${GREEN}PASS${NC} [E3] Tampered JWT → 401 (signature validation works)"
            PASS_COUNT=$((PASS_COUNT + 1))
        else
            echo -e "  ${RED}FAIL${NC} [E3] CRITICAL: Tampered JWT accepted! Got $HTTP_STATUS"
            echo "         Server may NOT be verifying JWT signatures!"
            FAIL_COUNT=$((FAIL_COUNT + 1))
        fi
    fi
fi

# E4: Empty bearer value
HTTP_STATUS=$(curl -s -o "$TMPFILE_E" -w "%{http_code}" \
    -H "Authorization: Bearer " -H "Accept: application/json" "$PROBE_URL")
if [ "$HTTP_STATUS" -eq 401 ]; then
    echo -e "  ${GREEN}PASS${NC} [E4] Empty bearer → 401"
    PASS_COUNT=$((PASS_COUNT + 1))
else
    echo -e "  ${RED}FAIL${NC} [E4] Empty bearer — expected 401, got $HTTP_STATUS"
    FAIL_COUNT=$((FAIL_COUNT + 1))
fi

rm -f "$TMPFILE_E"

# ==============================================================================
# FINAL SUMMARY
# ==============================================================================
TOTAL=$(( PASS_COUNT + FAIL_COUNT + SKIP_COUNT ))
echo ""
echo -e "${BOLD}======================================================"
echo "  SECURITY TEST RESULTS"
echo "======================================================"
echo -e "  ${GREEN}PASS : ${PASS_COUNT}${NC}"
echo -e "  ${RED}FAIL : ${FAIL_COUNT}${NC}"
echo -e "  ${YELLOW}SKIP : ${SKIP_COUNT}${NC}"
echo -e "  TOTAL: ${TOTAL}"
echo ""
if [[ $FAIL_COUNT -gt 0 ]]; then
    echo -e "  ${RED}${BOLD}⚠ ${FAIL_COUNT} SECURITY ISSUE(S) DETECTED${NC}"
else
    echo -e "  ${GREEN}${BOLD}✓ All tests passed${NC}"
fi
echo -e "${BOLD}======================================================${NC}"
echo ""

if [[ $FAIL_COUNT -gt 0 ]]; then
    exit 1
fi
