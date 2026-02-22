-- V186: Widen sharepoint_file_url to TEXT for multi-document cases
-- Previously VARCHAR(1000), which can overflow when storing pipe-separated
-- SharePoint URLs for cases with multiple signed documents.

ALTER TABLE signing_cases
    MODIFY COLUMN sharepoint_file_url TEXT NULL
        COMMENT 'Pipe-separated SharePoint URLs of uploaded signed documents';
