-- ============================================================================
-- V564: Auto-fix model catalogue — move the model allow-list out of Java
-- ============================================================================
-- Purpose: The Settings -> Auto-Fix model dropdown was driven by a hardcoded
--          Java constant (BugReportResource.ALLOWED_MODELS) plus a second
--          hardcoded label map in the frontend. Two lists, two repos, both
--          stale: claude-opus-5 and claude-sonnet-5 were not selectable at all.
--
--          This table becomes the single source of truth. Adding a model is now
--          one INSERT, not a two-repo code deploy.
--
-- Shape note: the columns here are deliberately the ones an automated refresher
--          would populate from Anthropic's GET /v1/models (id, display_name,
--          created_at, capabilities). A scheduled refresh job can be added later
--          writing to this same table with no schema rework and no API change.
--
-- Why not a CSV in autofix_config: a model carries per-model metadata (which
--          effort levels it accepts, cost tier, whether the deployed worker CLI
--          recognises it). A comma-separated string cannot express that, and the
--          effort levels in particular are not uniform across models.
--
-- Ordering: sort_order exists because the old code used Set.of(...), whose
--          iteration order is salted per JVM boot — the dropdown genuinely
--          rendered in a different order after every restart.
-- ============================================================================

CREATE TABLE IF NOT EXISTS autofix_model_catalog (
    model_id          VARCHAR(100) NOT NULL
                      COMMENT 'Exact CLI/API model id passed to claude --model',
    display_name      VARCHAR(100) NOT NULL
                      COMMENT 'Human label for the dropdown (e.g. Claude Opus 5)',
    family            VARCHAR(40)  NOT NULL
                      COMMENT 'Grouping for the dropdown optgroup: Opus / Sonnet / Haiku / Fable',
    sort_order        INT          NOT NULL DEFAULT 100
                      COMMENT 'Ascending display order; ties broken by model_id',
    supported_efforts VARCHAR(120) NOT NULL DEFAULT 'low,medium,high,xhigh,max'
                      COMMENT 'CSV of --effort levels this model accepts. Empty = model takes no effort flag',
    cost_tier         VARCHAR(20)  NOT NULL DEFAULT 'medium'
                      COMMENT 'low | medium | high | premium — drives the UI cost badge',
    available         TINYINT(1)   NOT NULL DEFAULT 1
                      COMMENT '0 hides the model from the picker without losing its metadata',
    recommended       TINYINT(1)   NOT NULL DEFAULT 0
                      COMMENT 'Marks the models we actively suggest for auto-fix runs',
    worker_status     VARCHAR(20)  NOT NULL DEFAULT 'UNKNOWN'
                      COMMENT 'VERIFIED | UNRECOGNIZED | UNKNOWN — whether the DEPLOYED worker CLI maps this id',
    notes             VARCHAR(255) NULL
                      COMMENT 'Free-text caveat surfaced to admins (e.g. why a model is unverified)',
    updated_by        VARCHAR(100) NULL
                      COMMENT 'UUID of admin who last changed the row, or system actor',
    updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (model_id),
    KEY idx_autofix_model_catalog_display (available, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='Selectable Claude models for the auto-fix worker (see BugReportResource auto-fix/config)';

-- ---------------------------------------------------------------------------
-- Seed.
--
-- worker_status reflects the DEPLOYED worker image, not the newest CLI. Both
-- tw-claude-worker-{staging,production} images were last pushed 2026-06-14, and
-- infra/claude-runner/Dockerfile installs @anthropic-ai/claude-code UNPINNED —
-- so the CLI in production is whatever was latest that day, which predates the
-- Claude 5 family. Models released after that date are UNKNOWN, not broken:
-- --model has no argument validator, so the id is forwarded verbatim. They stay
-- selectable behind an explicit warning in the UI.
--
-- Flip a row to VERIFIED once a real auto-fix run on that model has completed,
-- or after the worker image is rebuilt on a pinned CLI.
--
-- INSERT IGNORE: safe to re-run, and never clobbers a row an admin has edited.
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO autofix_model_catalog
    (model_id, display_name, family, sort_order, supported_efforts, cost_tier,
     available, recommended, worker_status, notes, updated_by)
VALUES
    ('claude-opus-5',       'Claude Opus 5',       'Opus',   10,
     'low,medium,high,xhigh,max', 'high',    1, 1, 'UNKNOWN',
     'Released after the deployed worker image was built (2026-06-14). Runs, but the CLI cost table may not price it.',
     'system:migration'),

    ('claude-opus-4-8',     'Claude Opus 4.8',     'Opus',   20,
     'low,medium,high,xhigh,max', 'high',    1, 1, 'VERIFIED',
     NULL, 'system:migration'),

    ('claude-opus-4-7',     'Claude Opus 4.7',     'Opus',   30,
     'low,medium,high,xhigh,max', 'high',    1, 0, 'VERIFIED',
     NULL, 'system:migration'),

    ('claude-sonnet-5',     'Claude Sonnet 5',     'Sonnet', 40,
     'low,medium,high,xhigh,max', 'medium',  1, 1, 'UNKNOWN',
     'Released after the deployed worker image was built (2026-06-14). Runs, but the CLI cost table may not price it.',
     'system:migration'),

    -- Sonnet 4.6 predates the xhigh level (introduced with Opus 4.7).
    ('claude-sonnet-4-6',   'Claude Sonnet 4.6',   'Sonnet', 50,
     'low,medium,high,max',       'medium',  1, 0, 'VERIFIED',
     NULL, 'system:migration'),

    -- The dated id claude-haiku-4-5-20251001 was the entry in the old Java
    -- allow-list; this is the same model under its undated alias. A config row
    -- still holding the dated id stays valid — the API unions the stored value
    -- into the allowed set so a saved setting can never become unsaveable.
    ('claude-haiku-4-5',    'Claude Haiku 4.5',    'Haiku',  60,
     'low,medium,high,xhigh,max', 'low',     1, 0, 'VERIFIED',
     'Effort support on this model is unverified against the deployed CLI; the levels above are the pre-existing behaviour, not a new claim.',
     'system:migration'),

    -- Seeded but hidden: premium tier, materially more expensive per token than
    -- Opus, and almost certainly unmapped by the deployed CLI. Enable with
    --   UPDATE autofix_model_catalog SET available = 1 WHERE model_id = 'claude-fable-5-1';
    ('claude-fable-5-1',    'Claude Fable 5.1',    'Fable',  70,
     'low,medium,high,xhigh,max', 'premium', 0, 0, 'UNKNOWN',
     'Hidden by default: premium pricing well above Opus. Verify the per-run budget cap is enforced before enabling.',
     'system:migration');
