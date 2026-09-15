-- One aggregate per presentation; deliberately no participant or request history.
-- The API allowlist bounds this to the five Cloud & AI Festival presentations.
-- No conference FK: shared download counts do not depend on registration/phase data.
CREATE TABLE conference_presentation_downloads (
    conference_uuid VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    presentation_id VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    download_requests BIGINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (conference_uuid, presentation_id)
) ENGINE=InnoDB;
