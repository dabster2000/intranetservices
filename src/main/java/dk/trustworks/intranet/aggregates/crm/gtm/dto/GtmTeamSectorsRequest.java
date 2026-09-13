package dk.trustworks.intranet.aggregates.crm.gtm.dto;

import java.util.List;

/** The complete set of segments a GTM team covers. A PUT — the whole set, replaced. */
public record GtmTeamSectorsRequest(List<String> segments) {
}
