-- Add description and text columns to conferences table
ALTER TABLE conferences
    ADD COLUMN description TEXT NULL AFTER active,
    ADD COLUMN note_text TEXT NULL AFTER description,
    ADD COLUMN consent_text TEXT NULL AFTER note_text,
    ADD COLUMN thanks_text TEXT NULL AFTER consent_text;
