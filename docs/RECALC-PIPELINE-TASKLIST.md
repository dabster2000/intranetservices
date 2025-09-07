# Recalc Pipeline Implementation Tasklist

This document tracks the implementation of the simplified orchestration design (two tracks + fixed pipeline).

Scope (Phase 1):
- Fixed pipeline for operations: WORK_AGGREGATES → AVAILABILITY → BUDGET
- Triggers mapping → targets + startStage
- Orchestrator service (DayRecalcService)
- Batchlet delegates to orchestrator
- Salary path included via existing UserSalaryCalculatorService

Deferred (Phase 2+):
- Dirty-state (user_day_recalc) entity/repository + scheduler
- Kafka consumers (Outbox) for dirty upsert
- Observability spans/metrics beyond existing logging

Tasks
1. Orchestration types
   - [x] RecalcTrigger enum
   - [x] PipelineStage enum
   - [x] Target/Targets + mapping function
2. Orchestrator
   - [x] StageResult/RecalcResult types
   - [x] DayRecalcService with orderedStagesFrom and stop-on-error
3. Integration
   - [x] Refactor UserDayBatchletEnhanced to use DayRecalcService and trigger param
   - [x] Refactor UserStatusDayRecalcBatchletEnhanced to use DayRecalcService (default STATUS_CHANGE)
   - [x] Update user-status-forward-recalc.xml to pass trigger=STATUS_CHANGE
4. Tests/Build
   - [x] Compile and run tests (if any) -> build OK
5. Docs
   - [x] This tasklist
