-- Pricing-model descriptions used by consultant assignment forms and reporting.
-- Stable codes and all existing consultant selections are preserved.
UPDATE pricing_model_definitions
SET description = 'The full agreed hourly rate applies from the first day of the assignment.'
WHERE code = 'FULL_FROM_START';

UPDATE pricing_model_definitions
SET description = 'The hourly rate increases in agreed steps. Create a separate dated assignment for each rate period.'
WHERE code = 'STEPPED';

UPDATE pricing_model_definitions
SET description = 'A free introductory period with an agreed deadline, followed by the agreed paid hourly rate. Record the free and paid periods as separate dated assignments.'
WHERE code = 'PILOT_FREE';

UPDATE pricing_model_definitions
SET name = 'Model 4 — Free of charge',
    description = 'The assignment is free of charge for its entire duration, with no planned transition to a paid rate.'
WHERE code = 'COLLEAGUE_HOURS';
