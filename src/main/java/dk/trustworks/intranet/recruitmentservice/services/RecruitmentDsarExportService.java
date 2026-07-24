package dk.trustworks.intranet.recruitmentservice.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.fileservice.model.File;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplicationAnswer;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentConsent;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentInterview;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPendingEmail;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentReferral;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentScorecard;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * DSAR export (ATS P19, spec §5.5): everything the controller holds about
 * one candidate as a ZIP of machine-readable JSON and a human-readable
 * PDF, for the DPO to review and forward within the 30-day Art. 15
 * window. The export is STREAMED to the DPO and never stored — persisting
 * a second copy of all personal data would itself be a retention
 * liability; a re-export is cheap.
 *
 * <h3>What goes in</h3>
 * The candidate row, consents, applications with their form answers,
 * interviews, scorecard assessments, the referral that introduced them,
 * the full event timeline INCLUDING {@code pii} sections (notes, email
 * bodies, AI briefs — it is all the candidate's data), the queued-email
 * snapshots and the stored-document list (metadata; the documents
 * themselves are the candidate's own uploads, listed not embedded).
 *
 * <h3>What stays out (third-party data)</h3>
 * Employee identities: interviewer uuids on interviews/scorecards, event
 * {@code actor_uuid}s, the referrer's identity (Art. 15(1)(g) is
 * satisfied by the source CATEGORY — "via referral" — which the candidate
 * row's {@code source} field carries). Recorded as a P19 scope decision
 * in findings.
 */
@JBossLog
@ApplicationScoped
public class RecruitmentDsarExportService {

    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    private static final com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>> JSON_OBJECT =
            new com.fasterxml.jackson.core.type.TypeReference<>() {
            };

    @Inject
    ObjectMapper objectMapper;

    @Inject
    RecruitmentS3StorageService s3StorageService;

    /** The finished archive plus the counts the bookkeeping event records. */
    public record DsarExport(byte[] zipBytes, String filename,
                             int eventCount, int documentCount) {
    }

    /**
     * Build the ZIP for one candidate. Read-only; the caller appends the
     * {@code DSAR_EXPORTED} event.
     */
    @Transactional
    public DsarExport export(RecruitmentCandidate candidate) {
        String candidateUuid = candidate.getUuid();

        List<RecruitmentConsent> consents =
                RecruitmentConsent.list("candidateUuid = ?1 ORDER BY createdAt", candidateUuid);
        List<RecruitmentApplication> applications =
                RecruitmentApplication.list("candidateUuid = ?1 ORDER BY createdAt", candidateUuid);
        List<RecruitmentApplicationAnswer> answers = RecruitmentApplicationAnswer.list(
                "candidateUuid = ?1 or applicationUuid in ?2",
                candidateUuid,
                applications.isEmpty()
                        ? List.of("-") // IN () is invalid JPQL; no application matches "-"
                        : applications.stream().map(RecruitmentApplication::getUuid).toList());
        List<RecruitmentInterview> interviews = applications.isEmpty() ? List.of()
                : RecruitmentInterview.list("applicationUuid in ?1",
                        applications.stream().map(RecruitmentApplication::getUuid).toList());
        List<RecruitmentScorecard> scorecards = interviews.isEmpty() ? List.of()
                : RecruitmentScorecard.list("interviewUuid in ?1",
                        interviews.stream().map(RecruitmentInterview::getUuid).toList());
        List<RecruitmentReferral> referrals =
                RecruitmentReferral.list("candidateUuid", candidateUuid);
        List<RecruitmentEvent> events = RecruitmentEvent.list(
                "candidateUuid = ?1 ORDER BY seq", candidateUuid);
        List<RecruitmentPendingEmail> pendingEmails =
                RecruitmentPendingEmail.list("candidateUuid = ?1 ORDER BY createdAt", candidateUuid);
        List<File> documents = s3StorageService.listCandidateFiles(
                java.util.UUID.fromString(candidateUuid));
        Map<String, String> positionTitles = positionTitles(applications);

        Map<String, Object> json = buildJson(candidate, consents, applications, answers,
                interviews, scorecards, referrals, events, pendingEmails, documents,
                positionTitles);

        try {
            byte[] jsonBytes = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(json);
            byte[] pdfBytes = buildPdf(candidate, consents, applications, answers, interviews,
                    documents, events, positionTitles);
            byte[] zip = zip(Map.of(
                    "candidate-data.json", jsonBytes,
                    "candidate-report.pdf", pdfBytes,
                    "README.txt", readme(candidate)));
            String filename = "dsar-" + candidateUuid + "-"
                    + LocalDateTime.now(ZoneOffset.UTC).toLocalDate() + ".zip";
            return new DsarExport(zip, filename, events.size(), documents.size());
        } catch (IOException e) {
            throw new IllegalStateException("DSAR export failed for candidate " + candidateUuid, e);
        }
    }

    // ------------------------------------------------------------------
    // JSON (machine-readable)
    // ------------------------------------------------------------------

    private Map<String, Object> buildJson(RecruitmentCandidate candidate,
                                          List<RecruitmentConsent> consents,
                                          List<RecruitmentApplication> applications,
                                          List<RecruitmentApplicationAnswer> answers,
                                          List<RecruitmentInterview> interviews,
                                          List<RecruitmentScorecard> scorecards,
                                          List<RecruitmentReferral> referrals,
                                          List<RecruitmentEvent> events,
                                          List<RecruitmentPendingEmail> pendingEmails,
                                          List<File> documents,
                                          Map<String, String> positionTitles) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("exported_at", LocalDateTime.now(ZoneOffset.UTC).toString());
        root.put("controller", "Trustworks A/S");
        root.put("subject_candidate_uuid", candidate.getUuid());
        root.put("candidate", candidate);
        root.put("consents", consents);
        root.put("applications", applications.stream().map(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("uuid", a.getUuid());
            m.put("position_uuid", a.getPositionUuid());
            m.put("position_title", positionTitles.get(a.getPositionUuid()));
            m.put("stage", String.valueOf(a.getStage()));
            m.put("terminal", a.getTerminal() == null ? null : a.getTerminal().name());
            m.put("rejection_reason_code", a.getRejectionReasonCode());
            m.put("expected_start_date", String.valueOf(a.getExpectedStartDate()));
            m.put("created_at", String.valueOf(a.getCreatedAt()));
            return m;
        }).toList());
        root.put("form_answers", answers.stream().map(a -> Map.of(
                "application_uuid", String.valueOf(a.getApplicationUuid()),
                "question_key", a.getQuestionKey(),
                "answer", String.valueOf(a.getAnswer()))).toList());
        // Third-party rule (class javadoc): no interviewer identities.
        root.put("interviews", interviews.stream().map(i -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", String.valueOf(i.getKind()));
            m.put("round", i.getRound());
            m.put("scheduled_at", String.valueOf(i.getScheduledAt()));
            m.put("location", i.getLocation());
            m.put("status", String.valueOf(i.getStatus()));
            return m;
        }).toList());
        root.put("assessments", scorecards.stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("interview_uuid", s.getInterviewUuid());
            m.put("scores", s.getScores());
            m.put("recommendation", String.valueOf(s.getRecommendation()));
            m.put("submitted_at", String.valueOf(s.getSubmittedAt()));
            return m;
        }).toList());
        // Source category satisfies Art. 15(1)(g); the referrer's identity
        // is third-party data and stays out.
        root.put("referrals", referrals.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("referrer_relation", String.valueOf(r.getReferrerRelation()));
            m.put("status", String.valueOf(r.getStatus()));
            m.put("submitted_at", String.valueOf(r.getSubmittedAt()));
            m.put("candidate_name", r.getCandidateName());
            m.put("email", r.getEmail());
            m.put("linkedin_url", r.getLinkedinUrl());
            m.put("why_text", r.getWhyText());
            return m;
        }).toList());
        root.put("events", events.stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", e.getSeq());
            m.put("type", e.getEventType().name());
            m.put("occurred_at", String.valueOf(e.getOccurredAt()));
            m.put("actor_type", e.getActorType().name());
            m.put("payload", parse(e.getPayload()));
            m.put("pii", parse(e.getPii()));
            return m;
        }).toList());
        root.put("queued_emails", pendingEmails.stream().map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("template_key", p.getTemplateKey());
            m.put("status", String.valueOf(p.getStatus()));
            m.put("to_email", p.getToEmail());
            m.put("subject", p.getSubject());
            m.put("body", p.getBody());
            m.put("created_at", String.valueOf(p.getCreatedAt()));
            return m;
        }).toList());
        root.put("documents", documents.stream().map(d -> Map.of(
                "file_uuid", String.valueOf(d.getUuid()),
                "name", String.valueOf(d.getName()),
                "uploaded", String.valueOf(d.getUploaddate()))).toList());
        return root;
    }

    // ------------------------------------------------------------------
    // PDF (human-readable)
    // ------------------------------------------------------------------

    private byte[] buildPdf(RecruitmentCandidate candidate,
                            List<RecruitmentConsent> consents,
                            List<RecruitmentApplication> applications,
                            List<RecruitmentApplicationAnswer> answers,
                            List<RecruitmentInterview> interviews,
                            List<File> documents,
                            List<RecruitmentEvent> events,
                            Map<String, String> positionTitles) throws IOException {
        try (PdfWriter pdf = new PdfWriter()) {
            String fullName = (nullSafe(candidate.getFirstName()) + " "
                    + nullSafe(candidate.getLastName())).trim();
            pdf.title("Data export — " + fullName);
            pdf.line("Generated " + LocalDateTime.now(ZoneOffset.UTC) + " UTC by Trustworks A/S");
            pdf.line("Subject reference: " + candidate.getUuid());
            pdf.blank();

            pdf.heading("1. Profile");
            pdf.field("Name", fullName);
            pdf.field("Email", candidate.getEmail());
            pdf.field("Phone", candidate.getPhone());
            pdf.field("LinkedIn", candidate.getLinkedinUrl());
            pdf.field("Status", String.valueOf(candidate.getStatus()));
            pdf.field("Source", String.valueOf(candidate.getSource()));
            pdf.field("Education", String.valueOf(candidate.getEducationLevel()));
            pdf.field("Experience", String.valueOf(candidate.getExperienceLevel()));
            pdf.field("Created", String.valueOf(candidate.getCreatedAt()));
            pdf.field("Process ended", String.valueOf(candidate.getProcessEndedAt()));
            pdf.field("Retention deadline", String.valueOf(candidate.getRetentionDeadline()));
            pdf.blank();

            pdf.heading("2. Consents");
            if (consents.isEmpty()) {
                pdf.line("None recorded.");
            }
            for (RecruitmentConsent consent : consents) {
                pdf.line("- " + consent.getKind() + ": " + consent.getStatus()
                        + (consent.getGrantedAt() != null
                                ? ", granted " + consent.getGrantedAt() : "")
                        + (consent.getExpiresAt() != null
                                ? ", expires " + consent.getExpiresAt() : ""));
            }
            pdf.blank();

            pdf.heading("3. Applications");
            if (applications.isEmpty()) {
                pdf.line("None recorded.");
            }
            for (RecruitmentApplication application : applications) {
                String title = positionTitles.get(application.getPositionUuid());
                pdf.line("- " + (title == null ? "Position" : title)
                        + ": stage " + application.getStage()
                        + (application.getTerminal() != null
                                ? ", ended as " + application.getTerminal() : "")
                        + ", created " + application.getCreatedAt());
                for (RecruitmentApplicationAnswer answer : answers) {
                    if (application.getUuid().equals(answer.getApplicationUuid())
                            && answer.getAnswer() != null) {
                        pdf.line("    " + answer.getQuestionKey() + ": " + answer.getAnswer());
                    }
                }
            }
            for (RecruitmentApplicationAnswer answer : answers) {
                if (answer.getApplicationUuid() == null && answer.getAnswer() != null) {
                    pdf.line("- (unsolicited) " + answer.getQuestionKey() + ": "
                            + answer.getAnswer());
                }
            }
            pdf.blank();

            pdf.heading("4. Interviews");
            if (interviews.isEmpty()) {
                pdf.line("None recorded.");
            }
            for (RecruitmentInterview interview : interviews) {
                pdf.line("- " + interview.getKind()
                        + (interview.getRound() != null ? " round " + interview.getRound() : "")
                        + ", " + interview.getStatus()
                        + (interview.getScheduledAt() != null
                                ? ", scheduled " + interview.getScheduledAt() : ""));
            }
            pdf.blank();

            pdf.heading("5. Stored documents");
            if (documents.isEmpty()) {
                pdf.line("None stored.");
            }
            for (File document : documents) {
                pdf.line("- " + document.getName() + " (uploaded "
                        + document.getUploaddate() + ")");
            }
            pdf.blank();

            pdf.heading("6. Timeline (all recorded events)");
            for (RecruitmentEvent event : events) {
                pdf.line(event.getOccurredAt() + "  " + event.getEventType());
                appendJsonLines(pdf, parse(event.getPayload()));
                appendJsonLines(pdf, parse(event.getPii()));
            }
            return pdf.finish();
        }
    }

    private static void appendJsonLines(PdfWriter pdf, Map<String, Object> section)
            throws IOException {
        for (Map.Entry<String, Object> entry : section.entrySet()) {
            if (entry.getValue() != null) {
                pdf.line("    " + entry.getKey() + ": " + entry.getValue());
            }
        }
    }

    // ------------------------------------------------------------------
    // ZIP
    // ------------------------------------------------------------------

    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256 * 1024);
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static byte[] readme(RecruitmentCandidate candidate) {
        return ("""
                Dataeksport / Data export — Trustworks A/S
                ==========================================

                Denne mappe indeholder alle personoplysninger, Trustworks A/S har
                registreret om %s i forbindelse med rekruttering (GDPR artikel 15).

                  candidate-data.json   Maskinlaesbar komplet eksport
                  candidate-report.pdf  Laesevenlig rapport

                This folder contains every piece of personal data Trustworks A/S
                holds about %s in its recruitment system (GDPR Article 15).

                Questions: hr@trustworks.dk
                """).formatted(nullSafe(candidate.getFirstName()),
                        nullSafe(candidate.getFirstName()))
                .getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Map<String, String> positionTitles(List<RecruitmentApplication> applications) {
        List<String> positionUuids = applications.stream()
                .map(RecruitmentApplication::getPositionUuid)
                .distinct()
                .toList();
        if (positionUuids.isEmpty()) {
            return Map.of();
        }
        Map<String, String> titles = new LinkedHashMap<>();
        RecruitmentPosition.<RecruitmentPosition>list("uuid in ?1", positionUuids)
                .forEach(p -> titles.put(p.getUuid(), p.getTitle()));
        return titles;
    }

    private Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, JSON_OBJECT);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    /**
     * Minimal multi-page text PDF on PDFBox: title/heading/line/field with
     * wrapping and pagination. Text is transcoded to windows-1252 with
     * replacement first — Danish characters pass through, anything the
     * standard Helvetica cannot encode becomes {@code ?} instead of an
     * exception mid-export.
     */
    private static final class PdfWriter implements AutoCloseable {

        private static final float MARGIN = 50;
        private static final float LEADING = 13.5f;
        private static final int WRAP_COLUMN = 96;

        private final PDDocument document = new PDDocument();
        private final PDFont regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        private final PDFont bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

        private PDPageContentStream content;
        private float y;

        private PdfWriter() throws IOException {
            newPage();
        }

        void title(String text) throws IOException {
            write(text, bold, 16);
            y -= 6;
        }

        void heading(String text) throws IOException {
            y -= 4;
            write(text, bold, 12);
        }

        void field(String label, String value) throws IOException {
            if (value == null || value.isBlank() || "null".equals(value)) {
                return;
            }
            line(label + ": " + value);
        }

        void line(String text) throws IOException {
            for (String wrapped : wrap(sanitize(text))) {
                write(wrapped, regular, 10);
            }
        }

        void blank() {
            y -= LEADING;
        }

        byte[] finish() throws IOException {
            content.close();
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            document.save(out);
            return out.toByteArray();
        }

        @Override
        public void close() throws IOException {
            document.close();
        }

        private void write(String text, PDFont font, float size) throws IOException {
            if (y < MARGIN + LEADING) {
                content.close();
                newPage();
            }
            content.beginText();
            content.setFont(font, size);
            content.newLineAtOffset(MARGIN, y);
            content.showText(text);
            content.endText();
            y -= LEADING + (size > 10 ? size - 10 : 0);
        }

        private void newPage() throws IOException {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            content = new PDPageContentStream(document, page);
            y = page.getMediaBox().getHeight() - MARGIN;
        }

        private static String sanitize(String text) {
            if (text == null) {
                return "";
            }
            String flattened = text.replace("\r", " ").replace("\n", " ").replace("\t", "  ");
            // Round-trip through windows-1252: unmappable characters become '?'
            // instead of throwing inside PDFBox glyph encoding.
            return new String(flattened.getBytes(WINDOWS_1252), WINDOWS_1252);
        }

        private static List<String> wrap(String text) {
            if (text.length() <= WRAP_COLUMN) {
                return List.of(text);
            }
            List<String> lines = new ArrayList<>();
            String remaining = text;
            while (remaining.length() > WRAP_COLUMN) {
                int cut = remaining.lastIndexOf(' ', WRAP_COLUMN);
                if (cut <= 0) {
                    cut = WRAP_COLUMN;
                }
                lines.add(remaining.substring(0, cut));
                remaining = "      " + remaining.substring(cut).trim();
            }
            lines.add(remaining);
            return lines;
        }
    }
}
