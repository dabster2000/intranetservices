package dk.trustworks.intranet.aggregates.crm.account.model.enums;

/**
 * The two roles on an account (CRM spec §3.1; account-people-merge spec §2, V598).
 *
 * <p><b>The owner is not here.</b> {@code client.accountmanager} is the single store for
 * it. There used to be a {@code RESPONSIBLE} value mirroring that column, rewritten by
 * {@code AccountService} on every write path — and never read, by anything, anywhere.
 * V598 deleted its 9 rows and narrowed the enum; the owner has one home now.
 *
 * <p>{@link #MEMBER} is where the {@code ACCOUNT_TEAM} bubbles' membership went. Those
 * bubbles were the third store for "who is on this account", they had no roles, no dates
 * and no status on a membership, and two of the busiest accounts — Rigspolitiet and
 * Banedanmark — had empty ones. The rows were MOVED, not recomputed from staffing:
 * where a bubble was populated it was a curated subset of who had ever worked there, and
 * that curation is information.
 */
public enum AccountRoleType {

    /** Zero or more colleagues who help the owner run the account. */
    SUPPORTED_BY,

    /** Zero or more colleagues on the account team (V598, from the ACCOUNT_TEAM bubbles). */
    MEMBER
}
