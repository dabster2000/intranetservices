package dk.trustworks.intranet.aggregates.crm.sector.dto;

import dk.trustworks.intranet.aggregates.crm.account.dto.PersonDTO;

/** The sector an account is in, as its header shows it: the segment, its label and its lead. */
public record SectorRefDTO(String segment, String label, PersonDTO lead) {
}
