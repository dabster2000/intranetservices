package dk.trustworks.intranet.aggregates.crm.merge.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * What a merge WOULD do — read before anything is written, and shown to the person in the
 * confirm dialog word for word ("101 invoices, 1 contract, 31 TrustLink connections, 2
 * orphaned e-conomic customers"). The merge itself re-derives every number inside its own
 * transaction; this is the picture, not the contract.
 *
 * @param problem            why the merge would be refused — the same sentence the POST
 *                           answers 409 with — or {@code null} when it may proceed
 * @param moves              per column, how many of the loser's rows move to the winner;
 *                           only columns with rows
 * @param dropped            per table, how many of the loser's rows are removed because
 *                           the winner already holds the same unique key
 * @param account            the two accounts when BOTH rows have one and a person has to
 *                           choose (spec D2); {@code null} when at most one has
 * @param monthControls      the months both rows control, with the rule's decision (D3)
 * @param orphanedEconomics  the loser's e-conomic customers the winner already has one
 *                           for in the same agreement: dropped by the merge, live in
 *                           e-conomic, to be deactivated by hand (spec §5)
 * @param movedEconomics     the loser's e-conomic customers in an agreement the winner has
 *                           none for: the mapping moves to the winner
 * @param skippedTables      registry tables absent from this database (a staging copy
 *                           behind production), skipped with a warning
 */
public record ClientMergePreviewDTO(
        ClientRef winner,
        ClientRef loser,
        String problem,
        List<TableCount> moves,
        List<TableCount> dropped,
        AccountChoice account,
        List<MonthControlDecision> monthControls,
        List<EconomicsCustomerRef> orphanedEconomics,
        List<EconomicsCustomerRef> movedEconomics,
        List<String> skippedTables,
        long totalMoves,
        long totalDropped) {

    public record ClientRef(
            String uuid,
            String name,
            String type,
            String cvr,
            LocalDateTime created,
            String accountManagerUuid,
            String accountManagerName,
            /** Already merged into another client — a tombstone. */
            boolean merged) {}

    public record TableCount(String table, String column, long rows) {}

    public record AccountSide(String band, String ownerUuid, String ownerName, String nextStep, String gtmBubbleUuid) {}

    public record AccountChoice(AccountSide winner, AccountSide loser) {}

    /** @param keep WINNER or LOSER — whose control row stands for this month */
    public record MonthControlDecision(LocalDate month, String keep, boolean winnerHasContent, boolean loserHasContent) {}

    /**
     * @param winnerCustomerNumber the winner's own customer number in the same agreement,
     *                             when it has one (then the loser's is orphaned); {@code null}
     *                             when the mapping moves
     * @param identical            both sides map to the SAME customer number — nothing is
     *                             lost, the duplicate mapping is simply removed
     */
    public record EconomicsCustomerRef(String companyUuid, String companyName, int customerNumber,
                                       Integer winnerCustomerNumber, boolean identical) {}
}
