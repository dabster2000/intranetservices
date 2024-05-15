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

import java.time.LocalDate;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
@Entity
@Table(name = "user_pension")
public class UserPension extends PanacheEntityBase {
    @Id
    @Size(max = 36)
    @Column(name = "uuid", nullable = false, length = 36)
    @EqualsAndHashCode.Include
    private String uuid;

    private String useruuid;

    @NotNull
    @Column(name = "active_date", nullable = false)
    private LocalDate activeDate;

    @Column(name = "pension_own")
    private Double ownPensionPayment;

    @Column(name = "pension_company")
    private Double companyPensionPayment;

    public static List<UserPension> findByUser(String useruuid) {
        return UserPension.find("useruuid", useruuid).list();
    }
}