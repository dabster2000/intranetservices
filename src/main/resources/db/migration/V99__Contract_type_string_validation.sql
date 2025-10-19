-- Add CHECK constraint to validate contract type format
-- Ensures contract types follow the naming convention: uppercase letters, numbers, and underscores only
--
-- NOTE: The contracttype column is already VARCHAR(255) per V90 migration comment
-- This migration only adds validation constraints

-- Add CHECK constraint for contracts.contracttype
ALTER TABLE contracts
ADD CONSTRAINT chk_contract_contracttype_format
CHECK (contracttype REGEXP '^[A-Z0-9_]+$');

-- Add CHECK constraint for invoices.contract_type
ALTER TABLE invoices
ADD CONSTRAINT chk_invoice_contract_type_format
CHECK (contract_type REGEXP '^[A-Z0-9_]+$');

-- Add indexes for better query performance on contract type lookups
CREATE INDEX idx_contracts_contracttype ON contracts(contracttype);
CREATE INDEX idx_invoices_contract_type ON invoices(contract_type);
