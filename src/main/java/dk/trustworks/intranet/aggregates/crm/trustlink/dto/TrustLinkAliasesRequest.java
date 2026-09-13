package dk.trustworks.intranet.aggregates.crm.trustlink.dto;

import java.util.List;

/**
 * The complete list of TrustLink company names a client should map to. As with domains and
 * roles, a PUT that replaces the set rather than a pair of add/remove calls: the editor
 * shows the whole list, so what it sends IS the whole list, and two people editing the same
 * account cannot interleave into a state neither of them asked for.
 *
 * <p>What "replace" means here is narrower than it looks — only the MANUAL layer is
 * replaced. A name the nightly seeder found and the caller left alone stays AUTO; a name
 * the caller removed becomes a disabled MANUAL row rather than a deletion, so the seeder
 * does not simply add it back tomorrow night.
 */
public record TrustLinkAliasesRequest(List<String> companyNames) {
}
