package dk.trustworks.intranet.aggregates.crm.account.dto;

/**
 * "This is how I know her" — the body of {@code PUT /accounts/{clientUuid}/people/{personUuid}/claim}
 * (spec §3.5).
 *
 * <h2>There is no user field, and there must never be one</h2>
 * The claimant is the {@code X-Requested-By} user, resolved by the resource and nowhere else.
 * A body field would let somebody file a claim in a colleague's name — and since only the
 * claimant may remove their own claim, it would also let them plant one that nobody short of
 * a DBA could take out again. The same reasoning as {@code AccountSignalRequest}, which does
 * not carry an author either.
 *
 * <h2>Boxed Integer on purpose</h2>
 * {@code strength} is {@link Integer} rather than {@code int} so that a body which omits it
 * arrives as null and is refused with a 400 that says what the four values mean. An
 * {@code int} would arrive as 0, fail the range check anyway, and tell a caller nothing about
 * whether they sent a bad number or no number.
 *
 * <h2>Bean Validation is not active in this codebase</h2>
 * {@code quarkus-hibernate-validator} is absent, so a {@code @Min}/{@code @Size}/{@code @Valid}
 * here would be inert decoration that reads as a guarantee. Every check is hand-rolled in
 * {@code AccountResource} and again in {@code AccountRelationshipClaimService}, both throwing
 * {@code WebApplicationException} with an explicit status.
 *
 * @param strength 1 met once · 2 know each other · 3 good working relationship · 4 trusted.
 *                 Anything outside 1–4, including null, is a 400
 * @param how      free text ("worked together at KMD 2019–21"), optional, at most 255
 *                 characters. Rejected rather than truncated when it is longer: half a
 *                 sentence about how somebody knows a named third party says something they
 *                 did not mean
 */
public record AccountClaimRequest(Integer strength, String how) {
}
