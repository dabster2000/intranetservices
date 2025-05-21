# Add slug column to conference table
ALTER TABLE conferences ADD COLUMN slug VARCHAR(80) NULL UNIQUE AFTER name;