package dk.trustworks.intranet.aggregates.crm.account.dto;

import java.util.List;

/**
 * The complete Supported-by list for an account. A PUT, not a POST: the UI edits the
 * whole set, and replacing it wholesale means no add/remove race can leave a stale row.
 *
 * <p>The Responsible is NOT set here — it is {@code client.accountmanager}, changed on
 * the client form.
 */
public record AccountRolesRequest(List<String> supportedByUuids) {
}
