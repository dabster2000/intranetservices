-- ============================================================================
-- V144: Complete page_migration registry with all navigation items
-- ============================================================================
-- Purpose: Populates page_migration with ALL navigation items from Vaadin
--          MainLayout, enabling fully dynamic menu rendering in both React
--          and Vaadin frontends.
--
-- This replaces the partial data from V143 with a complete registry.
--
-- Architecture change: Vaadin now acts as the gateway for routing.
-- Nginx no longer needs migration-routes.conf - all routing decisions
-- are made based on this table.
--
-- Author: Claude Code
-- Date: 2024-12-25
-- ============================================================================

-- Clear existing partial data from V143
DELETE FROM page_migration;

-- ============================================================================
-- Insert all navigation items
-- ============================================================================
-- Notes:
-- - is_migrated=TRUE for pages already implemented in React (6 pages)
-- - display_order determines menu ordering within sections
-- - section=NULL means top-level navigation (no grouping)
-- - icon_name uses Lucide icon names for React compatibility
-- - vaadin_icon_class stores LineAwesome class for Vaadin (new column not needed - use icon registry)
-- ============================================================================

INSERT INTO page_migration (page_key, page_label, is_migrated, react_route, vaadin_route, vaadin_view_class, required_roles, display_order, section, icon_name, migrated_at) VALUES

-- ============================================================================
-- TOP-LEVEL NAVIGATION (no section)
-- ============================================================================
('dashboard', 'Frontpage', TRUE, '/dashboard', 'frontpage', 'DashboardView', 'USER', 1, NULL, 'LayoutDashboard', NOW()),
('timesheet', 'Time registration', TRUE, '/timesheet', 'timesheet', 'TimeSheetView', 'USER', 2, NULL, 'Clock', NOW()),
('taskboard', 'Task board', FALSE, '/taskboard', 'taskboard', 'TaskBoardView', 'USER', 3, NULL, 'ListTodo', NULL),
('organization', 'Organization', FALSE, '/organization', 'organization', 'OrganizationView', 'USER', 4, NULL, 'Users', NULL),
('faq', 'FAQ', FALSE, '/faq', 'faq', 'FaqView', 'USER', 5, NULL, 'HelpCircle', NULL),
('profile', 'My profile', TRUE, '/profile', 'profile-view', 'ProfileView', 'USER', 6, NULL, 'User', NOW()),

-- ============================================================================
-- KNOWLEDGE SECTION
-- ============================================================================
('bubbles', 'Bubbles', FALSE, '/bubbles', 'bubbles', 'BubbleView', 'USER', 100, 'KNOWLEDGE', 'Sparkles', NULL),
('courses', 'Courses', FALSE, '/courses', 'courses', 'CoursesView', 'USER', 101, 'KNOWLEDGE', 'GraduationCap', NULL),
('projects', 'Projects', FALSE, '/projects', 'project-description', 'ProjectDescriptionView', 'USER', 102, 'KNOWLEDGE', 'Book', NULL),

-- ============================================================================
-- CRM SECTION
-- ============================================================================
('clients', 'Clients', TRUE, '/clients', 'client-list', 'ClientListView', 'USER', 200, 'CRM', 'Building2', NOW()),
('framework-agreements', 'Framework Agreements', FALSE, '/framework-agreements', 'finance/framework-agreements', 'ContractTypesView', 'ADMIN,PARTNER', 201, 'CRM', 'FileText', NULL),
('account-teams', 'Account Teams', FALSE, '/account-teams', 'accountteams', 'AccountTeamsView', 'USER', 202, 'CRM', 'Building', NULL),
('sales-leads', 'Leads', TRUE, '/sales-leads', 'sales-list', 'LeadListView', 'SALES', 203, 'CRM', 'TrendingUp', NOW()),
('staffing', 'Staffing', FALSE, '/staffing', 'staffing', 'StaffingView', 'SALES', 204, 'CRM', 'Clipboard', NULL),
('certifications', 'Certifications', FALSE, '/certifications', 'certifications-list', 'CertificationsListView', 'USER', 205, 'CRM', 'Award', NULL),
('allocation', 'Customer Allocation', FALSE, '/allocation', 'allocation', 'AllocationView', 'USER', 206, 'CRM', 'BarChart3', NULL),
('device-management', 'Device Management', FALSE, '/device-management', 'device-management', 'DeviceManagementView', 'DPO', 207, 'CRM', 'Monitor', NULL),

-- ============================================================================
-- INVOICING SECTION
-- ============================================================================
('invoicing', 'Invoicing', FALSE, '/invoicing', 'invoice', 'InvoiceView', 'SALES', 300, 'INVOICING', 'Mail', NULL),
('invoices', 'Invoice List', TRUE, '/invoices', 'invoice_list', 'InvoiceListView', 'PARTNER,ADMIN', 301, 'INVOICING', 'FileText', NOW()),
('invoice-controlling', 'Invoice Controlling', FALSE, '/invoice-controlling', 'invoice-controlling-admin', 'InvoiceControllingAdmin', 'ADMIN', 302, 'INVOICING', 'BarChart', NULL),
('upload-monitor', 'Upload Monitor', FALSE, '/upload-monitor', 'invoice-upload-monitor', 'InvoiceUploadMonitorView', 'ACCOUNTING,ADMIN', 303, 'INVOICING', 'CloudUpload', NULL),
('sales-approved', 'Sales Approved', FALSE, '/sales-approved', 'bonus/partner-bonus', 'PartnerBonusStatusView', 'PARTNER', 304, 'INVOICING', 'CheckCircle', NULL),
('sales-approval', 'Sales Approval', FALSE, '/sales-approval', 'bonus/partner-bonus-approval', 'PartnerBonusApprovalView', 'ADMIN', 305, 'INVOICING', 'XCircle', NULL),

-- ============================================================================
-- MANAGEMENT SECTION
-- ============================================================================
('team', 'Team', FALSE, '/team', 'management-view', 'ManagementView', 'HR,TEAMLEAD', 400, 'MANAGEMENT', 'UserCog', NULL),
('salary-payment', 'Salary Payment', FALSE, '/salary-payment', 'salary-payment-view', 'SalaryPaymentView', 'HR', 401, 'MANAGEMENT', 'Wallet', NULL),
('your-part', 'Din del af Trustworks', FALSE, '/your-part', 'your-part-of-trustworks-view', 'YourPartOfTrustworksView', 'ADMIN', 402, 'MANAGEMENT', 'DollarSign', NULL),

-- ============================================================================
-- EXPENSES SECTION
-- ============================================================================
('failed-expenses', 'Failed Expenses', FALSE, '/failed-expenses', 'admin/failed-expenses', 'FailedExpensesView', 'ADMIN,HR', 500, 'EXPENSES', 'AlertTriangle', NULL),
('expense-management', 'Expense Management', FALSE, '/expense-management', 'management/expenses', 'ExpenseManagementView', 'HR', 501, 'EXPENSES', 'Receipt', NULL),

-- ============================================================================
-- MARKETING SECTION
-- ============================================================================
('conferences', 'Conferences', FALSE, '/conferences', 'conference-view', 'ConferencesHome', 'USER,MARKETING', 600, 'MARKETING', 'Mic', NULL),

-- ============================================================================
-- TRUSTWORKS A/S SECTION
-- ============================================================================
('townhall', 'Townhall', FALSE, '/townhall', 'tw-townhall-view', 'TrustworksTownhallView', 'ADMIN', 700, 'Trustworks A/S', 'Mic', NULL),
('tw-finance', 'Financial report', FALSE, '/tw-finance', 'tw-finance-view', 'TrustworksFinanceView', 'ADMIN', 701, 'Trustworks A/S', 'LineChart', NULL),
('tw-expense-dist', 'Expense distribution', FALSE, '/tw-expense-dist', 'accounting-distribution', 'AccountingDistributionView', 'ADMIN', 702, 'Trustworks A/S', 'PieChart', NULL),
('partner-bonus', 'Partner Bonus', FALSE, '/partner-bonus', 'bonus/partner-bonus-admin-view', 'PartnerBonusAdminView', 'ADMIN', 703, 'Trustworks A/S', 'Wallet', NULL),
('teamlead-bonus', 'Teamlead Bonus', FALSE, '/teamlead-bonus', 'bonus/teamlead-bonus-admin-view', 'TeamleadBonusAdminView', 'ADMIN', 704, 'Trustworks A/S', 'Banknote', NULL),

-- ============================================================================
-- TRUSTWORKS TECHNOLOGY SECTION
-- ============================================================================
('twt-finance', 'Financial report', FALSE, '/twt-finance', 'twt-finance-view', 'TrustworksTechFinanceView', 'TECHPARTNER,ADMIN', 800, 'Trustworks Technology', 'LineChart', NULL),
('twt-expense-dist', 'Expense distribution', FALSE, '/twt-expense-dist', 'accounting-distribution', 'AccountingDistributionView', 'TECHPARTNER,ADMIN', 801, 'Trustworks Technology', 'PieChart', NULL),

-- ============================================================================
-- TRUSTWORKS CYBER SECTION
-- ============================================================================
('twc-finance', 'Financial report', FALSE, '/twc-finance', 'tw-cyber-finance-view', 'TrustworksCyberFinanceView', 'CYBERPARTNER,ADMIN', 900, 'Trustworks Cyber', 'LineChart', NULL),
('twc-expense-dist', 'Expense distribution', FALSE, '/twc-expense-dist', 'accounting-distribution', 'AccountingDistributionView', 'CYBERPARTNER,ADMIN', 901, 'Trustworks Cyber', 'PieChart', NULL),

-- ============================================================================
-- ADMIN SECTION
-- ============================================================================
('settings', 'Settings', FALSE, '/settings', 'admin-view', 'AdminView', 'EDITOR', 1000, 'ADMIN', 'Settings', NULL),
('cache-metrics', 'Cache Metrics', FALSE, '/cache-metrics', 'cache-metrics', 'CacheMetricsView', 'ADMIN', 1001, 'ADMIN', 'Activity', NULL),
('api-usage-logs', 'API Usage Logs', FALSE, '/api-usage-logs', 'api-usage-logs', 'ApiUsageLogsView', 'ADMIN', 1002, 'ADMIN', 'Server', NULL),
('batch-tracking', 'Batch Tracking', FALSE, '/batch-tracking', 'batch-tracking', 'BatchTrackingView', 'ADMIN', 1003, 'ADMIN', 'ListTodo', NULL),
('news-admin', 'News Admin', FALSE, '/news-admin', 'news-admin', 'NewsAdminView', 'EDITOR,ADMIN', 1004, 'ADMIN', 'Newspaper', NULL),
('faq-admin', 'FAQ Admin', FALSE, '/faq-admin', 'faq-admin', 'FaqAdminView', 'EDITOR,ADMIN', 1005, 'ADMIN', 'HelpCircle', NULL),
('template-management', 'Template Management', FALSE, '/template-management', 'admin/templates', 'TemplateManagementView', 'ADMIN', 1006, 'ADMIN', 'FileCode', NULL);

-- ============================================================================
-- Summary: 46 navigation items
-- - 6 migrated to React (dashboard, timesheet, profile, clients, sales-leads, invoices)
-- - 40 still in Vaadin
-- ============================================================================
