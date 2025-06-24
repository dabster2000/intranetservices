CREATE TABLE guest_registration (
    uuid VARCHAR(36) NOT NULL PRIMARY KEY,
    guest_name VARCHAR(255) NOT NULL,
    guest_company VARCHAR(255) NULL,
    employee_uuid VARCHAR(36) NOT NULL,
    employee_name VARCHAR(255) NOT NULL,
    registration_time DATETIME NOT NULL
);
