package dk.trustworks.intranet.aggregates.crm.account.model.enums;

/**
 * The three bands an account sits in (CRM spec §3.1).
 *
 * <p>Three bands, never a ladder. The long list of clients nobody has prioritised is the
 * real situation and it needs no owner, no plan and no next step — {@link #BACKLOG} says
 * that out loud instead of pretending every client is being worked.
 *
 * <p>{@code BACKLOG} is the default for every client that has no {@code client_account}
 * row, which is all of them until somebody decides otherwise. That is deliberate: a
 * client's band should read as a decision a person took, not as a value a migration
 * guessed.
 */
public enum AccountBand {

    /** A nøglekunde. The staffing guard applies: consultants are not moved off without the owner. */
    STRATEGIC,

    /** Being worked, with an owner, a plan and a next step. */
    ACTIVE,

    /** Not prioritised. No owner, no plan and no next step are expected. */
    BACKLOG
}
