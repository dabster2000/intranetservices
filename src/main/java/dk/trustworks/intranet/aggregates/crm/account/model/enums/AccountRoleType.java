package dk.trustworks.intranet.aggregates.crm.account.model.enums;

/**
 * The two roles on an account — the Coffee Clients vocabulary (CRM spec §3.1, appendix B).
 *
 * <p>{@link #RESPONSIBLE} mirrors {@code client.accountmanager}, which stays the source of
 * truth for the owner in this cut: {@code AccountService} writes the role row alongside it
 * so the two can never disagree. {@link #SUPPORTED_BY} has no other home and is the reason
 * this table exists.
 */
public enum AccountRoleType {

    /** Exactly one per Strategic/Active account; equals the client's account manager. */
    RESPONSIBLE,

    /** Zero or more colleagues who help run the account. */
    SUPPORTED_BY
}
