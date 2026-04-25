package dk.trustworks.intranet.recruitmentservice.domain.entities;

import dk.trustworks.intranet.recruitmentservice.domain.enums.CandidateState;
import dk.trustworks.intranet.recruitmentservice.domain.enums.Practice;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Entity
@Table(name = "recruitment_candidate")
public class Candidate extends PanacheEntityBase {

    @Id
    @Column(length = 36)
    public String uuid;

    @Column(name = "first_name", length = 120)
    public String firstName;

    @Column(name = "last_name", length = 120)
    public String lastName;

    @Column(length = 255)
    public String email;

    @Column(length = 40)
    public String phone;

    @Column(name = "first_contact_source", length = 40)
    public String firstContactSource;

    @Column(name = "current_company", length = 255)
    public String currentCompany;

    @Enumerated(EnumType.STRING)
    @Column(name = "desired_practice", length = 10)
    public Practice desiredPractice;

    @Column(name = "desired_career_level_uuid", length = 36)
    public String desiredCareerLevelUuid;

    @Column(name = "notice_period_days")
    public Integer noticePeriodDays;

    @Column(name = "salary_expectation")
    public Integer salaryExpectation;

    @Column(name = "salary_currency", length = 3)
    public String salaryCurrency;

    @Column(name = "location_preference", length = 120)
    public String locationPreference;

    @Column(name = "linkedin_url", length = 512)
    public String linkedinUrl;

    @Column(columnDefinition = "JSON")
    public String tags;  // JSON-as-string for Slice 1; structured DTO arrives in Slice 2

    @Column(name = "last_contact_at")
    public LocalDateTime lastContactAt;

    @Column(name = "consent_status", length = 40, nullable = false)
    public String consentStatus = "PENDING";

    @Column(name = "consent_given_at")
    public LocalDateTime consentGivenAt;

    @Column(name = "consent_expires_at")
    public LocalDateTime consentExpiresAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 40, nullable = false)
    public CandidateState state = CandidateState.NEW;

    @Column(name = "owner_user_uuid", length = 36)
    public String ownerUserUuid;

    @Column(name = "sharepoint_folder_url", length = 1024)
    public String sharepointFolderUrl;

    @Column(name = "added_to_pool_at")
    public LocalDateTime addedToPoolAt;

    @Column(name = "retention_extended_to")
    public LocalDateTime retentionExtendedTo;

    @Column(name = "retention_extension_reason", columnDefinition = "TEXT")
    public String retentionExtensionReason;

    @Column(name = "anonymized_at")
    public LocalDateTime anonymizedAt;

    @Column(name = "converted_user_uuid", length = 36)
    public String convertedUserUuid;

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    public LocalDateTime updatedAt = LocalDateTime.now();

    public Candidate() {}

    public static Candidate withFreshUuid() {
        Candidate c = new Candidate();
        c.uuid = UUID.randomUUID().toString();
        return c;
    }
}
