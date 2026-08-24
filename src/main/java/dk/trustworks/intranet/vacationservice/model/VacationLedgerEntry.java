package dk.trustworks.intranet.vacationservice.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import dk.trustworks.intranet.userservice.utils.LocalDateDeserializer;
import dk.trustworks.intranet.userservice.utils.LocalDateSerializer;
import dk.trustworks.intranet.vacationservice.model.enums.VacationEntrySource;
import dk.trustworks.intranet.vacationservice.model.enums.VacationEntryType;
import dk.trustworks.intranet.vacationservice.model.enums.VacationPoolType;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A vacation-day fact. {@code days} is positive for every entry type except
 * ADJUSTMENT, which is signed; the balance engine applies direction from the
 * entry type. {@code ferieaar} is the September start-year of the vacation
 * year (2025 = 1 Sep 2025 – 31 Aug 2026).
 */
@Data
@Entity
@Table(name = "vacation_ledger_entries")
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
public class VacationLedgerEntry extends PanacheEntityBase {

    @Id
    @EqualsAndHashCode.Include
    private String uuid;

    private String useruuid;

    private int ferieaar;

    @Enumerated(EnumType.STRING)
    private VacationPoolType pool;

    @Column(name = "entry_type")
    @Enumerated(EnumType.STRING)
    private VacationEntryType entryType;

    private double days;

    @Column(name = "effective_date")
    @JsonDeserialize(using = LocalDateDeserializer.class)
    @JsonSerialize(using = LocalDateSerializer.class)
    private LocalDate effectiveDate;

    @Enumerated(EnumType.STRING)
    private VacationEntrySource source;

    @Column(name = "source_ref")
    private String sourceRef;

    private String note;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by")
    private String createdBy;

    public static List<VacationLedgerEntry> findByUser(String useruuid) {
        return list("useruuid = ?1 ORDER BY effectiveDate, createdAt", useruuid);
    }

    public static List<VacationLedgerEntry> findByUsers(List<String> useruuids) {
        if (useruuids.isEmpty()) return List.of();
        return list("useruuid IN (?1) ORDER BY effectiveDate, createdAt", useruuids);
    }

    public static boolean existsBySourceRef(String sourceRef) {
        return count("sourceRef", sourceRef) > 0;
    }
}
