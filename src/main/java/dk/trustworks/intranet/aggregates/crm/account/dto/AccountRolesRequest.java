package dk.trustworks.intranet.aggregates.crm.account.dto;

import java.util.List;

/**
 * The complete people-on-this-account payload: Supported by, and the account team.
 *
 * <p>A PUT, not a POST: the UI edits the whole set, and replacing it wholesale means no
 * add/remove race can leave a stale row.
 *
 * <p><b>Null and empty are different.</b> A null list means "leave that set alone" — so a
 * caller that only edits supporters need not send the members, and a frontend that
 * predates V598 keeps working. An empty list means "clear it". The header sends both.
 *
 * <p>The owner is NOT set here — it is {@code client.accountmanager}, changed on the
 * client form or through {@code PATCH /accounts/&#123;uuid&#125;} with {@code ownerUuid}.
 *
 * @param supportedByUuids colleagues who help the owner run the account, or null
 * @param memberUuids      the account team (V598, from the ACCOUNT_TEAM bubbles), or null
 */
public record AccountRolesRequest(List<String> supportedByUuids, List<String> memberUuids) {
}
