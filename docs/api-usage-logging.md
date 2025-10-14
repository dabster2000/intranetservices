# API Usage Logging

The `ApiUsageLoggingFilter` records every REST request. It logs the HTTP method, the request path and the username resolved by `HeaderInterceptor`.
When neither an `X-Requested-By` header nor a JWT token is present, the interceptor attempts to read a `username` query parameter. If no identifier can be found, the username `anonymous` is used.

To correlate multiple requests used to build the same page, the filter also logs the `Referer` header when present. Frontend clients may instead send an `X-View-Id` header to explicitly group requests belonging to a single view.

Typical log entry:

```
12:00:00,000 INFO  [dk.trustworks.intranet.logging.ApiUsageLoggingFilter] (executor-thread-1) API request - user=jdoe method=GET path=/projects referer=/dashboard
```

Inspect these logs to identify resources invoked multiple times during rendering of a particular view.

## Log collection API

Each request entry is persisted in the `api_usage_log` table. A REST endpoint at
`/api-usage-logs` exposes the collected data.

- `GET /api-usage-logs` - list logs filtered by `date`, `path` or `user` query
  parameters.
- `GET /api-usage-logs/count` - returns the number of requests for a path, with
  optional `date` filtering.
- `GET /api-usage-logs/performance` - returns max, min and average response time
  for a path. The `date` query parameter limits the calculation to a specific
  day.

## Micrometer metrics

Request counts and durations can also be captured with Micrometer. Add the
`quarkus-micrometer` extension and annotate resources with `@Timed` to record
timings automatically. For custom metrics inject a `MeterRegistry` and use
counters or timers per resource path.

For detailed examples of how client applications can query the `/api-usage-logs`
REST API, see [api-usage-log-client-guide.md](api-usage-log-client-guide.md).

The Flyway migration script `V67__Create_api_usage_log_table.sql` has been tested with MariaDB 10.11 and MySQL 8.0.
