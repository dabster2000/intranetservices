-- ====================================================================
-- V318: Reorganize page_registry into workflow-oriented sections.
--
-- Adds 9 missing pages, restructures sections (MAIN, COMPANY, SALES,
-- FINANCE, PEOPLE, KNOWLEDGE, INSIGHTS, TOOLS, ADMIN), and removes 19
-- stale rows pointing to non-existent routes.
--
-- Idempotent (INSERT ... ON DUPLICATE KEY UPDATE + UPDATE WHERE +
-- DELETE WHERE), so re-runs are safe.
-- ====================================================================

-- 1) Remove stale rows (routes that no longer exist as pages)
DELETE FROM page_registry WHERE page_key IN (
    'taskboard',
    'certifications',
    'upload-monitor',
    'failed-expenses',
    'townhall',
    'tw-finance',
    'partner-bonus',
    'teamlead-bonus',
    'tw-accounting',
    'twt-finance',
    'twt-expense-dist',
    'twt-accounting',
    'twc-finance',
    'twc-expense-dist',
    'cache-metrics',
    'api-usage-logs',
    'batch-tracking',
    'internal-invoices',
    'admin-practice-settings'
);

-- 2) Insert the 9 newly-registered pages
INSERT INTO page_registry
    (page_key, page_label, is_visible, react_route, required_roles, display_order, section, icon_name, is_external, external_url)
VALUES
    ('career-galaxy',                'Career Galaxy',               1, '/organization/career-galaxy',    'USER',             120, 'COMPANY', 'Sparkles',                  0, NULL),
    ('invoice-controlling-public',   'Invoice Controlling',         1, '/accounting/invoice-controlling','SALES,ACCOUNTING', 320, 'FINANCE', 'FileChartColumnIncreasing', 0, NULL),
    ('partner-bonus-admin',          'Partner Bonus Admin',         1, '/bonus/partner-bonus-admin',     'ADMIN',            350, 'FINANCE', 'UserCog',                   0, NULL),
    ('recruitment',                  'Recruitment',                 1, '/recruitment',                   'ADMIN',            870, 'ADMIN',   'UserPlus',                  0, NULL),
    ('accounting-accounts',          'Accounting Accounts',         1, '/admin/accounting-accounts',     'ADMIN',            880, 'ADMIN',   'BookOpen',                  0, NULL),
    ('invoice-recovery',             'Invoice Recovery',            1, '/admin/invoice-recovery',        'ADMIN',            890, 'ADMIN',   'RotateCcw',                 0, NULL),
    ('questionnaires',               'Questionnaires',              1, '/admin/questionnaires',          'ADMIN',            900, 'ADMIN',   'MessageCircleQuestionMark', 0, NULL),
    ('api-clients',                  'API Clients',                 1, '/admin/api-clients',             'ADMIN',            910, 'ADMIN',   'KeyRound',                  0, NULL),
    ('economics-settings',           'Economics Settings',          1, '/settings/economics',            'ADMIN',            920, 'ADMIN',   'Coins',                     0, NULL)
ON DUPLICATE KEY UPDATE
    page_label     = VALUES(page_label),
    is_visible     = VALUES(is_visible),
    react_route    = VALUES(react_route),
    required_roles = VALUES(required_roles),
    display_order  = VALUES(display_order),
    section        = VALUES(section),
    icon_name      = VALUES(icon_name),
    is_external    = VALUES(is_external),
    external_url   = VALUES(external_url);

-- 3) Re-section / reorder existing rows ----------------------------------

-- Main (top-level, NULL section)
UPDATE page_registry SET display_order = 10, section = NULL WHERE page_key = 'dashboard';
UPDATE page_registry SET display_order = 20, section = NULL WHERE page_key = 'timesheet';
UPDATE page_registry SET display_order = 30, section = NULL WHERE page_key = 'profile';
UPDATE page_registry SET display_order = 40, section = NULL WHERE page_key = 'faq';
UPDATE page_registry SET display_order = 50, section = NULL WHERE page_key = 'know-your-client';
UPDATE page_registry SET display_order = 60, section = NULL WHERE page_key = 'bug-reports';

-- COMPANY
UPDATE page_registry SET display_order = 110, section = 'COMPANY' WHERE page_key = 'organization';
UPDATE page_registry SET display_order = 130, section = 'COMPANY' WHERE page_key = 'conferences';

-- SALES & PIPELINE
UPDATE page_registry SET display_order = 210, section = 'SALES' WHERE page_key = 'clients';
UPDATE page_registry SET display_order = 220, section = 'SALES' WHERE page_key = 'sales-leads';
UPDATE page_registry SET display_order = 230, section = 'SALES' WHERE page_key = 'sales-overview';
UPDATE page_registry SET display_order = 240, section = 'SALES' WHERE page_key = 'account-manager-dashboard';
UPDATE page_registry SET display_order = 250, section = 'SALES' WHERE page_key = 'account-teams';
UPDATE page_registry SET display_order = 260, section = 'SALES' WHERE page_key = 'staffing';
UPDATE page_registry SET display_order = 270, section = 'SALES' WHERE page_key = 'allocation';
UPDATE page_registry SET display_order = 280, section = 'SALES' WHERE page_key = 'framework-agreements';

-- FINANCE (consolidates former INVOICING + ACCOUNTING + EXPENSES)
UPDATE page_registry SET display_order = 310, section = 'FINANCE' WHERE page_key = 'invoicing';
UPDATE page_registry SET display_order = 330, section = 'FINANCE' WHERE page_key = 'sales-approved';
UPDATE page_registry SET display_order = 340, section = 'FINANCE' WHERE page_key = 'sales-approval';
UPDATE page_registry SET display_order = 360, section = 'FINANCE' WHERE page_key = 'invoices';
UPDATE page_registry SET display_order = 370, section = 'FINANCE' WHERE page_key = 'internal-invoice-controlling';
UPDATE page_registry SET display_order = 380, section = 'FINANCE' WHERE page_key = 'expense-management';
UPDATE page_registry SET display_order = 390, section = 'FINANCE' WHERE page_key = 'tw-expense-dist';
UPDATE page_registry SET display_order = 395, section = 'FINANCE' WHERE page_key = 'your-part';

-- PEOPLE (formerly MANAGEMENT)
UPDATE page_registry SET display_order = 410, section = 'PEOPLE' WHERE page_key = 'team-dasboard';
UPDATE page_registry SET display_order = 420, section = 'PEOPLE' WHERE page_key = 'jk-team-dashboard';
UPDATE page_registry SET display_order = 430, section = 'PEOPLE' WHERE page_key = 'team';
UPDATE page_registry SET display_order = 440, section = 'PEOPLE' WHERE page_key = 'salary-payment';

-- KNOWLEDGE
UPDATE page_registry SET display_order = 510, section = 'KNOWLEDGE' WHERE page_key = 'bubbles';
UPDATE page_registry SET display_order = 520, section = 'KNOWLEDGE' WHERE page_key = 'courses';
UPDATE page_registry SET display_order = 530, section = 'KNOWLEDGE' WHERE page_key = 'projects';

-- INSIGHTS (formerly 'Trustworks A/S')
UPDATE page_registry
    SET display_order = 610,
        section = 'INSIGHTS',
        page_label = 'Executive Dashboard'
    WHERE page_key = 'cxo-dashboard';

-- TOOLS (external links)
UPDATE page_registry SET display_order = 710, section = 'TOOLS' WHERE page_key = 'trust-link';
UPDATE page_registry SET display_order = 720, section = 'TOOLS' WHERE page_key = 'cv-tools';
UPDATE page_registry SET display_order = 730, section = 'TOOLS' WHERE page_key = 'project-tools';

-- ADMINISTRATION
UPDATE page_registry SET display_order = 810, section = 'ADMIN' WHERE page_key = 'admin-bug-reports';
UPDATE page_registry SET display_order = 820, section = 'ADMIN' WHERE page_key = 'news-admin';
UPDATE page_registry SET display_order = 830, section = 'ADMIN' WHERE page_key = 'faq-admin';
UPDATE page_registry SET display_order = 840, section = 'ADMIN' WHERE page_key = 'template-management';
UPDATE page_registry SET display_order = 850, section = 'ADMIN' WHERE page_key = 'access-management';
UPDATE page_registry SET display_order = 860, section = 'ADMIN' WHERE page_key = 'device-management';
