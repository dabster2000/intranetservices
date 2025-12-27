-- ============================================================================
-- V145: Add missing menu items and external link support
-- ============================================================================
-- Purpose: Adds 4 menu items that were in MainLayout but not migrated to V144,
--          plus schema support for external URL links.
--
-- Missing items found from git history comparison:
-- 1. Projektvaerktoejer (external SharePoint link)
-- 2. Internal Invoices (InternalInvoiceListView)
-- 3. Accounting under Trustworks A/S (TrustworksAccountsView)
-- 4. Accounting under Trustworks Technology (TrustworksTechAccountsView)
--
-- Author: Claude Code
-- Date: 2024-12-25
-- ============================================================================

-- Add columns for external link support
ALTER TABLE page_migration
ADD COLUMN is_external BOOLEAN NOT NULL DEFAULT FALSE,
ADD COLUMN external_url VARCHAR(500) NULL;

-- ============================================================================
-- Add missing menu items
-- ============================================================================

-- Projektvaerktoejer (external SharePoint link) - top-level, display_order 99 (before profile at 6)
INSERT INTO page_migration (page_key, page_label, is_migrated, react_route, vaadin_route, vaadin_view_class, required_roles, display_order, section, icon_name, is_external, external_url) VALUES
('project-tools', 'Projektvaerktoejer', FALSE, '/project-tools', '', NULL, 'USER', 99, NULL, 'BookOpen', TRUE, 'https://trustworksaps.sharepoint.com/sites/TW-Tools/');

-- Internal Invoices (INVOICING section) - after Invoice List (301), before Invoice Controlling (302)
INSERT INTO page_migration (page_key, page_label, is_migrated, react_route, vaadin_route, vaadin_view_class, required_roles, display_order, section, icon_name) VALUES
('internal-invoices', 'Internal Invoices', FALSE, '/internal-invoices', 'Internal Invoice_List', 'InternalInvoiceListView', 'TECHPARTNER,ADMIN', 302, 'INVOICING', 'ArrowLeftRight');

-- Accounting under Trustworks A/S - after Teamlead Bonus (704)
INSERT INTO page_migration (page_key, page_label, is_migrated, react_route, vaadin_route, vaadin_view_class, required_roles, display_order, section, icon_name) VALUES
('tw-accounting', 'Accounting', FALSE, '/tw-accounting', 'tw-account-view', 'TrustworksAccountsView', 'ADMIN', 705, 'Trustworks A/S', 'Calculator');

-- Accounting under Trustworks Technology - after TWT Expense Distribution (801)
INSERT INTO page_migration (page_key, page_label, is_migrated, react_route, vaadin_route, vaadin_view_class, required_roles, display_order, section, icon_name) VALUES
('twt-accounting', 'Accounting', FALSE, '/twt-accounting', 'twt-account-view', 'TrustworksTechAccountsView', 'TECHPARTNER,ADMIN', 802, 'Trustworks Technology', 'Calculator');

-- ============================================================================
-- Summary: 4 navigation items added
-- - 1 external link (Projektvaerktoejer)
-- - 3 internal Vaadin views
-- ============================================================================
