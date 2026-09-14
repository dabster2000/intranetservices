-- V610 — what the nightly client-enrichment jobs know about each client.
--
-- WHY. Three jobs now touch a client row without a person in the loop: the CVR
-- registry verification (Virkdata), the logo hunt (OpenAI web search, then image
-- generation) and the sector check (OpenAI, for clients filed under OTHER). Each of
-- them can succeed, need a person, or fail, and the client page has to be able to say
-- which — "CVR needed", "CVR 26573572 found, is this you?", "no logo could be found or
-- generated", "sector set to Financial Services by AI" — with enough detail that the
-- person can act on it in one click.
--
-- ONE ROW PER CLIENT, NOT ONE PER ATTEMPT. The question the UI asks is "what is the
-- state of this client right now", never "what happened on the 3rd". The attempt
-- counters and the checked-at timestamps are what the retry policy reads; the activity
-- log (client_activity_log) keeps the per-field history exactly as it does for a human
-- edit, so nothing is lost by not keeping a run log here.
--
-- No FK onto legacy client, matching V585/V586/V595. Statuses are varchar, not enums:
-- an unknown value from a later code version degrades to PENDING on read instead of
-- failing the row (the V609 posture).
--
-- CVR statuses: PENDING, VERIFIED, CANDIDATE (AI found a CVR whose registry name does
--   not match — a person confirms), NOT_FOUND (no CVR on the row and the AI found none),
--   INVALID (the registry knows no company for the stored CVR), DUPLICATE (the CVR is
--   already on another client), FAILED (lookup error, retried), DISMISSED (a person
--   said no), SKIPPED (not a Danish row).
-- Logo statuses: PENDING, PRESENT (a logo was already there), FOUND (downloaded from
--   the web), GENERATED (drawn by the image model), FAILED, SKIPPED (not a CLIENT).
-- Sector statuses: PENDING, CONFIRMED_OTHER, CHANGED (the AI set the segment),
--   HUMAN_SET (a person chose a sector), HUMAN_OVERRIDE (a person put an AI-set
--   client back to OTHER — never re-checked), FAILED, SKIPPED.

CREATE TABLE IF NOT EXISTS client_enrichment (
    client_uuid VARCHAR(36) NOT NULL PRIMARY KEY,

    cvr_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    cvr_checked_at DATETIME(6) NULL,
    cvr_verified_at DATETIME(6) NULL,
    cvr_candidate VARCHAR(8) NULL,
    cvr_candidate_name VARCHAR(255) NULL,
    cvr_candidate_source VARCHAR(500) NULL,
    cvr_error VARCHAR(255) NULL,
    cvr_attempts INT NOT NULL DEFAULT 0,

    logo_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    logo_checked_at DATETIME(6) NULL,
    logo_source_url VARCHAR(1000) NULL,
    logo_error VARCHAR(255) NULL,
    logo_attempts INT NOT NULL DEFAULT 0,

    sector_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    sector_checked_at DATETIME(6) NULL,
    sector_ai_segment VARCHAR(20) NULL,
    sector_confidence DECIMAL(4,3) NULL,
    sector_reason VARCHAR(500) NULL,
    sector_attempts INT NOT NULL DEFAULT 0,

    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    INDEX idx_client_enrichment_cvr (cvr_status, cvr_checked_at),
    INDEX idx_client_enrichment_logo (logo_status, logo_checked_at),
    INDEX idx_client_enrichment_sector (sector_status, sector_checked_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- The runtime kill switch, the AccountSlackFeatureFlag shape: read from app_settings on
-- every run, flip it through PUT /app-settings without a deploy. Seeded ON because the
-- three jobs were asked for as nightly jobs; the per-environment gate that keeps staging
-- from spending the same Virkdata quota and OpenAI credits on a copy of production's
-- clients every night is dk.trustworks.environment.id, checked by ClientEnrichmentJob,
-- and is not this row (app_settings is copied to staging by the nightly sync).
INSERT INTO app_settings (setting_key, setting_value, category, updated_by)
SELECT 'crm.enrichment.enabled', 'true', 'crm', 'V610'
WHERE NOT EXISTS (SELECT 1 FROM app_settings WHERE setting_key = 'crm.enrichment.enabled');
