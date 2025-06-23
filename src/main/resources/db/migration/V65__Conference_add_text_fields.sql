-- Add description and text columns to conferences table
ALTER TABLE conferences
    ADD COLUMN description VARCHAR(255) NULL AFTER active,
    ADD COLUMN note_text VARCHAR(255) NULL AFTER description,
    ADD COLUMN consent_text VARCHAR(255) NULL AFTER note_text,
    ADD COLUMN thanks_text VARCHAR(255) NULL AFTER consent_text;
