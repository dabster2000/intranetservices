package dk.trustworks.intranet.aggregates.crm.signal.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A Trustworks colleague one capture named, besides its author (CRM spec §3.4/§3.7).
 *
 * <p>The line <i>"jeg har snakket med Dorte som jeg har mødt i @KOMBIT sammen Tobias
 * Kjølsen"</i> says TWO of us know Dorte. Before V593 there was nowhere to put the
 * second name — the extraction prompt discarded Trustworks colleagues outright — so the
 * relationship graph could only ever draw one edge, from the author. This table is the
 * second name.
 *
 * <p><b>Keyed by the signal row, not the capture.</b> A capture naming three accounts
 * writes three {@link AccountSignal} rows and three colleague rows per colleague. That
 * duplication is the same trade the N-rows design already makes for the line itself, and
 * it buys the thing that matters: {@code AccountRelationshipService}'s existing
 * {@code where client_uuid = ?} query joins straight onto this table with no capture-level
 * indirection.
 *
 * <p><b>Never the author.</b> {@link AccountSignal#getAuthorUuid()} already records who
 * heard it. A row here for the author too would double every KNOWS edge in the graph, so
 * {@code AccountSignalService} filters the author out before writing; the unique key
 * {@code (signal_uuid, user_uuid)} makes a duplicate impossible in any case.
 *
 * <p><b>Not personal data of a third party.</b> Only internal user uuids live here. The
 * name this relates to is {@link AccountSignal#getPersonName()} on the parent row and
 * carries that row's retention — the FK cascades on delete so the agreed 24-month purge
 * (still not built) cannot leave orphans behind.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "account_signal_colleague")
public class AccountSignalColleague extends PanacheEntityBase {

    @Id
    @Column(name = "uuid", length = 36)
    private String uuid;

    /** The {@link AccountSignal} row this belongs to. Real FK, {@code ON DELETE CASCADE}. */
    @Column(name = "signal_uuid", length = 36, nullable = false)
    private String signalUuid;

    /** The colleague the line named. Soft reference to {@code user.uuid}. */
    @Column(name = "user_uuid", length = 36, nullable = false)
    private String userUuid;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** Every colleague named on one signal row. */
    public static List<AccountSignalColleague> listForSignal(String signalUuid) {
        return list("signalUuid", signalUuid);
    }

    /**
     * Every colleague row for a set of signals, in one query.
     *
     * <p>The account page reads a whole account's signals at once; resolving colleagues
     * one signal at a time would be the N+1 the read surfaces exist to avoid.
     */
    public static List<AccountSignalColleague> listForSignals(List<String> signalUuids) {
        if (signalUuids == null || signalUuids.isEmpty()) {
            return List.of();
        }
        return list("signalUuid in ?1", signalUuids);
    }
}
