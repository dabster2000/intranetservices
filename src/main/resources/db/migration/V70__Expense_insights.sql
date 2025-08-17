CREATE TABLE IF NOT EXISTS `expense_insights` (
  `expense_uuid` varchar(36) NOT NULL,
  `useruuid` varchar(36) DEFAULT NULL,
  `merchant_name` varchar(255) DEFAULT NULL,
  `merchant_category` varchar(64) DEFAULT NULL,
  `confidence` decimal(5,4) DEFAULT NULL,
  `expense_date` date DEFAULT NULL,
  `currency` varchar(8) DEFAULT NULL,
  `total_amount` decimal(14,2) DEFAULT NULL,
  `subtotal_amount` decimal(14,2) DEFAULT NULL,
  `vat_amount` decimal(14,2) DEFAULT NULL,
  `payment_method` varchar(64) DEFAULT NULL,
  `country` varchar(64) DEFAULT NULL,
  `city` varchar(64) DEFAULT NULL,
  `drinks_total` decimal(14,2) DEFAULT NULL,
  `alcohol_total` decimal(14,2) DEFAULT NULL,
  `coffee_total` decimal(14,2) DEFAULT NULL,
  `juice_total` decimal(14,2) DEFAULT NULL,
  `water_total` decimal(14,2) DEFAULT NULL,
  `soft_drink_total` decimal(14,2) DEFAULT NULL,
  `raw_json` longtext,
  `model_name` varchar(64) DEFAULT NULL,
  `model_version` varchar(64) DEFAULT NULL,
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`expense_uuid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `expense_insight_line_items` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `expense_uuid` varchar(36) NOT NULL,
  `description` text,
  `quantity` decimal(14,4) DEFAULT NULL,
  `unit_price` decimal(14,4) DEFAULT NULL,
  `total` decimal(14,2) DEFAULT NULL,
  `item_category` varchar(64) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_line_items_expense_uuid` (`expense_uuid`),
  CONSTRAINT `fk_line_items_expense` FOREIGN KEY (`expense_uuid`) REFERENCES `expense_insights` (`expense_uuid`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `expense_insight_tags` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `expense_uuid` varchar(36) NOT NULL,
  `tag` varchar(64) NOT NULL,
  `confidence` decimal(5,4) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_expense_tag` (`expense_uuid`,`tag`),
  KEY `idx_tags_tag` (`tag`),
  CONSTRAINT `fk_tags_expense` FOREIGN KEY (`expense_uuid`) REFERENCES `expense_insights` (`expense_uuid`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX IF NOT EXISTS `idx_expense_insights_category` ON `expense_insights`(`merchant_category`);
CREATE INDEX IF NOT EXISTS `idx_expense_insights_date` ON `expense_insights`(`expense_date`);
CREATE INDEX IF NOT EXISTS `idx_expense_insights_user_date` ON `expense_insights`(`useruuid`, `expense_date`);
CREATE INDEX IF NOT EXISTS `idx_expense_insights_merchant` ON `expense_insights`(`merchant_name`);
