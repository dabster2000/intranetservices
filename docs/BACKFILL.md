Backfill & Replay Guide (Phase 5)

Overview
This service can replay historical domain events from the transactional outbox (outbox_events) into Kafka topics to re-derive aggregates or fix downstream data. This supports targeted backfills and full replays.

Endpoint
- Method: POST
- Path: /internal/backfill/outbox
- Query parameters:
  - start: ISO LocalDateTime (e.g., 2024-01-01T00:00:00). Default: now-1y at start of day
  - end: ISO LocalDateTime. Default: now
  - types: comma-separated AggregateEventType names to include (e.g., CREATE_USER_STATUS,UPDATE_WORK). Optional
  - limit: page size (default 1000)
  - offset: zero-based offset (default 0)
  - dryRun: true|false (default true). When true, nothing is produced; only a summary is returned

Behavior
- Selects from outbox_events by occurredAt within [start,end] and optional type IN (...)
- Maps OutboxEvent.type to Kafka topic using:
  - CREATE_USER_STATUS, DELETE_USER_STATUS -> user.status.updates
  - CREATE_USER_SALARY, DELETE_USER_SALARY -> user.salary.updates
  - UPDATE_WORK -> work.updates
  - MODIFY_CONTRACT_CONSULTANT -> contract.consultant.updates
- Builds EventData JSON for consumers:
  {
    "aggregateRootUUID": "<aggregateId>",
    "aggregateDate": "<derivedDate>"
  }
- Derived date is taken from payload field (first present): aggregateDate | statusdate | activefrom | registered | date | documentDate
  Fallback: occurredAt.toLocalDate() (ISO YYYY-MM-DD)
- Produces to Kafka with key = aggregateId; idempotent producer; metrics counters included

Examples
1) Dry-run count of last 30 days across all types:
   curl -X POST "http://localhost:9093/internal/backfill/outbox?start=$(date -v-30d +%Y-%m-%d)T00:00:00&dryRun=true"

2) Replay only user status and work updates for Jan 2024 in pages of 500:
   curl -X POST "http://localhost:9093/internal/backfill/outbox?start=2024-01-01T00:00:00&end=2024-02-01T00:00:00&types=CREATE_USER_STATUS,UPDATE_WORK&limit=500&offset=0&dryRun=false"

Notes
- Ensure Kafka is reachable (KAFKA_BOOTSTRAP_SERVERS env). Default is localhost:9092
- The resource is under /internal; protect it behind your gateway or with auth before using in production
- For large backfills, iterate offset until per-page results are empty
- Consumers are idempotent at the day granularity; duplicates will overwrite values for that user/day

Metrics
- Producer counters: kafka.producer.messages{result="success|error", topic="..."}
- Consumers already expose processing timers and counters (see OBSERVABILITY.md)

Caveats
- If payload does not contain a suitable date, occurredAt date will be used which may not match original domain intent. When necessary, provide a stricter type filter and date range.
