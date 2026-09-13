package dk.trustworks.intranet.aggregates.crm.account.dto;

import java.util.List;

/** The complete domain list for a client. As with roles, a PUT that replaces the set. */
public record AccountDomainsRequest(List<String> domains) {
}
