package dk.trustworks.intranet.userservice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.ColumnDefault;

import java.time.LocalDate;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
@Entity
@Table(name = "transportation_registration")
public class TransportationRegistration extends PanacheEntityBase {
    @Id
    @Size(max = 36)
    @Column(name = "uuid", nullable = false, length = 36)
    @EqualsAndHashCode.Include
    private String uuid;

    @NotNull
    @Column(name = "useruuid", nullable = false)
    private String useruuid;

    @NotNull
    @Column(name = "date", nullable = false)
    private LocalDate date;

    @Size(max = 200)
    @Column(name = "purpose", length = 200)
    private String purpose;

    @Size(max = 200)
    @NotNull
    @Column(name = "destination", nullable = false, length = 200)
    private String destination;

    @NotNull
    @ColumnDefault("0")
    @Column(name = "kilometers", nullable = false)
    private Integer kilometers;

    @NotNull
    @Column(name = "paid", nullable = false)
    private Boolean paid = false;

    public static List<TransportationRegistration> findByUseruuid(String useruuid) {
        return TransportationRegistration.find("useruuid", useruuid).list();
    }

    public static List<TransportationRegistration> findByUseruuidAndUnpaid(String useruuid) {
        return TransportationRegistration.find("useruuid = ?1 and paid = ?2", useruuid, false).list();
    }

    public @NotNull Boolean isPaid() {
        return paid;
    }
}