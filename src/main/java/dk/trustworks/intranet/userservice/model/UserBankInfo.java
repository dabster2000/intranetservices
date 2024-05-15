package dk.trustworks.intranet.userservice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@Entity
@Table(name = "user_bank_info")
public class UserBankInfo extends PanacheEntityBase {
    @Id
    @Size(max = 36)
    @Column(name = "uuid", nullable = false, length = 36)
    private String uuid;

    private String useruuid;

    private String fullname;

    @NotNull
    @Column(name = "active_date", nullable = false)
    private LocalDate activeDate;

    @Column(name = "regnr")
    private String regnr;

    @Column(name = "account_nr")
    private String accountNr;

    @Column(name = "bic_swift")
    private String bicSwift;

    @Column(name = "iban")
    private String iban;

    public UserBankInfo(String fullname, LocalDate activeDate, String regnr, String accountNr, String bicSwift, String iban) {
        this.uuid = UUID.randomUUID().toString();
        this.useruuid = null;
        this.fullname = fullname;
        this.activeDate = activeDate;
        this.regnr = regnr;
        this.accountNr = accountNr;
        this.bicSwift = bicSwift;
        this.iban = iban;
    }

    public static List<UserBankInfo> findByUseruuid(String useruuid) {
        return UserBankInfo.find("useruuid", useruuid).list();
    }
}