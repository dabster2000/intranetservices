-- Add REJECTED status to bug_reports for admin rejection workflow
ALTER TABLE bug_reports MODIFY COLUMN status
    ENUM('DRAFT','SUBMITTED','IN_PROGRESS','AUTO_FIX_REQUESTED','RESOLVED','CLOSED','REJECTED')
    NOT NULL DEFAULT 'DRAFT';
