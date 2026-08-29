package dk.trustworks.intranet.aggregates.finance.dto.cxo;

import java.util.Map;
import java.util.Objects;

/**
 * One quarter's service-line penetration values for a single industry segment.
 *
 * @param quarterKey             calendar quarter key, e.g. {@code "2026-Q2"}
 * @param activeClients          distinct clients of the segment with revenue in
 *                               the quarter
 * @param clientsByServiceLine   distinct clients per service-line code (zero
 *                               entries omitted)
 * @param avgServiceLinesPerClient  average number of distinct service lines an
 *                               active client of the segment bought in the
 *                               quarter; {@code null} when no active clients
 */
public record IndustryServiceLinePointDTO(
        String quarterKey,
        int activeClients,
        Map<String, Integer> clientsByServiceLine,
        Double avgServiceLinesPerClient
) {
    public IndustryServiceLinePointDTO {
        Objects.requireNonNull(clientsByServiceLine, "clientsByServiceLine");
    }
}
