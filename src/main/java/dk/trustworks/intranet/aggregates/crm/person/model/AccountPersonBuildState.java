package dk.trustworks.intranet.aggregates.crm.person.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * What the registry rebuild last did. One row, id 1, seeded by V604.
 *
 * <h2>Why a table and not just a log line</h2>
 * The same reason {@code trustlink_sync_state} exists: the rebuild is invisible when it works
 * and equally invisible when it stops. People simply stop getting fresher, and a stale
 * registry looks exactly like a quiet account. This row answers "is it still running, and did
 * the last run get anywhere" from the database, without CloudWatch and without waiting until
 * 03:00.
 *
 * <h2>NEVER-RUN IS TWO STATES, NOT ONE</h2>
 * {@link #hasNeverRun(AccountPersonBuildState)} treats <b>both</b> a missing row and a row
 * whose {@link #lastRunAt} is null as never-run, and every reader must. This is the TrustLink
 * trap repeated exactly: V604 seeds one row, but a database restored from before V604, or a
 * seed that lost a race, leaves no row at all, and code that only checks {@code last_run_at}
 * throws a {@code NullPointerException} on the one machine where it mattered instead of
 * rebuilding. The recovery cron exists precisely for the case where this row says never-run,
 * so getting this wrong is getting the recovery wrong.
 *
 * <h2>Why last_run_at and last_success_at are separate</h2>
 * A rebuild that failed every client still <i>ran</i>. A recovery job that cannot tell those
 * apart re-runs a failing rebuild hourly, for ever. {@link #lastRunAt} moves on every pass;
 * {@link #lastSuccessAt} moves only on a pass with zero failed clients. The widening gap
 * between them is the alarm.
 *
 * <h2>Counts only</h2>
 * No client uuid, no name, no address ever lands here, and {@link #failureCode} carries a
 * CODE and never an upstream message or body.
 *
 * <p>Configuration rather than personal data, but still excluded from
 * {@code sp_sync_prod_to_staging} (V608) along with the two data tables — a staging run
 * rebuilding from production's counters would report a success it never had.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "account_person_build_state")
public class AccountPersonBuildState extends PanacheEntityBase {

    /** Always 1. The table holds exactly one row; V604 seeds it with {@code INSERT IGNORE}. */
    public static final byte SINGLETON_ID = 1;

    @Id
    @Column(name = "id")
    private byte id;

    /** Null means never run — and so does no row at all. See the class javadoc. */
    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    /** Only set when the pass finished with zero failed clients. */
    @Column(name = "last_success_at")
    private LocalDateTime lastSuccessAt;

    @Column(name = "clients_built", nullable = false)
    private int clientsBuilt;

    @Column(name = "people_upserted", nullable = false)
    private int peopleUpserted;

    @Column(name = "identities_upserted", nullable = false)
    private int identitiesUpserted;

    /** Clients whose own rebuild threw. Each costs that client only; the pass continues. */
    @Column(name = "failures", nullable = false)
    private int failures;

    /** A CODE only, never an upstream body. */
    @Column(name = "failure_code", length = 40)
    private String failureCode;

    /**
     * Has the registry ever been built?
     *
     * <p>Static and null-tolerant so the only correct reading of the two never-run states is
     * also the easiest one to write. {@code state == null} is a database that never got the
     * seed; {@code lastRunAt == null} is the seeded row.
     */
    public static boolean hasNeverRun(AccountPersonBuildState state) {
        return state == null || state.getLastRunAt() == null;
    }

    /**
     * The singleton, or null when the seed is missing.
     *
     * <p>Deliberately returns null rather than manufacturing an empty row: a caller that
     * wants to <i>write</i> has to notice the absence and create the row with
     * {@link #SINGLETON_ID}, and a caller that only wants to read must go through
     * {@link #hasNeverRun(AccountPersonBuildState)}.
     */
    public static AccountPersonBuildState singleton() {
        return findById(SINGLETON_ID);
    }
}
