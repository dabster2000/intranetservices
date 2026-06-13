package dk.trustworks.intranet.aggregates.users.danlon;

/** Result of proposeIfNeeded / proposeClose (spec §5). */
public enum ProposalOutcome {
    CREATED, ALREADY_PROPOSED, ALREADY_MINTED, REOPEN_PROPOSED, CLOSE_PROPOSED, SKIPPED, CONFLICT
}
