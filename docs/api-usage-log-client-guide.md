# API Usage Log Resource Guide

The `/api-usage-logs` endpoints expose stored request data for analytics and monitoring. Clients can filter logs instead of retrieving the entire table.

## Listing logs

```
GET /api-usage-logs?date=2025-06-28&path=/projects&user=jdoe
```

- `date` (optional) limits the result to a single day (`YYYY-MM-DD`).
- `path` filters by the REST path.
- `user` filters by username.

## Counting requests

```
GET /api-usage-logs/count?path=/projects
GET /api-usage-logs/count?path=/projects&date=2025-06-28
```

The second form counts only requests on the specified day.

## Performance metrics

```
GET /api-usage-logs/performance?path=/projects
GET /api-usage-logs/performance?path=/projects&date=2025-06-28
```

Responses contain the maximum, minimum and average response times in milliseconds.

Use these endpoints to power dashboards or alerts without extracting large datasets from the server.
