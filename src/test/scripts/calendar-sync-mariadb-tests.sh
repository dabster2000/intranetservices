#!/usr/bin/env bash
set -euo pipefail

# Opt-in integration tests. Uses a cached image only, isolated tmpfs data, localhost binding,
# and synthetic credentials. No application configuration or external database is loaded.
cd "$(dirname "$0")/../../.."
calendar_test_mockito="${HOME}/.m2/repository/org/mockito/mockito-core/5.23.0/mockito-core-5.23.0.jar"
if [[ ! -f "$calendar_test_mockito" ]]; then
  echo "Resolve the project's Maven test dependencies first (Mockito 5.23.0 is required)." >&2
  exit 1
fi
calendar_test_container="calendar-sync-test-$$"
trap 'docker stop "$calendar_test_container" >/dev/null 2>&1 || true' EXIT
docker run --pull=never --detach --rm --name "$calendar_test_container" \
  --publish 127.0.0.1::3306 --tmpfs /var/lib/mysql:rw --cpus=2 --memory=1g \
  --env MARIADB_ROOT_PASSWORD=calendar-test-only \
  --env MARIADB_DATABASE=crm_calendar_test mariadb:10.11 >/dev/null
for calendar_test_attempt in {1..60}; do
  if docker exec "$calendar_test_container" mariadb-admin ping -uroot -pcalendar-test-only --silent >/dev/null 2>&1; then break; fi
  sleep 1
done
if ! docker exec "$calendar_test_container" mariadb-admin ping -uroot -pcalendar-test-only --silent >/dev/null 2>&1; then
  echo "The disposable calendar test database did not become ready." >&2
  exit 1
fi
calendar_test_port="$(docker port "$calendar_test_container" 3306 | sed 's/127.0.0.1://')"
export CRM_CALENDAR_IT_JDBC_URL="jdbc:mariadb://127.0.0.1:${calendar_test_port}/crm_calendar_test"
export CRM_CALENDAR_IT_PASSWORD=calendar-test-only
# The explicit agent avoids Mockito's dynamic self-attach requirement on recent JDKs.
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+${JAVA_TOOL_OPTIONS} }-javaagent:${calendar_test_mockito}"
./mvnw test -Dtest=CalendarSyncStateServiceMariaDbTest,CalendarCandidateServiceMariaDbTest,AccountRelationshipEvidenceMariaDbTest \
  -Dsurefire.forkCount=1 -Dsurefire.maxHeap=1g -DskipITs=true
