package dk.trustworks.intranet.expenseservice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "merchant_allow_list")
public class MerchantAllowList extends PanacheEntityBase {

    @Id
    public String uuid = UUID.randomUUID().toString();

    @Column(name = "rule_id", nullable = false)
    public String ruleId;

    @Column(name = "merchant_name_pattern", nullable = false)
    public String merchantNamePattern;

    @Column(name = "match_kind", nullable = false)
    public String matchKind = "CONTAINS";

    @Column(name = "notes")
    public String notes;

    @Column(name = "added_by_uuid")
    public String addedByUuid;

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt = LocalDateTime.now();
}
