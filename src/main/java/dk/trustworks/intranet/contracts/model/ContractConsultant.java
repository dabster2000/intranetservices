package dk.trustworks.intranet.contracts.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper=false)
@AllArgsConstructor
@Entity
@Table(name = "contract_consultants")
public class ContractConsultant extends PanacheEntityBase {

    @Id
    @EqualsAndHashCode.Include
    private String uuid;

    private String contractuuid;

    private String useruuid;

    private String name;

    @Column(name = "activefrom")
    @JsonSerialize(using = LocalDateSerializer.class)
    @JsonDeserialize(using = LocalDateDeserializer.class)
    private LocalDate activeFrom;

    @Column(name = "activeto")
    @JsonSerialize(using = LocalDateSerializer.class)
    @JsonDeserialize(using = LocalDateDeserializer.class)
    private LocalDate activeTo;

    private double rate;

    private double hours;

    /**
     * {@code pricing_model_definitions.code} — the commercial model for this line
     * (JK Team 2.0 WP5, D11). Lives on the line, never on the contract: Model 4 is a junior
     * on a senior's ordinary contract. Nullable; validated against the active codes.
     */
    @Column(name = "pricing_model_code", length = 32)
    private String pricingModelCode;

    /**
     * Zero-rate declaration (JK Team 2.0 WP4b, spec §4.4.1). A rate of 0 is legal only with
     * all three: the reason, the hard step-up deadline and the list rate the hours are worth.
     * {@code listRate} is display and reporting only — it never reaches {@code work_full.rate},
     * revenue or an invoice total. Mirrors {@code chk_consultant_rate_declared}.
     */
    @Column(name = "zero_rate_reason", length = 32)
    private String zeroRateReason;

    @Column(name = "rate_review_date")
    @JsonSerialize(using = LocalDateSerializer.class)
    @JsonDeserialize(using = LocalDateDeserializer.class)
    private LocalDate rateReviewDate;

    // No precision/scale here: Hibernate rejects a scale on a floating-point type
    // ("scale has no meaning for SQL floating point types") and fails the whole
    // SessionFactory build at boot. The column is DECIMAL(10,2) in V572; the mapping
    // follows rate/hours above and InvoiceItem.listRate, which are plain doubles.
    @Column(name = "list_rate")
    private Double listRate;

    /** A declared 0 kr line: the hours are given away on purpose and print as a no-charge invoice line. */
    public boolean isDeclaredZeroRate() {
        return rate == 0.0 && zeroRateReason != null && !zeroRateReason.isBlank();
    }

    @Column(name = "created")
    @JsonSerialize(using = LocalDateTimeSerializer.class)
    @JsonDeserialize(using = LocalDateTimeDeserializer.class)
    private LocalDateTime created;

    public ContractConsultant() {
        if(created==null) created = LocalDateTime.now();
    }

    private ContractConsultant(ContractConsultant cc, Contract c) {
        uuid = UUID.randomUUID().toString();
        contractuuid = c.getUuid();
        useruuid = cc.getUseruuid();
        rate = cc.getRate();
        hours = cc.getHours();
        pricingModelCode = cc.getPricingModelCode();
        zeroRateReason = cc.getZeroRateReason();
        rateReviewDate = cc.getRateReviewDate();
        listRate = cc.getListRate();
        if(created==null) created = LocalDateTime.now();
    }

    public static ContractConsultant createContractConsultant(ContractConsultant cc, Contract c) {
        return new ContractConsultant(cc, c);
    }

}
