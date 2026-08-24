package dk.trustworks.intranet.vacationservice.engine;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Answers "which company employed this person on date X" from a userstatus
 * timeline — and answers it with {@link Optional#empty()} when the timeline
 * cannot say, rather than inventing something.
 *
 * <p>That refusal is the whole point of the class. {@code User.getUserStatus}
 * looks like the obvious tool for this and is a trap: on a user loaded shallow
 * the status collection is empty, and instead of failing it returns a
 * synthetic {@code TERMINATED} status whose company is {@code null}. Every
 * caller then gets a confident wrong answer for every employee at once, with
 * no exception and no log line. Here there is no collection to be silently
 * empty and no fallback to fabricate — an empty timeline is an empty
 * {@code Optional}, which the import turns into a row HR must resolve.</p>
 */
public final class EmploymentCompanyResolver {

    /**
     * One userstatus row, reduced to the two fields this question needs.
     * {@code companyuuid} may be null: legacy rows exist that never carried one.
     */
    public record StatusFact(LocalDate statusdate, String companyuuid) {
    }

    private EmploymentCompanyResolver() {
    }

    /**
     * @return the company of the latest status effective at or before
     * {@code asOf}, or empty when there is no such status or it carries no
     * company.
     *
     * <p>Keyed on {@code statusdate} — the effective date — not on when the
     * row was written, so a transfer registered late but backdated to the day
     * it really happened resolves correctly, and a transfer pre-registered
     * with a future {@code statusdate} is correctly ignored.</p>
     *
     * <p>{@code uq_userstatus_user_date} makes at most one status per user per
     * day, so there is no tie to break.</p>
     */
    public static Optional<String> companyAt(List<StatusFact> timeline, LocalDate asOf) {
        if (timeline == null || timeline.isEmpty() || asOf == null) return Optional.empty();
        return timeline.stream()
                .filter(fact -> fact != null && fact.statusdate() != null && !fact.statusdate().isAfter(asOf))
                .max(Comparator.comparing(StatusFact::statusdate))
                .map(StatusFact::companyuuid)
                .filter(companyuuid -> !companyuuid.isBlank());
    }
}
