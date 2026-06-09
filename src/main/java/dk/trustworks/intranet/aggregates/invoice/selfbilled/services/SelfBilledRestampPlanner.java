package dk.trustworks.intranet.aggregates.invoice.selfbilled.services;

import dk.trustworks.intranet.aggregates.invoice.selfbilled.dto.RestampDecision;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure matcher for the migration's re-stamp phase (Decision D6a, the F2 fix). Booking month
 * != work period, so each existing internal is matched to the self-billing it covers by
 * (client, consultant, amount within tolerance). Exactly one target -> RESTAMP; zero -> UNMATCHED
 * (report, never guess, never reverse); many -> AMBIGUOUS. Already-correct stamp -> NO_CHANGE.
 * Carrying clientUuid removes the fragile consultant-only join the apply step would otherwise need.
 */
public final class SelfBilledRestampPlanner {

    private SelfBilledRestampPlanner() {}

    private static final BigDecimal TOLERANCE = BigDecimal.ONE; // 1 kr

    /** An existing cross-company settlement internal. clientUuid derived from its phantom ref (Task G1). */
    public record Internal(String uuid, String clientUuid, String debtorCompanyUuid, String consultantUuid,
                           BigDecimal amount, Integer settlementYear, Integer settlementMonth) {}

    /** A voucher-netted self-billed target (client, consultant, work period, positive amount). */
    public record Target(String clientUuid, String debtorCompanyUuid, String consultantUuid,
                         int workYear, int workMonth, BigDecimal amount) {}

    public static List<RestampDecision> plan(List<Internal> internals, List<Target> targets) {
        List<RestampDecision> out = new ArrayList<>(internals.size());
        for (Internal in : internals) {
            List<Target> matches = new ArrayList<>();
            for (Target t : targets) {
                if (!t.clientUuid().equals(in.clientUuid())) continue;
                if (!t.consultantUuid().equals(in.consultantUuid())) continue;
                if (in.amount().subtract(t.amount()).abs().compareTo(TOLERANCE) <= 0) matches.add(t);
            }
            if (matches.isEmpty()) {
                out.add(new RestampDecision(in.uuid(), RestampDecision.Outcome.UNMATCHED,
                        in.clientUuid(), in.debtorCompanyUuid(), 0, 0,
                        "no self-billed target within tolerance (arrears or non-self-billed work)"));
            } else if (matches.size() > 1) {
                out.add(new RestampDecision(in.uuid(), RestampDecision.Outcome.AMBIGUOUS,
                        in.clientUuid(), in.debtorCompanyUuid(), 0, 0,
                        matches.size() + " targets match amount; manual review"));
            } else {
                Target t = matches.get(0);
                boolean already = in.settlementYear() != null && in.settlementMonth() != null
                        && in.settlementYear() == t.workYear() && in.settlementMonth() == t.workMonth();
                out.add(new RestampDecision(in.uuid(),
                        already ? RestampDecision.Outcome.NO_CHANGE : RestampDecision.Outcome.RESTAMP,
                        t.clientUuid(), t.debtorCompanyUuid(), t.workYear(), t.workMonth(),
                        already ? "already stamped" : "unique match"));
            }
        }
        return out;
    }
}
