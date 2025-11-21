-- Add AI validation result columns to expenses table
-- These columns store the AI validation decision from ExpenseAIValidationService
-- Added: 2025-11-20

ALTER TABLE expenses
    ADD COLUMN ai_validation_approved BOOLEAN NULL
        COMMENT 'AI validation decision: TRUE=approved, FALSE=rejected, NULL=not yet validated',
    ADD COLUMN ai_validation_reason TEXT NULL
        COMMENT 'AI validation reason/explanation (5-100 characters from OpenAI)';

-- Index for querying expenses by AI approval status
-- Useful for finding rejected expenses or generating validation reports
CREATE INDEX idx_expenses_ai_validation_approved ON expenses(ai_validation_approved);

-- Composite index for status + validation queries
-- Useful for finding VALIDATED expenses that were rejected by AI
CREATE INDEX idx_expenses_status_ai_validation ON expenses(status, ai_validation_approved);
