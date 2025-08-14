# Observability Guide

This service exposes health, metrics, and tracing hooks to observe runtime behavior for Kafka consumers, batch jobs, and REST endpoints.

## Endpoints
- Health: GET /health (SmallRye Health)
  - Liveness: /q/health/live
  - Readiness: /q/health/ready
  - Startup: /q/health/started
- Metrics (Micrometer Prometheus): GET /q/metrics

Note: In dev profile, the root health path is configured as /health (see application-dev.yaml). Quarkus default management paths are under /q/.

## Metrics
Micrometer is enabled with Prometheus registry.

Custom consumer metrics added per Kafka channel:
- kafka_consumer_process_seconds (histogram/timer)
  - timer name: kafka.consumer.process
  - tags: channel = [user-status-updates|user-salary-updates|work-updates|contract-consultant-updates|budget-updates]
- kafka_consumer_messages_total (counter)
  - counter name: kafka.consumer.messages
  - tags: result = success|error, channel = <channel>

Useful PromQL examples:
- Per-channel throughput (5m):
  sum by (channel) (rate(kafka_consumer_messages_total{result="success"}[5m]))
- Error rate (5m):
  sum by (channel) (rate(kafka_consumer_messages_total{result="error"}[5m]))
- P95 processing latency:
  histogram_quantile(0.95, sum by (le, channel) (rate(kafka_consumer_process_seconds_bucket[5m])))

## Tracing (OpenTelemetry)
OpenTelemetry is integrated. By default in dev, OTLP exporter is disabled. Enable using env vars:
- OTEL_EXPORTER_OTLP_TRACES_ENABLED=true
- OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://otel-collector:4317

Reactive Messaging Kafka channels have tracing-enabled: true, so trace context is propagated via headers and spans are created around consumer processing methods annotated with @WithSpan.

## Logging
The console pattern is configured in application-dev.yaml. To include trace IDs in logs, switch to the alternative commented pattern:
- %d{HH:mm:ss} %-5p traceId=%X{traceId}, parentId=%X{parentId}, spanId=%X{spanId}, sampled=%X{sampled} [%c{2.}] (%t) %s%e%n

## Health Considerations
- Quarkus Kafka provides health checks for incoming channels. Readiness will fail if brokers are unreachable.
- Batch jobs can be monitored via logs and custom metrics if needed (future work: add batch job metrics).

## Dashboards & Alerts (Suggestions)
- Dashboard panels: consumer lag (from Kafka exporter), processing rate, error rate, latency P95/P99.
- Alerts: any DLQ topic traffic > 0/s sustained, error rate > 1% for 5 minutes, latency P95 > SLA.
