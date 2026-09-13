package dk.trustworks.intranet.aggregates.crm.trustlink.model;

import dk.trustworks.intranet.aggregates.crm.trustlink.model.enums.AliasSource;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One TrustLink company name that belongs to an Intra client (CRM spec §3.5).
 *
 * <h2>Why a client needs several names</h2>
 * TrustLink fragments an organisation. {@code Novo Nordisk} carries 203 tier-5
 * connections; {@code Novo Nordisk A/S} carries 2. The Intra client is called
 * {@code NOVO NORDISK A/S}. A one-name mapping would look like it worked and quietly lose
 * 203 relationships — the exact failure mode this table exists to prevent. A client points
 * at as many TrustLink names as it needs, and the search asks for all of them at once.
 *
 * <p>The TrustLink search is exact and case-sensitive ({@code "novo nordisk"} returns
 * nothing), so {@link #companyName} is stored verbatim as TrustLink spells it, never
 * normalised. Normalisation exists only to decide whether a candidate is worth adding.
 *
 * <h2>AUTO and MANUAL, and the line between them</h2>
 * {@link AliasSource#AUTO} rows are the nightly seeder's guess: the client's own name, plus
 * typeahead candidates whose normalised, legal-form-stripped name equals the client's.
 * {@link AliasSource#MANUAL} rows are something a person asserted, and <b>the seeder never
 * touches a MANUAL row in either direction</b>. That is what makes the pair useful:
 * <ul>
 *   <li>a MANUAL row with {@link #enabled} true adds a name the matcher could never have
 *       guessed (a former name, a subsidiary, a spelling only TrustLink uses);</li>
 *   <li>a MANUAL row with {@link #enabled} false <em>suppresses</em> a name — including one
 *       the seeder would otherwise add back tonight, which is the only way a wrong AUTO
 *       guess can be made to stay gone.</li>
 * </ul>
 *
 * <p>{@code (client_uuid, company_name)} is unique. The name is deliberately NOT unique
 * across clients: two clients may legitimately both claim a TrustLink company, and the sync
 * writes a connection row for each of them.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "trustlink_company_alias")
public class TrustLinkCompanyAlias extends PanacheEntityBase {

    @Id
    @Column(name = "uuid", length = 36)
    private String uuid;

    @Column(name = "client_uuid", length = 36, nullable = false)
    private String clientUuid;

    /** Verbatim TrustLink spelling. The search is case-sensitive; do not fold this. */
    @Column(name = "company_name", length = 255, nullable = false)
    private String companyName;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 10, nullable = false)
    private AliasSource source;

    /** False means "do not query this name" — and, on a MANUAL row, "never seed it again". */
    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** The person who added it. Null for AUTO rows: nobody asserted them. */
    @Column(name = "created_by", length = 36)
    private String createdBy;
}
