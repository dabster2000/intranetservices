package dk.trustworks.intranet.vacationservice.engine;

import dk.trustworks.intranet.vacationservice.engine.VacationBalanceEngine.PolicyRate;
import dk.trustworks.intranet.vacationservice.model.enums.VacationEntryType;
import dk.trustworks.intranet.vacationservice.model.enums.VacationImportRowStatus;
import dk.trustworks.intranet.vacationservice.model.enums.VacationPoolType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static dk.trustworks.intranet.vacationservice.engine.VacationRules.round2;
import static dk.trustworks.intranet.vacationservice.engine.VacationRules.startOf;

/**
 * Turns the reviewed rows of one Danløn feriepengeforpligtelse batch into the
 * exact set of IMPORT_BASELINE entries to post: first one figure pair per
 * (user, ferieår), then the split into the two pools.
 *
 * <p>Danløn emits one line per employment record, so a person with two
 * employment records — a rehire, a change of bogføringsgruppe, an old record
 * still carrying a former name — appears twice in the same file. Both lines
 * are parts of one person's entitlement, so their days belong together.</p>
 *
 * <p>The order matters. Earned days split proportionally, which is linear and
 * would survive being done per line. Used days do not: they are charged
 * ferie-first and clamped against that line's own ferie capacity, so splitting
 * each line on its own spills leave into feriefridage that the person's
 * combined ferie could have covered. Sum first, split once.</p>
 *
 * <p>Summing is also what keeps the writer inside
 * {@code uq_vacation_ledger_entries_dedup}: every baseline in a batch shares
 * the batch's as-of date and uuid, so two lines for one person otherwise
 * produce byte-identical unique keys and take the whole apply down at
 * commit — which is precisely how the Trustworks Technology file failed.</p>
 */
public final class ImportBaselinePlanner {

    /** The Danløn day figures for one ferieår, as the file states them. */
    public record Figures(double earnedDays, double usedDays) {
    }

    /** One reviewed import row, as the planner needs to see it. */
    public record Row(String useruuid, VacationImportRowStatus matchStatus, Map<Integer, Figures> years) {
    }

    /** One (user, ferieår) of the batch, with the figures of every line merged. */
    public record Baseline(String useruuid, int ferieaar, double earnedDays, double usedDays) {
    }

    /** One ledger entry to post; days is already rounded to two decimals. */
    public record BaselineEntry(String useruuid, int ferieaar, VacationPoolType pool,
                                VacationEntryType type, double days) {
    }

    private ImportBaselinePlanner() {
    }

    /**
     * Merges the batch's rows down to one figure pair per (user, ferieår).
     *
     * <p>Only AUTO and MANUAL contribute — the
     * {@link VacationImportRowStatus.Bucket#APPLIES} bucket — and the test is
     * an allow-list on purpose. It used to be a deny-list on IGNORED, which
     * meant every status added afterwards was summed into a baseline by
     * default; that is exactly how an OTHER_COMPANY line would have kept
     * overwriting a transferred employee's correct figures. Both flavours of
     * "applies" count equally: two lines pointed at one person by hand must
     * merge exactly as two lines the matcher resolved on its own.</p>
     *
     * @return ordered by the user's first appearance in the file and then by
     * ferieår ascending, so the posted entries — and the count in the apply
     * log — are identical on every run. The ferieår set is the union across
     * the merged lines, because a second employment record starting mid-year
     * legitimately carries fewer years than the first.
     */
    public static List<Baseline> aggregate(List<Row> rows) {
        Map<String, Map<Integer, Figures>> byUser = new LinkedHashMap<>();
        for (Row row : rows) {
            if (row == null || row.matchStatus() == null) continue;
            if (row.matchStatus().bucket() != VacationImportRowStatus.Bucket.APPLIES) continue;
            if (row.useruuid() == null || row.useruuid().isBlank()) continue;
            if (row.years() == null || row.years().isEmpty()) continue;
            Map<Integer, Figures> years = byUser.computeIfAbsent(row.useruuid(), k -> new TreeMap<>());
            row.years().forEach((ferieaar, figures) -> years.merge(ferieaar, figures,
                    (a, b) -> new Figures(a.earnedDays() + b.earnedDays(), a.usedDays() + b.usedDays())));
        }

        List<Baseline> baselines = new ArrayList<>();
        byUser.forEach((useruuid, years) -> years.forEach((ferieaar, figures) -> baselines.add(
                // Round the sum before it reaches the split: adding N doubles in
                // file order can land a hair off a .xx5 boundary, and which pool a
                // day ends up in must not depend on the order Danløn emitted.
                new Baseline(useruuid, ferieaar, round2(figures.earnedDays()), round2(figures.usedDays())))));
        return baselines;
    }

    /**
     * The full posting plan: four entries per (user, ferieår) — earned and
     * used, ferie and feriefridage — in a stable order.
     */
    public static List<BaselineEntry> plan(List<Row> rows, List<PolicyRate> policies) {
        List<BaselineEntry> entries = new ArrayList<>();
        for (Baseline baseline : aggregate(rows)) {
            entries.addAll(split(baseline, policies));
        }
        return entries;
    }

    /**
     * Splits the combined Danløn figures into the two pools: earned days
     * proportionally by the policy rates at the ferieår's start (2.08 : 0.42
     * by default), used days ferie-first — matching the engine's spend order —
     * with any excess charged to ferie as overdraft.
     *
     * <p>Aggregation can never create an overdraft: {@code Σused > Σearned}
     * requires at least one line that was already over-drawn on its own. It
     * can only cancel one, which is the right reading of two employment
     * records held by one person.</p>
     */
    static List<BaselineEntry> split(Baseline baseline, List<PolicyRate> policies) {
        int ferieaar = baseline.ferieaar();
        double ferieRate = VacationBalanceEngine.rateFor(policies, VacationPoolType.FERIE, startOf(ferieaar));
        double ffRate = VacationBalanceEngine.rateFor(policies, VacationPoolType.FERIEFRIDAGE, startOf(ferieaar));
        double totalRate = ferieRate + ffRate;
        double ferieShare = totalRate <= 0 ? 1.0 : ferieRate / totalRate;

        double earnedFerie = round2(baseline.earnedDays() * ferieShare);
        double earnedFf = round2(baseline.earnedDays() - earnedFerie);
        double usedFerie = Math.min(baseline.usedDays(), earnedFerie);
        double usedFf = Math.min(earnedFf, round2(baseline.usedDays() - usedFerie));
        double excess = round2(baseline.usedDays() - usedFerie - usedFf);
        if (excess > 0) usedFerie = round2(usedFerie + excess);

        String useruuid = baseline.useruuid();
        return List.of(
                new BaselineEntry(useruuid, ferieaar, VacationPoolType.FERIE, VacationEntryType.IMPORT_BASELINE_EARNED, round2(earnedFerie)),
                new BaselineEntry(useruuid, ferieaar, VacationPoolType.FERIE, VacationEntryType.IMPORT_BASELINE_USED, round2(usedFerie)),
                new BaselineEntry(useruuid, ferieaar, VacationPoolType.FERIEFRIDAGE, VacationEntryType.IMPORT_BASELINE_EARNED, round2(earnedFf)),
                new BaselineEntry(useruuid, ferieaar, VacationPoolType.FERIEFRIDAGE, VacationEntryType.IMPORT_BASELINE_USED, round2(usedFf)));
    }
}
