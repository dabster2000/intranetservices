-- HR letters: signature-less salary-regulation notices and vacation-transfer
-- agreements (replaces the manual PDF + NextSign/MitID flow for both).
--
-- Legal basis for dropping signatures:
--  * Salary change  = one-way written notice (lov om ansaettelsesbeviser og
--    visse arbejdsvilkaar par. 8) delivered no later than the effective date.
--    No signature required from either side.
--  * Vacation transfer = written agreement (ferieloven par. 21-22) entered
--    before 31 December. Form-free: the employee's request row plus HR's
--    approval row IS the written agreement; both consents are stamped into
--    the generated PDF.
--
-- One table carries both flows: salary rows are auto-drafted by
-- SalaryService on every non-first salary row; vacation rows are created by
-- the employee from their profile. HR approves from the Letters console
-- (/employee-management/letters), which generates the PDF from an
-- EMPLOYEE_SIGNING document template, files it in the employee-documents S3
-- store, and notifies the employee on Slack. The employee acknowledges
-- receipt from their profile (delivery documentation, not a legal demand).

CREATE TABLE hr_letters (
    uuid                   VARCHAR(36)  NOT NULL PRIMARY KEY,
    useruuid               VARCHAR(36)  NOT NULL,
    letter_type            VARCHAR(32)  NOT NULL,  -- SALARY_REGULATION | VACATION_TRANSFER
    status                 VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',  -- DRAFT | SENT | ACKNOWLEDGED | DISMISSED
    payload                JSON         NOT NULL,  -- type-specific facts (amounts / days / years)
    salary_uuid            VARCHAR(36)  NULL,      -- triggering salary row (salary letters only)
    template_uuid          VARCHAR(36)  NULL,      -- document template chosen at approval
    employee_document_uuid VARCHAR(36)  NULL,      -- stored PDF in the employee-documents store
    requested_by           VARCHAR(36)  NOT NULL,  -- salary: who saved the raise; vacation: the employee
    approved_by            VARCHAR(36)  NULL,
    dismissed_by           VARCHAR(36)  NULL,
    dismiss_reason         VARCHAR(500) NULL,
    sent_at                DATETIME     NULL,
    acknowledged_at        DATETIME     NULL,
    created_at             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_hr_letters_user (useruuid, status),
    KEY idx_hr_letters_status (status, letter_type),
    KEY idx_hr_letters_salary (salary_uuid),
    CONSTRAINT chk_hr_letters_type   CHECK (letter_type IN ('SALARY_REGULATION', 'VACATION_TRANSFER')),
    CONSTRAINT chk_hr_letters_status CHECK (status IN ('DRAFT', 'SENT', 'ACKNOWLEDGED', 'DISMISSED'))
);

-- Letters console page, directly after the document console (715) in the
-- PEOPLE section. HR and ADMIN only, matching the BFF gate on
-- /api/hr-letters — team leads create raises but do not approve letters.
INSERT INTO page_registry
    (page_key, page_label, is_visible, react_route, required_roles, required_permission,
     display_order, section, icon_name, is_external, external_url)
VALUES
    ('hr-letters-console', 'Letters', 1, '/employee-management/letters',
     'HR,ADMIN', NULL, 716, 'PEOPLE', 'Mail', 0, NULL)
ON DUPLICATE KEY UPDATE
    page_label       = VALUES(page_label),
    react_route      = VALUES(react_route),
    required_roles   = VALUES(required_roles),
    display_order    = VALUES(display_order),
    section          = VALUES(section),
    icon_name        = VALUES(icon_name);
