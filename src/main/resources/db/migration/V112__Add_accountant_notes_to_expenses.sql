-- Add accountant_notes field to expenses table for storing voucher text from e-conomic
-- This field stores text/notes added by accountants during the verification/booking process in e-conomic
-- Added: 2025-11-19

ALTER TABLE expenses
    ADD COLUMN accountant_notes TEXT COMMENT 'Voucher text/notes from e-conomic added by accountants during verification/booking';
