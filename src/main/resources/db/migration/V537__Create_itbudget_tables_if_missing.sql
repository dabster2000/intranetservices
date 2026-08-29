-- ===================================================================
-- IT budget: bring `itbudget` and `itbudget_category` under Flyway
--
-- Both tables predate Flyway in this repository — no migration ever
-- created them, so they exist in production and staging by hand and a
-- freshly built environment simply has no IT budget at all (every
-- query against them fails at boot-time schema validation or at the
-- first request).
--
-- This is CREATE TABLE IF NOT EXISTS with the DDL copied from the live
-- schema, so it is a pure no-op everywhere the tables already stand and
-- the authoritative definition everywhere they do not.
--
-- Deliberately absent:
--   * foreign keys. `itbudget.category_id` -> `itbudget_category.id`
--     and `itbudget.useruuid` -> `user.uuid` are both enforced by
--     ItExpenseService (an unknown categoryId is a 400; deleting a type
--     still in use is a 409). Adding them here would mean an ALTER on a
--     live table for a constraint the application already keeps, and
--     the existing rows would have to be proven clean first.
--   * seed rows. The equipment types are company policy, maintained in
--     Settings -> IT Budget, and staging must not silently acquire a
--     different set from production.
-- ===================================================================

CREATE TABLE IF NOT EXISTS `itbudget_category` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `name` varchar(25) DEFAULT NULL,
  `lifespan` int(11) DEFAULT NULL,
  `long_name` varchar(100) DEFAULT NULL,
  `description` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS `itbudget` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `useruuid` varchar(36) DEFAULT NULL,
  `description` varchar(100) DEFAULT NULL,
  `category_id` int(11) DEFAULT NULL,
  `price` int(11) DEFAULT NULL,
  `invoicedate` date DEFAULT NULL,
  `status` varchar(15) DEFAULT NULL,
  `created_at` timestamp NULL DEFAULT current_timestamp(),
  PRIMARY KEY (`id`),
  KEY `fk_itbudget_user` (`useruuid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
