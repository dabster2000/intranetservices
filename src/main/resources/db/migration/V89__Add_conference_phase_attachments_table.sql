-- Add conference_phase_attachments table to support file attachments in phase emails
CREATE TABLE conference_phase_attachments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    phaseuuid VARCHAR(255) NOT NULL,
    filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    content MEDIUMBLOB NOT NULL,
    file_size BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (phaseuuid) REFERENCES conference_phases(uuid) ON DELETE CASCADE,
    INDEX idx_phaseuuid (phaseuuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
