-- ============================================================================
-- V143: Create page_migration table for React/Vaadin coexistence
-- ============================================================================
-- Purpose: Stores metadata about which pages have been migrated from Vaadin
--          to React, allowing dynamic routing without app rebuilds.
--
-- Usage:
--   - Toggle is_migrated to switch traffic between apps
--   - Both Vaadin and React menus fetch this registry to render navigation
--   - Changes take effect within 5 minutes (cache TTL)
--
-- Author: Claude Code
-- Date: 2024-12-24
-- ============================================================================

CREATE TABLE page_migration (
    id INT AUTO_INCREMENT PRIMARY KEY,

    -- Unique identifier for the page (e.g., 'dashboard', 'timesheet')
    page_key VARCHAR(50) NOT NULL UNIQUE,

    -- Display label shown in navigation menus
    page_label VARCHAR(100) NOT NULL,

    -- Whether this page has been migrated to React
    is_migrated BOOLEAN NOT NULL DEFAULT FALSE,

    -- React route path (e.g., '/dashboard')
    react_route VARCHAR(100) NOT NULL,

    -- Vaadin route path (e.g., 'frontpage')
    vaadin_route VARCHAR(100) NOT NULL,

    -- Vaadin view class name for reference (e.g., 'DashboardView')
    vaadin_view_class VARCHAR(150),

    -- Comma-separated list of required roles (e.g., 'USER', 'SALES,ADMIN')
    required_roles VARCHAR(255) NOT NULL DEFAULT 'USER',

    -- Display order in navigation menu
    display_order INT NOT NULL DEFAULT 0,

    -- Section/group name for menu organization (e.g., 'CRM', 'INVOICING')
    section VARCHAR(50),

    -- Lucide icon name for React menu (e.g., 'LayoutDashboard', 'Clock')
    icon_name VARCHAR(50),

    -- Timestamp when page was migrated to React
    migrated_at TIMESTAMP NULL,

    -- Audit timestamps
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    -- Indexes for common queries
    INDEX idx_is_migrated (is_migrated),
    INDEX idx_section (section),
    INDEX idx_display_order (display_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================================
-- Initial page registry data
-- ============================================================================
-- Pages marked as is_migrated=TRUE are already implemented in React
-- Pages marked as is_migrated=FALSE are still in Vaadin only

INSERT INTO page_migration (page_key, page_label, is_migrated, react_route, vaadin_route, vaadin_view_class, required_roles, display_order, section, icon_name, migrated_at) VALUES
-- Top-level navigation (no section)
('dashboard', 'Dashboard', TRUE, '/dashboard', 'frontpage', 'DashboardView', 'USER', 1, NULL, 'LayoutDashboard', NOW()),
('timesheet', 'Timesheet', TRUE, '/timesheet', 'timesheet', 'TimeSheetView', 'USER', 2, NULL, 'Clock', NOW()),
('profile', 'My Profile', TRUE, '/profile', 'profile-view', 'ProfileView', 'USER', 3, NULL, 'User', NOW()),
('organization', 'Organization', FALSE, '/organization', 'organization', 'OrganizationView', 'USER', 4, NULL, 'Building', NULL),
('faq', 'FAQ', FALSE, '/faq', 'faq', 'FaqView', 'USER', 5, NULL, 'HelpCircle', NULL),

-- KNOWLEDGE section
('bubbles', 'Bubbles', FALSE, '/bubbles', 'bubbles', 'BubbleView', 'USER', 30, 'KNOWLEDGE', 'Sparkles', NULL),
('courses', 'Courses', FALSE, '/courses', 'courses', 'CoursesView', 'USER', 31, 'KNOWLEDGE', 'GraduationCap', NULL),
('projects', 'Projects', FALSE, '/projects', 'project-description', 'ProjectDescriptionView', 'USER', 32, 'KNOWLEDGE', 'FolderKanban', NULL),

-- CRM section
('clients', 'Clients', TRUE, '/clients', 'client-list', 'ClientListView', 'USER', 40, 'CRM', 'Building2', NOW()),
('sales-leads', 'Sales Leads', TRUE, '/sales-leads', 'sales-list', 'LeadListView', 'SALES', 41, 'CRM', 'TrendingUp', NOW()),
('staffing', 'Staffing', FALSE, '/staffing', 'staffing', 'StaffingView', 'SALES', 42, 'CRM', 'Users', NULL),
('certifications', 'Certifications', FALSE, '/certifications', 'certifications-list', 'CertificationsListView', 'USER', 43, 'CRM', 'Award', NULL),
('allocation', 'Customer Allocation', FALSE, '/allocation', 'allocation', 'AllocationView', 'USER', 44, 'CRM', 'Calendar', NULL),

-- INVOICING section
('invoices', 'Invoices', TRUE, '/invoices', 'invoice_list', 'InvoiceListView', 'PARTNER,ADMIN', 50, 'INVOICING', 'FileText', NOW()),
('invoicing', 'Create Invoice', FALSE, '/invoicing', 'invoice', 'InvoiceView', 'SALES', 51, 'INVOICING', 'FilePlus', NULL),

-- MANAGEMENT section
('team-management', 'Team', FALSE, '/team-management', 'management-view', 'ManagementView', 'HR,TEAMLEAD', 60, 'MANAGEMENT', 'UserCog', NULL),

-- ADMIN section
('settings', 'Settings', FALSE, '/settings', 'admin-view', 'AdminView', 'EDITOR', 70, 'ADMIN', 'Settings', NULL),
('cache-metrics', 'Cache Metrics', FALSE, '/cache-metrics', 'cache-metrics', 'CacheMetricsView', 'ADMIN', 71, 'ADMIN', 'Activity', NULL);
