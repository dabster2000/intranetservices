-- ============================================================================
-- V460: cv_tool_employee_cv — cvtool_employee_id is not unique
-- ============================================================================
-- V176 created the table with TWO unique keys:
--
--     UNIQUE KEY uk_useruuid (useruuid)                 -- one row per person
--     UNIQUE KEY uk_cvtool_employee (cvtool_employee_id) -- one row per CV Tool id
--
-- Against the real source data those two are mutually unsatisfiable. CV Tool
-- holds more than one employee record for the same Employee_UUID for 8 people
-- — an old record plus a re-created one (e.g. 35 "Hans Lassen" and 173 "Hans
-- Ernst Lassen"). That is a 2:1 mapping of employee id to person, so a table
-- that insists on one row per person AND one row per employee id cannot store
-- it. Whichever key the sync upserts on, the other constraint fires:
--
--     Duplicate entry '<useruuid>' for key 'uk_useruuid'
--     Duplicate entry '96'         for key 'uk_cvtool_employee'
--
-- Both were observed on staging on 2026-08-04, during the first sync runs to
-- reach the CV Tool API in over a month.
--
-- uk_useruuid is the constraint that carries the domain invariant: we store one
-- base CV per person, and CvToolResource looks the row up by useruuid.
-- cvtool_employee_id is provenance — which CV Tool record the stored CV came
-- from — and encodes an assumption about the upstream system that upstream does
-- not honour. It becomes a plain index: still useful for lookups and
-- debugging, no longer a correctness claim.
--
-- Purely relaxing: dropping a unique key cannot fail on existing rows and
-- touches no data.
-- ============================================================================

ALTER TABLE cv_tool_employee_cv
    DROP INDEX uk_cvtool_employee,
    ADD INDEX idx_cvtool_employee (cvtool_employee_id);
