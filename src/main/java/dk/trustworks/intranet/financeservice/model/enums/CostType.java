package dk.trustworks.intranet.financeservice.model.enums;

/**
 * Classifies an accounting account for P&L line placement.
 *
 * <ul>
 *   <li>REVENUE      — Sales / invoiced revenue (groupname = 'Varesalg')</li>
 *   <li>DIRECT_COSTS — Project delivery costs (groupname = 'Direkte omkostninger')</li>
 *   <li>SALARIES     — Staff/admin payroll (salary flag accounts)</li>
 *   <li>OPEX         — Operating expenses (rent, software, marketing, admin)</li>
 *   <li>IGNORE       — Balance sheet, WIP, tax; excluded from all fact views</li>
 *   <li>OTHER        — Unclassified; treated as IGNORE until manually reviewed</li>
 * </ul>
 *
 * Winning priority during data migration: groupname > salary flag > OTHER.
 */
public enum CostType {
    REVENUE,
    DIRECT_COSTS,
    SALARIES,
    OPEX,
    IGNORE,
    OTHER
}
