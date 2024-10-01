CREATE TABLE `invoice_notes` (
                                 `uuid` varchar(36) NOT NULL,
                                 `contractuuid` varchar(36) NOT NULL,
                                 `projectuuid` varchar(36) NOT NULL,
                                 `month` date NOT NULL,
                                 `note` text,
                                 PRIMARY KEY (`uuid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci

