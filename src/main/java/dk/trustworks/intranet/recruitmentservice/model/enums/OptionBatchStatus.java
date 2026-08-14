package dk.trustworks.intranet.recruitmentservice.model.enums;

/** Lifecycle of one candidate option batch (plan §8.1 / §11.1). */
public enum OptionBatchStatus {
    /** The token works; the candidate can view and select. */
    ACTIVE,
    /** Terminal: a selection was committed (or the request left
     * automation) — the token answers a uniform 404. */
    CLOSED,
    /** Terminal: the candidate deadline passed unanswered. */
    EXPIRED
}
