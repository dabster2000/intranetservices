package dk.trustworks.intranet.aggregates.invoice.economics.customer.dto;

import dk.trustworks.intranet.aggregates.invoice.economics.customer.PairingSource;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/**
 * One Trustworks client/partner plus its e-conomic pairing status for a given
 * Trustworks company (=agreement). Returned by {@code GET /economics/customers/pairing}.
 *
 * <p>{@code pairingStatus} values:
 * <ul>
 *   <li>{@code PAIRED} — a {@code client_economics_customer} row exists</li>
 *   <li>{@code UNMATCHED} — no pairing yet and the indexer found no candidates</li>
 *   <li>{@code AMBIGUOUS} — multiple e-conomic customers matched by CVR or name</li>
 * </ul>
 *
 * SPEC-INV-001 §7.4.
 */
@Getter @AllArgsConstructor
public class PairingRowDto {
    private String clientUuid;
    private String clientName;
    private String cvrNo;
    private String clientType;                       // "CLIENT" | "PARTNER"
    private String pairingStatus;                    // "PAIRED" | "UNMATCHED" | "AMBIGUOUS"
    private PairingSource pairingSource;
    private Integer economicsCustomerNumber;         // null when not PAIRED
    private List<PairingCandidateDto> candidates;    // populated for AMBIGUOUS rows
}
