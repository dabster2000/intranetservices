package dk.trustworks.intranet.aggregates.bonus.individual.dto;

/** Public capability response consumed by the BFF and authoring UI. */
public record IndividualBonusCapabilitiesDTO(
        boolean authoringEnabled,
        boolean materializationEnabled,
        boolean reconciliationEnabled,
        int reconciliationLookbackMonths
) {
}
