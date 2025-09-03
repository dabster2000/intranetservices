# Batch Job Tracking REST API

This API exposes the batch job execution tracking data stored in the `batch_job_execution_tracking` table. It allows you to list executions with filters and paging, fetch a single execution, get currently running jobs, and retrieve simple summaries.

Base path: `/batch/tracking`
Security: Requires JWT and role `SYSTEM` (same convention as other resources).
Media type: `application/json`

## Data model (response fields)
Execution object fields exposed by the API:
- id: long (internal DB row id)
- jobName: string (JBeret job id)
- executionId: long (JBeret execution id)
- status: string (e.g., STARTED, COMPLETED, FAILED, STOPPED)
- result: string (COMPLETED | PARTIAL | FAILED | null)
- exitStatus: string | null (batch exit status)
- progressPercent: number 0..100 | null
- totalSubtasks: int | null
- completedSubtasks: int | null
- startTime: ISO-8601 string (e.g., 2025-09-01T12:34:56.789) 
- endTime: ISO-8601 string | null
- details: string | null (free-form notes from listeners)

Paginated responses use the following envelope:
```
{
  "items": [ Execution, ... ],
  "page": 0,
  "size": 50,
  "total": 123
}
```

## Endpoints

### 1) List executions with filters
GET `/batch/tracking/executions`

Query parameters:
- jobName: string (exact match)
- status: string (case-insensitive; e.g. COMPLETED, STARTED, FAILED)
- result: string (case-insensitive; COMPLETED, PARTIAL, FAILED)
- runningOnly: boolean (default false). When true, only `STARTED`/`STARTING` are returned.
- startFrom: string (LocalDateTime ISO-8601, e.g. `2025-09-01T00:00:00`)
- startTo: string (LocalDateTime)
- endFrom: string (LocalDateTime)
- endTo: string (LocalDateTime)
- page: int (default 0)
- size: int (default 50, max 200)
- sort: string (one of `startTime,asc`, `startTime,desc` [default], `endTime,asc`, `endTime,desc`)

Example:
```
GET /batch/tracking/executions?jobName=bi-date-update&status=COMPLETED&startFrom=2025-08-01T00:00:00&startTo=2025-09-01T00:00:00&page=0&size=20
```

### 2) Get a single execution by executionId
GET `/batch/tracking/executions/{executionId}`

- Returns 200 with an Execution object or 404 if not found.

Example:
```
GET /batch/tracking/executions/123456
```

### 3) List only running executions
GET `/batch/tracking/running`

Query parameters:
- jobName: optional exact-match filter
- page, size: paging controls as above

Example:
```
GET /batch/tracking/running?jobName=budget-aggregation
```

### 4) Summary totals
GET `/batch/tracking/summary`

Query parameters:
- jobName: optional exact-match filter

Returns:
```
{
  "byStatus": { "COMPLETED": 100, "STARTED": 2, "FAILED": 1 },
  "byResult": { "COMPLETED": 98, "PARTIAL": 1, "FAILED": 2 }
}
```

## Notes on progress and partial completion
- For partitioned jobs with an analyzer (e.g., `bi-date-update`, `budget-aggregation`, and forward-recalc jobs), `completedSubtasks` is incremented when partitions/steps complete, and `totalSubtasks` is precomputed when possible, giving a meaningful `progressPercent`.
- For simple jobs, `totalSubtasks` and `completedSubtasks` reflect step counts captured by the step listener, offering a coarse progress metric.
- The `result` is derived at job end: `COMPLETED` (all done), `PARTIAL` (completed with some partitions/steps not finished), or `FAILED`.

## Performance considerations
- Queries are indexed on `(job_name, start_time)` and `status` to support common filters efficiently.
- Default paging size is 50; avoid large sizes for UIs that refresh frequently.

## Error handling
- Invalid date-time filters are ignored (filter not applied) to be client-friendly. Always prefer ISO-8601 `yyyy-MM-ddTHH:mm:ss[.SSS]` format.

## Security
- This resource requires a valid JWT and the `SYSTEM` role, consistent with other internal administrative endpoints.
