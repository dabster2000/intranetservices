CREATE TABLE borrowed_device (
    uuid          VARCHAR(36)  NOT NULL PRIMARY KEY,
    useruuid      VARCHAR(36)  NOT NULL,
    type          VARCHAR(50)  NOT NULL,
    description   VARCHAR(255) NULL,
    serial        VARCHAR(255) NULL,
    borrowed_date DATE         NOT NULL,
    returned_date DATE         NULL
);
