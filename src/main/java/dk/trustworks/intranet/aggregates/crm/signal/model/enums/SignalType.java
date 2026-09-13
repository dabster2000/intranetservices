package dk.trustworks.intranet.aggregates.crm.signal.model.enums;

/**
 * What a signal is about (CRM spec §3.4).
 *
 * <p>Derived by the extractor, never picked by the author — the capture box is one
 * free-text field and nothing else (spec §0 principle 7, "AI does one job"). The value
 * is presented back before saving and may be corrected, so it is a suggestion the
 * author accepts, not a verdict.
 *
 * <p>{@link #OTHER} is the honest default: it is what an unextractable line, a refused
 * model call or an unavailable model all collapse to, so a capture is never lost merely
 * because nothing could be classified.
 */
public enum SignalType {

    /** Someone was hired, appointed, promoted or joined. */
    ORG_CHANGE,

    /** A need, budget, programme, platform or review is forming. */
    COMING_PROJECT,

    /** A known contact changed job or left. */
    CONTACT_MOVED,

    /** A tender or udbud is being run. */
    TENDER,

    /** Everything else, and the fallback when nothing could be classified. */
    OTHER
}
