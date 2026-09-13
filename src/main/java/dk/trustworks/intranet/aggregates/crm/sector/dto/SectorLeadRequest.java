package dk.trustworks.intranet.aggregates.crm.sector.dto;

/**
 * Who leads the sector from today. {@code clear} ends the current lead without naming a
 * new one; a JSON null and an absent key look the same after deserialisation, so clearing
 * needs the explicit flag.
 */
public record SectorLeadRequest(String userUuid, boolean clear) {
}
