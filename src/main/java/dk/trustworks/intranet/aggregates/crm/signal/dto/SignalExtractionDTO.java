package dk.trustworks.intranet.aggregates.crm.signal.dto;

/**
 * What the extractor read out of one line (CRM spec §3.4).
 *
 * <p>Every field is nullable. A line that names nobody is normal and fine — the capture
 * still saves. {@code clientUuid} is null when nothing matched a real client, and
 * {@code clientText} then carries the fragment the author appears to have meant, so the
 * panel can say "\"Ørsteds\" is not a client I know" instead of silently finding nothing.
 *
 * <p>A returned {@code clientUuid} is always a uuid the backend re-checked against the
 * real client list — the model never reaches persistence with an id nobody verified.
 *
 * @param clientUuid   a verified client uuid, or null
 * @param clientText   the client fragment as written, when no uuid could be resolved
 * @param personName   the person named, or null
 * @param personRole   their role, suffixed "(new)" when the line says so, or null
 * @param relationText how the author knows them, phrased from the author, or null
 * @param signalType   one of the {@code SignalType} names; never null — falls back to
 *                     {@code OTHER}
 * @param confidence   0.0–1.0, the model's own confidence in the reading
 */
public record SignalExtractionDTO(
        String clientUuid,
        String clientText,
        String personName,
        String personRole,
        String relationText,
        String signalType,
        Double confidence) {
}
