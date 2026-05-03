-- Drop the deprecated meal-plan tables.
--
-- The meal-plan feature was retired but its DB tables had already been
-- dropped manually outside of Flyway at some point before this migration.
-- Production audit on 2026-05-03 showed:
--   - meal_plan, meal_plan_user, meal_plan_buffer, meal_plan_user_recipient
--     all missing in production (SHOW TABLES LIKE '%meal%' returns 0 rows).
--   - V51 (created the tables) and V55 (column renames) had succeeded in
--     flyway_schema_history; the tables were dropped by an out-of-band
--     operation.
--   - The application was still calling MealPlanResource which produced
--     SQLSyntaxErrorException 500s ("Table 'twservices4.meal_plan' doesn't
--     exist") on every frontpage hit.
--
-- The Java aggregates/lunch package, the BFF /lunch fetch, and the
-- frontend lunch UI elements are all removed in the same change as this
-- migration. This SQL is idempotent (DROP IF EXISTS) so it harmlessly runs
-- against any environment whose tables happen to still exist.

DROP TABLE IF EXISTS meal_plan_user_recipient;
DROP TABLE IF EXISTS meal_plan_buffer;
DROP TABLE IF EXISTS meal_plan_user;
DROP TABLE IF EXISTS meal_plan;
