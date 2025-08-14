Phase 6 Cutover Runbook

Overview
This runbook guides the safe migration from AWS SNS + Lambdas to Kafka + Quarkus consumers/JBeret.

Feature Flags (application properties)
- feature.sns.enabled: true|false
  Controls whether SNS publishes are sent from the service. Default: true.
- feature.kafka.live-producer.enabled: true|false
  When true, the service publishes external events to Kafka directly from internal EventBus (ExternalEventBridge). Default: false.
- feature.kafka.consumers.shadow: true|false
  When true, Kafka consumers acknowledge messages but skip side effects. Default: true.

Cutover Steps
1) Dual-publish shadow period
   - Set:
     feature.sns.enabled=true
     feature.kafka.live-producer.enabled=true
     feature.kafka.consumers.shadow=true
   - Outcome: Continue SNS (Lambdas active) and publish to Kafka. New Kafka consumers run with no side effects.
   - Validate: counts, schemas, consumer processing metrics, no DLQ.

2) Enable side effects in Kafka consumers (still dual-publishing)
   - Set:
     feature.kafka.consumers.shadow=false
   - Outcome: Both Lambdas and Kafka consumers do the work. Monitor for duplicate effects; keep period short.
   - Alternatively, disable side effects in Lambdas or point them to shadow outputs if possible.

3) Disable Lambdas (stop SNS triggers) and switch off SNS publishing in the app
   - Disable/Remove Lambda triggers per topic.
   - Set:
     feature.sns.enabled=false
     feature.kafka.live-producer.enabled=true
     feature.kafka.consumers.shadow=false
   - Outcome: Kafka-only live path.

4) Stabilization window
   - Observe metrics and logs for 1–2 weeks: consumer lag, error rate, P95 latency, batch job success.
   - If issues arise, rollback by re-enabling Lambdas and setting feature.kafka.consumers.shadow=true.

5) Cleanup
   - Remove/retire Lambdas and SNS topics.
   - Remove legacy @Scheduled jobs (already replaced by JBeret).
   - Update documentation and diagrams.

Rollback
- Re-enable Lambda triggers.
- Set:
  feature.sns.enabled=true
  feature.kafka.consumers.shadow=true
- Optionally set feature.kafka.live-producer.enabled=false to stop dual publish, then investigate.

Notes
- Backfill/Replays: Use /internal/backfill/outbox to re-emit historical events to Kafka.
- Observability: /q/metrics, /health; see docs/OBSERVABILITY.md. Ensure Kafka broker exporter dashboards are in place.
