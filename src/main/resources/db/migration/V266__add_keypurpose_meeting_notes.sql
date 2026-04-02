-- V266__add_keypurpose_meeting_notes.sql
ALTER TABLE keypurpose ADD COLUMN meeting_notes TEXT DEFAULT NULL;
