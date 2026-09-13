package dk.trustworks.intranet.aggregates.crm.signal.dto;

/**
 * The body of {@code POST /account-signals} — everything a caller is allowed to decide.
 *
 * <p>Deliberately narrow. The author, the source, the status and the timestamp are
 * derived server-side from the request context and are absent here on purpose, so no
 * caller can forge who heard something or pre-decide a signal (the mass-assignment rule;
 * same posture as {@code UserDeclaredAvailabilityResource} and {@code BugReportResource}).
 *
 * <p>{@code clientUuid} is required and must resolve to a real client: the capture panel
 * resolves it from the {@code @} picker, which is deterministic and does not depend on
 * the extractor. The four extracted fields are whatever the author accepted in the
 * preview — all nullable, because an unreadable line still deserves to be captured.
 *
 * @param clientUuid   the client the signal is about; required, must exist
 * @param text         the verbatim line as typed, {@code @Client} prefix included
 * @param personName   extracted person, or null
 * @param personRole   extracted role, or null
 * @param relationText how the author knows the person, phrased from the author, or null
 * @param signalType   one of {@code ORG_CHANGE, COMING_PROJECT, CONTACT_MOVED, TENDER,
 *                     OTHER}; null is accepted and stored as {@code OTHER}
 */
public record AccountSignalRequest(
        String clientUuid,
        String text,
        String personName,
        String personRole,
        String relationText,
        String signalType) {
}
