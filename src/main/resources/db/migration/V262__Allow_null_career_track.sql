-- Allow career_track to be NULL for entry-level positions (JUNIOR_CONSULTANT has no track)
ALTER TABLE user_career_level MODIFY career_track VARCHAR(30) NULL;
