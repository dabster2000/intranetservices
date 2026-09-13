package dk.trustworks.intranet.aggregates.crm.account.dto;

/** One e-mail domain on a client, and whether a person asserted it or V585 guessed it. */
public record ClientDomainDTO(String uuid, String domain, String source) {
}
