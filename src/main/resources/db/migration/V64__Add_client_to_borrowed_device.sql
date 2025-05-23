ALTER TABLE borrowed_device
    ADD clientuuid VARCHAR(36) NOT NULL AFTER useruuid;
