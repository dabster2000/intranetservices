package dk.trustworks.intranet.vacationservice.model;

import dk.trustworks.intranet.vacationservice.model.enums.VacationImportRowStatus;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * One parsed CSV line, kept verbatim in {@code rawJson} for audit — including
 * the DKK columns the system deliberately never interprets (days only, by
 * decision).
 */
@Data
@Entity
@Table(name = "vacation_import_rows")
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
public class VacationImportRow extends PanacheEntityBase {

    @Id
    @EqualsAndHashCode.Include
    private String uuid;

    @Column(name = "batch_uuid")
    private String batchUuid;

    @Column(name = "line_no")
    private int lineNo;

    @Column(name = "danlon_name")
    private String danlonName;

    @Column(name = "raw_json")
    private String rawJson;

    private String useruuid;

    @Column(name = "match_status")
    @Enumerated(EnumType.STRING)
    private VacationImportRowStatus matchStatus;

    public static List<VacationImportRow> findByBatch(String batchUuid) {
        return list("batchUuid = ?1 ORDER BY lineNo", batchUuid);
    }
}
