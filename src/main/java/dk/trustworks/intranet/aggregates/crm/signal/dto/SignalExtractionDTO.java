package dk.trustworks.intranet.aggregates.crm.signal.dto;

import java.util.List;

/**
 * What the extractor read out of one line (CRM spec §3.4).
 *
 * <p>Every field is nullable or empty-able. A line that names nobody is normal and fine —
 * the capture still saves. {@link #clientUuids} is empty when nothing matched a real
 * client, and {@link #clientText} then carries the fragment the author appears to have
 * meant, so the panel can say "\"Ørsteds\" is not a client I know" instead of silently
 * finding nothing.
 *
 * <p>Every uuid in {@link #clientUuids} and {@link #colleagueUuids} is one the backend
 * re-checked against the real allowlist — the model never reaches persistence with an id
 * nobody verified.
 *
 * <p><b>What the two lists are FOR is not the same (V593).</b>
 * {@link #clientUuids} is a SUGGESTION: the accounts a line names are resolved by the
 * author's own {@code @} picks, and the panel uses this only to offer "you also mentioned
 * Rigspolitiet — add it?". A signal lands on an account because a person said so, never
 * because a model read it that way. {@link #colleagueUuids} is different: it is applied,
 * shown as removable chips, and saved unless the author takes one off. A colleague uuid
 * is internal data and the author is asserting "they were there" by pressing Save.
 *
 * @param clientUuids    verified client uuids the line appears to name; a suggestion only
 * @param clientText     the client fragment as written, when no uuid could be resolved
 * @param personName     the person named, or null
 * @param personRole     their role, suffixed "(new)" when the line says so, or null
 * @param relationText   how the author — and any named colleague — knows them, or null
 * @param colleagueUuids verified Trustworks colleagues the line named, besides the author
 * @param signalType     one of the {@code SignalType} names; never null — falls back to
 *                       {@code OTHER}
 * @param confidence     0.0–1.0, the model's own confidence in the reading
 */
public record SignalExtractionDTO(
        List<String> clientUuids,
        String clientText,
        String personName,
        String personRole,
        String relationText,
        List<String> colleagueUuids,
        String signalType,
        Double confidence) {
}
