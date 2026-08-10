-- HR document console (/employee-management/documents).
--
-- The three corpus-wide document backlogs — review queue, duplicate
-- filings, uncategorized documents. None of them is per-employee work:
-- duplicates span ~98 employees and the review queue ~97, so the existing
-- per-employee Documents tab could never surface them.
--
-- HR and ADMIN only, matching the BFF gate on
-- /api/admin/employee-documents/console. TEAMLEAD is deliberately absent:
-- team leads are excluded from employee documents throughout (spec §6.9),
-- and this page is cross-employee by construction.
--
-- Sits directly under "Team" (/employee-management, display_order 710) in
-- the PEOPLE section.

INSERT INTO page_registry
    (page_key, page_label, is_visible, react_route, required_roles, required_permission,
     display_order, section, icon_name, is_external, external_url)
VALUES
    ('employee-documents-console', 'Documents', 1, '/employee-management/documents',
     'HR,ADMIN', NULL, 715, 'PEOPLE', 'FileText', 0, NULL)
ON DUPLICATE KEY UPDATE
    page_label       = VALUES(page_label),
    react_route      = VALUES(react_route),
    required_roles   = VALUES(required_roles),
    display_order    = VALUES(display_order),
    section          = VALUES(section),
    icon_name        = VALUES(icon_name);
