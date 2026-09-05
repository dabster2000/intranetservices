package dk.trustworks.intranet.aggregates.availability.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * One declared day (V566): the hours a person expects to work on {@code day}.
 *
 * <p>Read by the availability resolver only for the declaring population — see
 * {@code DeclaredAvailabilityPolicy}. Never a REST body: the resource takes
 * {@code DeclaredAvailabilityUpsertRequest} and the service maps it here.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "user_declared_availability")
public class UserDeclaredAvailability extends PanacheEntityBase {

    /** Who wrote the row. {@code SYSTEM} is only ever the copy-forward endpoint or a seed. */
    public enum Source { SELF, TEAMLEAD, SYSTEM }

    @Id
    @Column(name = "uuid", length = 36, nullable = false)
    private String uuid;

    @Column(name = "useruuid", length = 36, nullable = false)
    private String useruuid;

    @Column(name = "day", nullable = false)
    private LocalDate day;

    @Column(name = "hours", nullable = false, precision = 4, scale = 2)
    private BigDecimal hours;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 16, nullable = false)
    private Source source;

    @Column(name = "note", length = 255)
    private String note;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "updated_by", length = 36)
    private String updatedBy;

    /** Rows for one person in {@code [from, to]}, oldest first. */
    public static List<UserDeclaredAvailability> findForRange(String useruuid, LocalDate from, LocalDate to) {
        return list("useruuid = ?1 and day >= ?2 and day <= ?3 order by day", useruuid, from, to);
    }

    /** {@link #findForRange} keyed by day — the shape the resolver batch-loads. */
    public static Map<LocalDate, UserDeclaredAvailability> mapForRange(String useruuid, LocalDate from, LocalDate to) {
        return findForRange(useruuid, from, to).stream()
                .collect(Collectors.toMap(UserDeclaredAvailability::getDay, Function.identity(), (a, b) -> b));
    }

    public static Optional<UserDeclaredAvailability> findForDay(String useruuid, LocalDate day) {
        return UserDeclaredAvailability.<UserDeclaredAvailability>find("useruuid = ?1 and day = ?2", useruuid, day)
                .firstResultOptional();
    }

    /** The latest declared day on or after {@code from}, or empty when nothing lies ahead. */
    public static Optional<LocalDate> findHorizon(String useruuid, LocalDate from) {
        return UserDeclaredAvailability.<UserDeclaredAvailability>find(
                        "useruuid = ?1 and day >= ?2 order by day desc", useruuid, from)
                .firstResultOptional()
                .map(UserDeclaredAvailability::getDay);
    }
}
