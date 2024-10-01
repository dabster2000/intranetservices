package dk.trustworks.intranet.userservice.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
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
import java.time.LocalDateTime;
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

    @Column(name = "paid_out")
    @JsonSerialize(using = LocalDateTimeSerializer.class)
    @JsonDeserialize(using = LocalDateTimeDeserializer.class)
    private LocalDateTime paidOut;

    public static List<TransportationRegistration> findByUseruuid(String useruuid) {
        return TransportationRegistration.find("useruuid", useruuid).list();
    }

    public static List<TransportationRegistration> findByUseruuidAndUnpaidAndMonth(String useruuid, LocalDate month) {
        return TransportationRegistration.find("useruuid = ?1 and (paidOut is null OR YEAR(paidOut) = YEAR(?2) AND MONTH(paidOut) = MONTH(?2))", useruuid, month).list();
    }

    public static List<TransportationRegistration> findByUseruuidAndPaidOutMonth(String useruuid, LocalDate month) {
        return TransportationRegistration.find("useruuid = ?1 and (YEAR(paidOut) = YEAR(?2) AND MONTH(paidOut) = MONTH(?2))", useruuid, month).list();
    }

    @JsonIgnore
    public boolean isPaidOut() {
        return paidOut != null;
    }
}