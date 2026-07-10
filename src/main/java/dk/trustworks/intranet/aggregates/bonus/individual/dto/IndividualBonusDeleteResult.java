package dk.trustworks.intranet.aggregates.bonus.individual.dto;

/**
 * The outcome of deleting an individual bonus rule.
 * <p>
 * A rule that has already driven a payout is <b>soft-deleted</b> ({@code active=false}) rather than
 * hard-deleted, so its live spec is preserved alongside the immutable {@code individual_bonus_payout}
 * snapshot (bonuses are money — a past payout must stay reconstructable). A rule that never paid is
 * hard-deleted.
 *
 * @param uuid        the rule that was deleted
 * @param softDeleted true = kept and deactivated (it had materialised payouts); false = hard-deleted
 * @param message     human-readable explanation of the outcome
 */
public record IndividualBonusDeleteResult(
        String uuid,
        boolean softDeleted,
        String message
) {
}
