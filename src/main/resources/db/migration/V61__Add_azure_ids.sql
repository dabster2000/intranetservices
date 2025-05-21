ALTER TABLE `user`
    ADD COLUMN azure_oid     CHAR(36)        NULL UNIQUE AFTER password,
    ADD COLUMN azure_issuer  VARCHAR(150)    NULL AFTER azure_oid;

CREATE UNIQUE INDEX idx_user_azure_oid_issuer
    ON `user` (azure_oid, azure_issuer);