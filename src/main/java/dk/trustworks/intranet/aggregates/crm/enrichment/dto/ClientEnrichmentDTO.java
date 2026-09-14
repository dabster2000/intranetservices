package dk.trustworks.intranet.aggregates.crm.enrichment.dto;

import dk.trustworks.intranet.aggregates.crm.enrichment.model.ClientEnrichment;
import dk.trustworks.intranet.aggregates.crm.enrichment.model.enums.CvrEnrichmentStatus;
import dk.trustworks.intranet.aggregates.crm.enrichment.model.enums.LogoEnrichmentStatus;
import dk.trustworks.intranet.aggregates.crm.enrichment.model.enums.SectorEnrichmentStatus;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * What the account page and the accounts list see of one client's enrichment state.
 *
 * <p>{@code attention} is the list of sentences a person has to act on, already worded;
 * {@code needsAttention} is its non-emptiness, so the list filter and the row marker never
 * disagree with the page. The provenance notes (an AI-found logo, an AI-set sector) are
 * NOT attention: they are shown, but nothing is being asked of anybody.
 */
public record ClientEnrichmentDTO(
        String clientUuid,
        CvrPart cvr,
        LogoPart logo,
        SectorPart sector,
        boolean needsAttention,
        List<String> attention) {

    public record CvrPart(String status, LocalDateTime checkedAt, LocalDateTime verifiedAt,
                          String candidateCvr, String candidateName, String candidateSource, String error) {}

    public record LogoPart(String status, LocalDateTime checkedAt, String sourceUrl, String error) {}

    public record SectorPart(String status, LocalDateTime checkedAt, String aiSegment, Double confidence, String reason) {}

    public static ClientEnrichmentDTO of(ClientEnrichment row) {
        CvrEnrichmentStatus cvr = row.cvrStatus();
        LogoEnrichmentStatus logo = row.logoStatus();
        SectorEnrichmentStatus sector = row.sectorStatus();
        List<String> attention = new ArrayList<>();
        switch (cvr) {
            case CANDIDATE -> attention.add("CVR " + row.getCvrCandidate() + " found for \"" + row.getCvrCandidateName()
                    + "\" — confirm it is this company");
            case NOT_FOUND -> attention.add(row.getCvrError() != null ? row.getCvrError() : "No CVR could be found — enter one");
            case INVALID -> attention.add(row.getCvrError() != null ? row.getCvrError() : "The CVR registry knows no company with this CVR");
            case DUPLICATE -> attention.add(row.getCvrError() != null ? row.getCvrError() : "This CVR is already on another client");
            case FAILED -> attention.add(row.getCvrError() != null ? "CVR lookup failed: " + row.getCvrError() : "CVR lookup failed");
            default -> { }
        }
        if (logo == LogoEnrichmentStatus.FAILED) {
            attention.add("No logo could be found or generated — upload one");
        }
        if (sector == SectorEnrichmentStatus.FAILED) {
            attention.add("The sector check failed — choose a sector or leave it to the next run");
        }
        return new ClientEnrichmentDTO(
                row.getClientUuid(),
                new CvrPart(cvr.name(), row.getCvrCheckedAt(), row.getCvrVerifiedAt(),
                        row.getCvrCandidate(), row.getCvrCandidateName(), row.getCvrCandidateSource(), row.getCvrError()),
                new LogoPart(logo.name(), row.getLogoCheckedAt(), row.getLogoSourceUrl(), row.getLogoError()),
                new SectorPart(sector.name(), row.getSectorCheckedAt(), row.getSectorAiSegment(),
                        row.getSectorConfidence() == null ? null : row.getSectorConfidence().doubleValue(), row.getSectorReason()),
                !attention.isEmpty(),
                List.copyOf(attention));
    }
}
