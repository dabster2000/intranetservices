package dk.trustworks.intranet.financeservice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
@Table(name = "posted_vouchers",
        uniqueConstraints = @UniqueConstraint(name = "uk_voucher_unique", columnNames = {"companyuuid", "accounting_year_url", "journalnumber", "vouchernumber"}))
public class PostedVoucher extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String expense_uuid; // nullable link back to expense when originating from app
    private String companyuuid;
    private String accounting_year_label;
    private String accounting_year_url;
    private Integer journalnumber;
    private Integer vouchernumber;
    private String account;
    private Double amount;
    private String currency;
    private LocalDate voucher_date;
    private String useruuid;
    private String attachment_s3_key;
    private String source; // e.g., "economics"

    @Column(name = "created_at")
    private OffsetDateTime createdAt;
}
