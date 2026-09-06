package dk.trustworks.intranet.aggregates.finance.dto;

/** A person reference for lists that only need to name someone (JK Team 2.0 WP6). */
public record NameRef(String userId, String firstname, String lastname) {
}
