-- Fix signing_cases.status which was always set to 'pending' due to
-- NextSign API not returning a case_status field. Derive correct status
-- from completed_signers / total_signers counts.

-- Fix completed cases (all signers have signed)
UPDATE signing_cases
SET status = 'completed', updated_at = NOW()
WHERE processing_status = 'COMPLETED'
  AND total_signers > 0
  AND completed_signers >= total_signers
  AND status = 'pending';

-- Fix in-progress cases (some signers have signed)
UPDATE signing_cases
SET status = 'in_progress', updated_at = NOW()
WHERE processing_status = 'COMPLETED'
  AND completed_signers > 0
  AND completed_signers < total_signers
  AND status = 'pending';
