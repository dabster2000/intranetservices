package dk.trustworks.intranet.financeservice.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
@Table(name = "accounting_lump_sums")
public class AccountLumpSum extends PanacheEntityBase {
    @Id
    @EqualsAndHashCode.Include
    private String uuid;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "accounting_account_uuid")
    @JsonIgnore
    private AccountingAccount accountingAccount;
    @Column(name = "description")
    private String description;
    private double amount;
    @JsonDeserialize(using = LocalDateDeserializer.class)
    @JsonSerialize(using = LocalDateSerializer.class)
    @Column(name = "registered_date")
    private LocalDate registeredDate;
}
