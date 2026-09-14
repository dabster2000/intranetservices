package dk.trustworks.intranet.aggregates.crm.merge;

import dk.trustworks.intranet.dao.crm.model.enums.ClientType;
import jakarta.ws.rs.WebApplicationException;

import java.time.LocalDateTime;
import java.util.Locale;

/**
 * The decisions of a merge that need no database — the spec's §9, as code. Pure, and
 * pinned by {@code ClientMergeRulesTest} in the fast tier.
 */
public final class ClientMergeRules {

    /** Whose {@code client_account} and whose account manager the survivor keeps (spec D2). */
    public enum AccountFrom {
        WINNER,
        LOSER;

        public static AccountFrom parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /**
     * Why these two rows may not be merged in this direction, or {@code null} when they may.
     *
     * <p><b>Only a prospect can be merged away</b> (spec D4, "prospect → anything only").
     * A CLIENT or a PARTNER has been billed, and its row IS the billing identity: Nærpension
     * is deliberately two rows with one CVR — the customer and the intermediary another
     * customer is billed through — and Arba Security the same. Collapsing either pair would
     * destroy the billing model, and the tool cannot tell that pair from a duplicate. The
     * survivor may be anything.
     *
     * <p>Widening this later is one line here and one sentence in the dialog; every other
     * part of the merge is direction-agnostic.
     */
    public static String directionProblem(String loserName, ClientType winnerType, ClientType loserType) {
        if (loserType != ClientType.PROSPECT) {
            String what = loserType == ClientType.PARTNER ? "a partner" : "a customer";
            return "Only a prospect can be merged away — " + loserName + " is " + what
                    + ", and a company that has been billed keeps its own row";
        }
        return null;
    }

    /** The two uuids name different rows, or the merge is meaningless. */
    public static String sameRowProblem(String winnerUuid, String loserUuid) {
        if (winnerUuid != null && winnerUuid.equals(loserUuid)) {
            return "A client cannot be merged into itself";
        }
        return null;
    }

    /**
     * Whether a {@code client_month_control} row says anything: an approval, or a note.
     * A row with neither is a placeholder somebody opened and closed.
     */
    public static boolean monthControlHasContent(LocalDateTime approvedAt, String note) {
        return approvedAt != null || (note != null && !note.isBlank());
    }

    /**
     * Which side's row stands for a month both rows control (spec D3): the one with
     * content, the winner on a tie — including the tie where neither says anything.
     */
    public static AccountFrom monthControlKeep(boolean winnerHasContent, boolean loserHasContent) {
        return loserHasContent && !winnerHasContent ? AccountFrom.LOSER : AccountFrom.WINNER;
    }

    /**
     * The account decision a request has to carry, given whether it is needed.
     *
     * <p>When only one side has a {@code client_account} row there is nothing to decide —
     * that row simply follows the survivor — and whatever the request says is ignored so a
     * dialog never has to ask a question with one answer. When both have one, the request
     * MUST say, and a missing or unknown value is refused: defaulting to the winner would
     * be a decision made in nobody's name about which band and which owner the merged
     * account keeps.
     */
    public static AccountFrom requireAccountFrom(String raw, boolean bothHaveAccounts) {
        if (!bothHaveAccounts) {
            return AccountFrom.WINNER;
        }
        AccountFrom parsed = AccountFrom.parse(raw);
        if (parsed == null) {
            throw new WebApplicationException(
                    "Both companies have an account — say whose band and owner the merged account keeps (accountFrom: WINNER or LOSER)",
                    400);
        }
        return parsed;
    }

    private ClientMergeRules() {
    }
}
