-- Framework Agreements Phase 4 G3: one value per parameter key on a contract.
-- V399 removed the audited orphan/null rows; pre-migration duplicate checks on
-- local, staging, and production all returned zero duplicate (contractuuid,name) pairs.
CREATE UNIQUE INDEX uq_contract_type_items_contract_key
    ON contract_type_items (contractuuid, name);
