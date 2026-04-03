package dk.trustworks.intranet.utils.dto.nextsign;

import java.util.List;

/**
 * Rich DTO for the case detail endpoint response.
 * Maps the full NextSign case to a frontend-friendly structure.
 * Used by GET /utils/signing/cases/{caseKey}/detail
 */
public record NextSignCaseDetailDTO(
    String id,
    String nextSignKey,
    String referenceId,
    String title,
    String type,
    String folder,
    String userEmail,
    String createdAt,
    String updatedAt,
    Settings settings,
    List<String> signingSchemas,
    List<Recipient> recipients,
    List<Document> documents,
    List<Document> signedDocuments
) {

    public record Settings(
        Reminders reminders,
        Deletion deletion,
        Availability availability
    ) {}

    public record Reminders(boolean send, int amount, int daysBetween) {}
    public record Deletion(boolean autoDelete, int days) {}
    public record Availability(boolean unlimited, int days, boolean isExpired) {}

    public record Recipient(
        String name,
        String email,
        boolean signing,
        String signed,
        String signedDateAndTime,
        int order,
        int group,
        boolean needsCpr,
        IdentityVerification identity,
        List<AuditLogEntry> log
    ) {}

    public record IdentityVerification(
        String name,
        String firstName,
        String lastName,
        String dateOfBirth,
        Boolean cprIsMatch,
        boolean confirmed
    ) {}

    public record AuditLogEntry(
        String type,
        String value,
        String createdAt
    ) {}

    public record Document(
        String name,
        String documentId
    ) {}

    /**
     * Maps from the raw NextSign API response to this clean DTO.
     */
    public static NextSignCaseDetailDTO fromCaseDetails(GetCaseStatusResponse.CaseDetails c) {
        if (c == null) return null;

        var settings = c.settings() != null ? new Settings(
            c.settings().reminders() != null
                ? new Reminders(c.settings().reminders().send(), c.settings().reminders().amount(), c.settings().reminders().daysBetween())
                : new Reminders(false, 0, 0),
            c.settings().deletion() != null
                ? new Deletion(c.settings().deletion().autoDelete(), c.settings().deletion().days())
                : new Deletion(false, 0),
            c.settings().availability() != null
                ? new Availability(c.settings().availability().unlimited(), c.settings().availability().days(), c.settings().availability().isExpired())
                : new Availability(false, 0, false)
        ) : new Settings(new Reminders(false, 0, 0), new Deletion(false, 0), new Availability(false, 0, false));

        var recipients = c.recipients() != null
            ? c.recipients().stream().map(r -> {
                IdentityVerification identity = null;
                if (r.signer() != null && r.signer().identity() != null && Boolean.TRUE.equals(r.signer().identity().confirmed())) {
                    var verifiedId = r.signer().identity().identity();
                    identity = new IdentityVerification(
                        verifiedId != null ? verifiedId.name() : null,
                        verifiedId != null ? verifiedId.firstName() : null,
                        verifiedId != null ? verifiedId.lastName() : null,
                        verifiedId != null ? verifiedId.dateOfBirth() : null,
                        r.signer().identity().cprIsMatch(),
                        true
                    );
                }
                var logEntries = r.log() != null
                    ? r.log().stream().map(l -> new AuditLogEntry(l.type(), l.value(), l.createdAt())).toList()
                    : List.<AuditLogEntry>of();

                return new Recipient(
                    r.name(), r.email(), r.signing(), r.status(), r.signedAt(),
                    r.order(), r.group(), r.needsCpr(), identity, logEntries
                );
            }).toList()
            : List.<Recipient>of();

        var documents = c.documents() != null
            ? c.documents().stream().map(d -> new Document(d.name(), d.documentId())).toList()
            : List.<Document>of();

        var signedDocuments = c.signedDocuments() != null
            ? c.signedDocuments().stream().map(d -> new Document(d.name(), d.documentId())).toList()
            : List.<Document>of();

        return new NextSignCaseDetailDTO(
            c.id(), c.nextSignKey(), c.referenceId(), c.title(), c.type(), c.folder(),
            c.userEmail(), c.createdAt(), c.updatedAt(),
            settings, c.signingSchemas() != null ? c.signingSchemas() : List.of(),
            recipients, documents, signedDocuments
        );
    }
}
