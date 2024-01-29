CREATE TABLE `cko_course_participants` (
                                   `uuid` varchar(36) NOT NULL,
                                   `useruuid` varchar(36) NOT NULL,
                                   `status` varchar(15) DEFAULT NULL,
                                   `application_date` date DEFAULT NULL,
                                   `courseuuid` varchar(36) NOT NULL,
                                   PRIMARY KEY (`uuid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COMMENT='Trustworks Micro Course Participants';



