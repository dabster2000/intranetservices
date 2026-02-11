-- Migrate Customer Allocation page to React
-- The page already exists in page_migration from V144 (page_key = 'allocation')
-- This migration marks it as migrated and updates properties for the React version

UPDATE page_migration
SET
  is_migrated = TRUE,
  react_route = '/allocation',
  icon_name = 'Orbit',
  required_roles = 'USER',
  migrated_at = NOW()
WHERE page_key = 'allocation';
