CREATE TABLE `bi_data_per_day` (
                                   `id` int NOT NULL AUTO_INCREMENT,
                                   `companyuuid` varchar(36) NOT NULL,
                                   `document_date` date DEFAULT NULL,
                                   `year` int DEFAULT NULL,
                                   `month` int DEFAULT NULL,
                                   `day` int DEFAULT NULL,
                                   `useruuid` varchar(36) NOT NULL,
                                   `gross_available_hours` decimal(7,4) DEFAULT NULL,
                                   `unavailable_hours` decimal(7,4) DEFAULT '0.0000',
                                   `vacation_hours` decimal(7,4) DEFAULT NULL,
                                   `sick_hours` decimal(7,4) DEFAULT NULL,
                                   `maternity_leave_hours` decimal(7,4) DEFAULT NULL,
                                   `non_payd_leave_hours` decimal(7,4) DEFAULT NULL,
                                   `paid_leave_hours` decimal(7,4) DEFAULT NULL,
                                   `consultant_type` varchar(50) DEFAULT NULL,
                                   `status_type` varchar(50) DEFAULT NULL,
                                   `contract_utilization` decimal(7,4) DEFAULT NULL,
                                   `actual_utilization` decimal(7,4) DEFAULT NULL,
                                   `registered_billable_hours` decimal(7,4) DEFAULT NULL,
                                   `helped_colleague_billable_hours` decimal(7,4) DEFAULT NULL,
                                   `registered_amount` decimal(9,2) DEFAULT NULL,
                                   `salary` int DEFAULT NULL,
                                   `last_update` datetime DEFAULT NULL,
                                   `is_tw_bonus_eligible` tinyint(1) DEFAULT '0',
                                   PRIMARY KEY (id),
                                   UNIQUE KEY uniq_useruuid_document_date (useruuid, document_date),
                                   KEY idx_availability_month (document_date),
                                   KEY idx_year (year),
                                   KEY idx_consultant_type (consultant_type),
                                   KEY idx_status_type (status_type)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;