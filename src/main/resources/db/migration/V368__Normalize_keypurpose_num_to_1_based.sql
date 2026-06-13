-- Normalize keypurpose.num to a single, consistent 1-based scheme (1, 2, 3).
--
-- Background
-- ----------
-- keypurpose.num was historically populated by two different conventions:
--   * the legacy pre-intranetservices monolith (table dates to 2017): 1-based -> {1,2,3}
--   * the current KeyPurposeService.findByUseruuid() lazy-create loop:  0-based -> {0,1,2}
-- The data was never normalized, leaving 118 users on {0,1,2} and 89 on {1,2,3}.
-- The mismatch caused real display bugs (profile Overview hid the first key
-- purpose for 0-based users; the team-dashboard KPC tab defaulted to a
-- non-existent "0" tab for 1-based users) and inconsistent "#0/#1/#2" vs
-- "#1/#2/#3" labels. The backend lazy-create loop is switched to 1-based in the
-- same change set, so new users are also provisioned as {1,2,3}.
--
-- Safety / no data loss
-- ---------------------
-- This shifts every 0-based user's rows up by one. It is LOSSLESS: only the num
-- value changes (description / meeting_notes are untouched), no rows are inserted
-- or deleted, and the total row count is unchanged.
--
-- Verified read-only against production before authoring:
--   * 621 rows / 207 users, each user has exactly 3 rows
--   * no NULL num; no num outside {0,1,2,3}; no duplicate (useruuid, num)
--   * no user holds both num=0 and num=3, so +1 can never create a stray "4"
--   * Pre-shift : num0=118  num1=207  num2=207  num3=89   (621 rows)
--   * Post-shift: num1=207  num2=207  num3=207             (621 rows, unchanged)
--
-- The 89 users already on {1,2,3} have no num=0 row and are intentionally left
-- untouched. The derived table is materialised first so MariaDB allows the
-- self-referencing UPDATE and evaluates the IN-list against the pre-update state.

UPDATE keypurpose
SET num = num + 1
WHERE useruuid IN (
    SELECT u FROM (SELECT DISTINCT useruuid AS u FROM keypurpose WHERE num = 0) AS zero_based_users
);
