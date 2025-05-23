ALTER TABLE borrowed_device
    ADD clientuuid VARCHAR(36) NOT NULL AFTER useruuid,
    ADD CONSTRAINT borrowed_device_client_fk FOREIGN KEY (clientuuid) REFERENCES client (uuid)
        ON UPDATE CASCADE ON DELETE CASCADE;
