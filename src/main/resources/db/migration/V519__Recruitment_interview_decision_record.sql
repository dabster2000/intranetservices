-- ===================================================================
-- V519: Recruitment ATS — interview decision record (pipeline sub-status)
-- ===================================================================
-- Feature: Pipeline sub-status visualization — the opt-in decision
--          record that makes "decided, but candidate not yet informed"
--          representable on the board
-- Domain:  recruitmentservice (interview loop)
--
-- Purpose:
--   * decision / decided_by / decided_at: the owner's recorded go/no-go
--     for one interview round, taken BEFORE the stage move that
--     completes it. While set, the board card shows the "Inform
--     candidate" sub-status; the stage move (or terminal) that follows
--     consumes and clears it. History lives in the
--     INTERVIEW_DECISION_RECORDED / _CLEARED events, not in these
--     columns — they hold pending state only.
--
-- Design notes:
--   * All three columns are NULL for every existing row and for every
--     round decided the old way (decision = the stage move, which
--     remains fully supported). No backfill: a pending decision cannot
--     exist for the past by definition.
--   * decision is VARCHAR(10) holding RecruitmentInterviewDecision
--     enum names (ADVANCE | REJECT), the module's @Enumerated(STRING)
--     convention.
-- ===================================================================

ALTER TABLE recruitment_interviews
    ADD COLUMN decision   VARCHAR(10) NULL,
    ADD COLUMN decided_by CHAR(36)    NULL,
    ADD COLUMN decided_at DATETIME    NULL;
