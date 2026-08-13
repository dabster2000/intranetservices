package dk.trustworks.intranet.communicationsservice.resources;

import dk.trustworks.intranet.communicationsservice.model.EmailAttachment;
import dk.trustworks.intranet.communicationsservice.model.TrustworksMail;
import dk.trustworks.intranet.communicationsservice.model.enums.MailStatus;
import dk.trustworks.intranet.fileservice.resources.PhotoService;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@JBossLog
@ApplicationScoped
public class MailResource {

    @Inject
    PhotoService photoService;

    @Inject
    Mailer mailer;

    @ConfigProperty(name = "quarkus.mailer.from")
    String defaultFrom;

    @Transactional
    public void sendingHTML(TrustworksMail mail) {
        // Deliberately does NOT log the mail object: its toString carries
        // subject and body, and callers include candidate correspondence.
        log.infof("MailResource.sendingHTML queued mail %s", mail.getUuid());

        mail.setStatus(MailStatus.READY);
        mail.persist();
    }

    /** Mails drained per run. At the 5-minute mail-send cadence this is
     * up to 240 mails/hour; the old one-per-run drain managed 12. */
    static final int DRAIN_BATCH_SIZE = 20;

    /** Send tries before a mail is parked as {@link MailStatus#FAILED}. */
    static final int MAX_ATTEMPTS = 5;

    /** Width of {@code mail.last_error} (V494). */
    static final int LAST_ERROR_MAX_LENGTH = 500;

    /**
     * Drain the outbox — driven by the JBeret {@code mail-send} job via
     * {@code BatchScheduler} (every 5 min), NOT {@code @Scheduled}.
     * <p>
     * V494 rework: up to {@link #DRAIN_BATCH_SIZE} READY mails per run,
     * oldest first, each in its own transactions so one failing mail
     * neither rolls back nor blocks the others. The attempt is counted
     * and committed BEFORE the send — a send that kills the JVM still
     * burns an attempt — and at {@link #MAX_ATTEMPTS} the row is parked
     * as FAILED (poison-pill isolation) instead of stalling the queue,
     * which is exactly what the pre-V494 single-row drain did: the send
     * threw, the transaction rolled back, and the same row was picked
     * first again every run, forever.
     * <p>
     * Only send failures are contained per mail; an infrastructure
     * failure (DB down) propagates and fails the monitored batchlet.
     */
    public void sendMailJob() {
        List<String> readyIds = QuarkusTransaction.requiringNew().call(() ->
                TrustworksMail.<TrustworksMail>find("status = ?1",
                                Sort.ascending("createdAt", "uuid"), MailStatus.READY)
                        .page(0, DRAIN_BATCH_SIZE)
                        .list().stream()
                        .map(TrustworksMail::getUuid)
                        .toList());
        for (String uuid : readyIds) {
            drainOne(uuid);
        }
    }

    /** What the drain does with a picked-up row — pure, pinned by the
     * DB-free tier. */
    enum ClaimOutcome { SEND, PARK_FAILED, SKIP }

    /**
     * The claim rule: only READY rows are touched (the row may have been
     * sent by an overlapping run since the id was listed); a READY row
     * that already spent {@link #MAX_ATTEMPTS} tries is parked, not
     * retried.
     */
    static ClaimOutcome claimOutcome(MailStatus status, int attemptCount) {
        if (status != MailStatus.READY) {
            return ClaimOutcome.SKIP;
        }
        return attemptCount >= MAX_ATTEMPTS ? ClaimOutcome.PARK_FAILED : ClaimOutcome.SEND;
    }

    /** After a failed try: park at the attempt ceiling, else stay READY. */
    static MailStatus statusAfterFailure(int attemptCount) {
        return attemptCount >= MAX_ATTEMPTS ? MailStatus.FAILED : MailStatus.READY;
    }

    /**
     * Exception class + message, truncated to the {@code last_error}
     * column — production sql_mode is STRICT_TRANS_TABLES, so an
     * over-long error string would 1406 the bookkeeping UPDATE and turn
     * one failure into another.
     */
    static String truncatedError(Throwable failure, int maxLength) {
        String text = failure.getClass().getSimpleName()
                + (failure.getMessage() != null ? ": " + failure.getMessage() : "");
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private void drainOne(String uuid) {
        TrustworksMail claimed = QuarkusTransaction.requiringNew().call(() -> {
            TrustworksMail mail = TrustworksMail.findById(uuid);
            if (mail == null) {
                return null;
            }
            switch (claimOutcome(mail.getStatus(), mail.getAttemptCount())) {
                case SKIP -> {
                    return null;
                }
                case PARK_FAILED -> {
                    // Backstop for tries that never reached the failure
                    // bookkeeping (JVM death mid-send).
                    mail.setStatus(MailStatus.FAILED);
                    log.errorf("Mail %s parked as FAILED after %d attempts (last error: %s)",
                            uuid, mail.getAttemptCount(), mail.getLastError());
                    return null;
                }
                case SEND -> mail.setAttemptCount(mail.getAttemptCount() + 1);
            }
            return mail;
        });
        if (claimed == null) {
            return;
        }
        // The entity is detached here; only already-loaded scalars are read.
        try {
            log.infof("Sending queued mail %s (attempt %d)", uuid, claimed.getAttemptCount());
            mailer.send(applyHeaders(
                    Mail.withHtml(claimed.getTo(), claimed.getSubject(), claimed.getBody()),
                    claimed));
            QuarkusTransaction.requiringNew().run(() ->
                    TrustworksMail.update("status = ?1 where uuid = ?2", MailStatus.SENT, uuid));
        } catch (Exception e) {
            String error = truncatedError(e, LAST_ERROR_MAX_LENGTH);
            MailStatus after = statusAfterFailure(claimed.getAttemptCount());
            QuarkusTransaction.requiringNew().run(() ->
                    TrustworksMail.update("lastError = ?1, status = ?2 where uuid = ?3",
                            error, after, uuid));
            log.warnf("Mail %s send attempt %d failed%s: %s", uuid, claimed.getAttemptCount(),
                    after == MailStatus.FAILED ? " — parked as FAILED" : "", error);
        }
    }

    /**
     * Apply the optional sender/reply/copy headers persisted since V455.
     * Every field is nullable and skipped when absent, so a pre-V455 caller
     * produces exactly the message it always did.
     * <p>
     * {@code fromName} only decorates the configured
     * {@code quarkus.mailer.from} address ("Name &lt;addr&gt;") — the
     * envelope address is never swapped, because SES rejects unverified
     * sender identities with a 554.
     */
    Mail applyHeaders(Mail out, TrustworksMail mail) {
        if (isSet(mail.getFromName())) {
            out.setFrom(mail.getFromName().trim() + " <" + defaultFrom + ">");
        }
        if (isSet(mail.getReplyTo())) {
            out.setReplyTo(mail.getReplyTo().trim());
        }
        for (String cc : splitAddresses(mail.getCc())) {
            out.addCc(cc);
        }
        for (String bcc : splitAddresses(mail.getBcc())) {
            out.addBcc(bcc);
        }
        return out;
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    /** Split a stored comma-separated address list; empty for null/blank. */
    private static List<String> splitAddresses(String raw) {
        if (!isSet(raw)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    @Transactional
    public void sendingNis2Mail(String mailTo) {
        TrustworksMail mail = new TrustworksMail(UUID.randomUUID().toString(), mailTo, "DU ER NU TILMELDT NIS2 GÅ-HJEM MØDE",
                "<p>K&aelig;re tilmelder</p>\n" +
                        "\n" +
                        "<p>Tusind tak for din interesse. Du er hermed skrevet op til NIS2 g&aring;-hjem-m&oslash;de d. 16.<br />\n" +
                        "november 2023. &Oslash;nsker du at blive klogere p&aring; NIS2 allerede nu, er du meget<br />\n" +
                        "velkommen til at l&aelig;se vores artikel her:</p>\n" +
                        "\n" +
                        "<p><a href=\"https://www.trustworks.dk/wp-content/uploads/2023/05/NIS2-ARTIKEL.pdf\">https://www.trustworks.dk/wp-content/uploads/2023/05/NIS2-ARTIKEL.pdf</a></p>\n" +
                        "\n" +
                        "<p>Hvis du har nogle sp&oslash;rgsm&aring;l vedr&oslash;rende m&oslash;det eller Trustworks, er du mere end<br />\n" +
                        "velkommen til at kontakte [Navn] p&aring; [mail].</p>\n" +
                        "\n" +
                        "<p>Vi ser frem til at hilse p&aring; dig.</p>\n" +
                        "\n" +
                        "<p><strong>Trustworks</strong></p>\n" +
                        "\n" +
                        "<p><em>Vi hj&aelig;lper vores kunder med at f&aring; succes med deres it-transformationsprojekter fra<br />\n" +
                        "start til overgang. Trustworks er en konsulentvirksomhed, der fokuserer p&aring; at bygge<br />\n" +
                        "bro mellem forretning og IT for at hj&aelig;lpe kunder med at realisere m&aring;lbar<br />\n" +
                        "forretningsv&aelig;rdi fra deres it-aktiverede investeringer.</em></p>\n" +
                        "\n" +
                        "<p>Pustervig 3, 1126 K&oslash;benhavn K</p>");

        mailer.send(Mail.withHtml(
                        mail.getTo(),
                        mail.getSubject(),
                        mail.getBody())
        );
        mail.setStatus(MailStatus.SENT);
        mail.persist();
    }

    @Transactional
    public void sendingWaitingListMail(String mailTo) {
        TrustworksMail mail = new TrustworksMail(UUID.randomUUID().toString(), mailTo, "BEKRÆFTELSE PÅ EARLY BIRD OPSKRIVNING",
                "<p><span style=\"font-size:12pt\"><span style=\"font-family:Calibri,sans-serif\"><span style=\"font-family:&quot;Helvetica&quot;,sans-serif\">Du er nu skrevet op til vores Early Bird-venteliste til FOREFRONT24! Du vil v&aelig;re blandt de f&oslash;rste til at f&aring; besked via e-mail, n&aring;r tilmeldingerne &aring;bner til n&aelig;ste &aring;rs konference.</span></span></span></p>\n" +
                        "<p><span style=\"font-size:12pt\"><span style=\"font-family:Calibri,sans-serif\"><span style=\"font-family:&quot;Helvetica&quot;,sans-serif\">Tak for din interesse i FOREFRONT!</span></span></span></p>\n" +
                        "<p>&nbsp;</p>\n" +
                        "<p><span style=\"font-size:12pt\"><span style=\"font-family:Calibri,sans-serif\"><span style=\"font-family:&quot;Helvetica&quot;,sans-serif\">Venligst, </span></span></span></p>\n" +
                        "<p><span style=\"font-size:12pt\"><span style=\"font-family:Calibri,sans-serif\"><span style=\"font-family:&quot;Helvetica&quot;,sans-serif\">Trustworks</span></span></span></p>\n" +
                        "<p>&nbsp;</p>\n" +
                        "<p><span style=\"font-size:12pt\"><span style=\"font-family:Calibri,sans-serif\"><em><span style=\"font-size:10.0pt\"><span style=\"font-family:&quot;Helvetica&quot;,sans-serif\">Hvis du har takket ja&nbsp;til at modtage e-mails med tilbud&nbsp;om kommende konferencer, men ikke l&aelig;ngere &oslash;nsker at modtage disse, bedes du&nbsp;skrive&nbsp;til&nbsp;forefrontkonf@trustworks.dk, s&aring; skal vi nok afmelde dig. </span></span></em></span></span></p>");
        log.infof("MailResource sending conference mail %s", mail.getUuid());

        mailer.send(Mail.withHtml(
                mail.getTo(),
                mail.getSubject(),
                mail.getBody())
                        .addInlineAttachment("forefront-logo.png", photoService.findPhotoByRelatedUUID("c3395f9f-1d8d-476e-a517-c83e3b86545a").getFile(), "image/png", "<forefront@trustworks.dk>")
                        .addInlineAttachment("trustworks-logo.png", photoService.findPhotoByRelatedUUID("91af1119-7725-4309-8ae8-131463d8d23c").getFile(), "image/png", "<trustworks@trustworks.dk>")
        );
        mail.setStatus(MailStatus.SENT);
        mail.persist();
    }

    @Transactional
    public void sendingInvitationMail(String mailTo) {
        TrustworksMail mail = new TrustworksMail(UUID.randomUUID().toString(), mailTo, "TILLYKKE, DU HAR FÅET EN PLADS",
                "<div style='width: 600px'>\n" +
                        "  <img src=\"cid:forefront@trustworks.dk\" />" +
                        "  <p>&nbsp;</p>\n" +
                        "<p>Vi ser frem til at byde dig velkommen til FOREFRONT den 28. september 2023 p&aring; Langelinie Pavillonen. Vi garanterer dig en sp&aelig;ndende dag i selskab med et hav af inspirerende opl&aelig;gsholdere inden for digital forretningsudvikling og innovation.</p>\n" +
                        "<p>I &aring;r er programmet centreret om kultur, processer og teknologi &ndash; lanceringen af det endelige program kan du l&oslash;bende holde &oslash;je med inde p&aring; forefront.trustworks.dk. Ligeledes kan du finde de nyeste talere inde p&aring; siden.</p>\n" +
                        "<p><strong>V&aelig;rd at vide:</strong></p>\n" +
                        "<ul>\n" +
                        "<li>Konferencen afholdes torsdag den 28. september 2023 kl. 09.00-17.00.</li>\n" +
                        "<li>Langelinie Pavillonen danner rammerne for dette &aring;rs FOREFRONT, og adressen er Langelinie 10, 2100 K&oslash;benhavn &Oslash;.</li>\n" +
                        "<li>Der vil blive s&oslash;rget for forplejning under konferencen &ndash; s&aring;fremt du har nogle allergener bedes du kontakte os via e-mail.</li>\n" +
                        "</ul>\n" +
                        "<p>Vi skal nok komme med flere oplysninger, n&aring;r vi n&aelig;rmer os konferencedagen. Mens du i sp&aelig;nding venter p&aring; startskuddet til FOREFRONT,&nbsp;kan du orientere dig p&aring;&nbsp;forefront.trustworks.dk.&nbsp;</p>\n" +
                        "<p>Har du sp&oslash;rgsm&aring;l, som ikke er besvaret i vores FAQ,&nbsp;er du velkommen til at kontakte os p&aring; <a href=\"&ldquo;mailto:forefrontkonf@trustworks.dk&rdquo;\">forefrontkonf@trustworks.dk</a>.</p>\n" +
                        "<p>&nbsp;</p>\n" +
                        "<p>Vi gl&aelig;der os til at se dig!</p>\n" +
                        "<p>&nbsp;</p>\n" +
                        "<p>Venligst,&nbsp;</p>\n" +
                        "<p>Trustworks</p>\n" +
                        "  <p>&nbsp;</p>\n" +
                        "  <img src=\"cid:trustworks@trustworks.dk\" />" +
                        "  <p>&nbsp;</p>\n" +
                        "  <p><span style=\"font-size:10px\"><em>Bliver du forhindret i at deltage, bedes du hurtigst muligt kontakte os, s&aring; pladsen kan g&aring; til anden side. Har du sp&oslash;rgsm&aring;l, som ikke er besvaret i vores FAQ</em><em>, bedes du ligeledes kontakte os p&aring; </em><a href=\"&ldquo;mailto:forefrontkonf@trustworks.dk&rdquo;\"><em>forefrontkonf@trustworks.dk</em></a><em>. </em></span></p>\n" +
                        "  <p><span style=\"font-size:10px\"><em>Hvis du har takket ja&nbsp;til at modtage e-mails&nbsp;med tilbud om kommende konferencer, men ikke l&aelig;ngere &oslash;nsker at modtage disse, bedes du&nbsp;skrive&nbsp;til os p&aring;&nbsp;</em><a href=\"&ldquo;mailto:forefrontkonf@trustworks.dk&rdquo;\"><em>forefrontkonf@trustworks.dk</em></a><em>., s&aring; skal vi nok afmelde dig.&nbsp;Har du sp&oslash;rgsm&aring;l, som ikke er besvaret i vores <a href=\"&ldquo;http://forefront.34.241.72.253.nip.io/faq/&rdquo;\">FAQ</a>, bedes du ligeledes kontakte os.</em></span></p>\n" +
                        "</div>");

        log.infof("MailResource sending conference mail %s", mail.getUuid());

        mailer.send(Mail.withHtml(
                        mail.getTo(),
                        mail.getSubject(),
                        mail.getBody())
                .addInlineAttachment("forefront-logo.png", photoService.findPhotoByRelatedUUID("c3395f9f-1d8d-476e-a517-c83e3b86545a").getFile(), "image/png", "<forefront@trustworks.dk>")
                .addInlineAttachment("trustworks-logo.png", photoService.findPhotoByRelatedUUID("91af1119-7725-4309-8ae8-131463d8d23c").getFile(), "image/png", "<trustworks@trustworks.dk>")
        );
        mail.setStatus(MailStatus.SENT);
        mail.persist();
    }

    @Transactional
    public void sendingDenyMail(String mailTo) {
        TrustworksMail mail = new TrustworksMail(UUID.randomUUID().toString(), mailTo, "TUSIND TAK FOR DIN INTERESSE",
                "<div style='width: 600px'>\n" +
                        "  <img src=\"cid:forefront@trustworks.dk\" />" +
                        "  <p>&nbsp;</p>\n" +
                        "<p>Hej&nbsp;</p>\n" +
                        "<p>Du har desv&aelig;rre ikke f&aring;et en plads til dette &aring;rs FOREFRONT.&nbsp;Tusind tak for din interesse. </p>\n" +
                        "<p>Vi h&aring;ber, at du har lyst til at f&oslash;lge med p&aring; <a href=\"https://forefront.trustworks.dk\">vores hjemmeside</a> eller <a href=\"https://dk.linkedin.com/company/trustworks-as\">LinkedIn</a>, hvor vi vil dele h&oslash;jdepunkter og meget mere i forbindelse med konferencen. </p>\n" +
                        "<p>God dag. </p>\n" +
                        "<p>Venligst, </p>\n" +
                        "<p>Trustworks</p>\n" +
                        "  <p>&nbsp;</p>\n" +
                        "  <img src=\"cid:trustworks@trustworks.dk\" />" +
                        "  <p>&nbsp;</p>\n" +
                        "  <p><span style=\"font-size:10px\"><em>Hvis du har takket ja&nbsp;til at modtage e-mails&nbsp;med tilbud om kommende konferencer, men ikke l&aelig;ngere &oslash;nsker at modtage disse, bedes du&nbsp;skrive&nbsp;til&nbsp;forefrontkonf@trustworks.dk, s&aring; skal vi nok afmelde dig.&nbsp;Har du sp&oslash;rgsm&aring;l, som ikke er besvaret i vores <a href=\"http://forefront.trustworks.dk/faq/\">FAQ</a>, bedes du ligeledes kontakte os.</em></span></p>\n" +
                        "</div>");

        log.infof("MailResource sending conference mail %s", mail.getUuid());

        mailer.send(Mail.withHtml(
                        mail.getTo(),
                        mail.getSubject(),
                        mail.getBody())
                .addInlineAttachment("forefront-logo.png", photoService.findPhotoByRelatedUUID("c3395f9f-1d8d-476e-a517-c83e3b86545a").getFile(), "image/png", "<forefront@trustworks.dk>")
                .addInlineAttachment("trustworks-logo.png", photoService.findPhotoByRelatedUUID("91af1119-7725-4309-8ae8-131463d8d23c").getFile(), "image/png", "<trustworks@trustworks.dk>")
        );
        mail.setStatus(MailStatus.SENT);
        mail.persist();
    }

    @Transactional
    public void sendingWithdrawMail(String mailTo) {
        TrustworksMail mail = new TrustworksMail(UUID.randomUUID().toString(), mailTo, "BEKRÆFTELSE PÅ AFMELDING",
                "<div style='width: 600px'>\n" +
                        "<img src=\"cid:forefront@trustworks.dk\" />" +
                        "<p>&nbsp;</p>\n" +
                        "<p>Hej&nbsp;</p>\n" +
                        "<p>Vi har nu modtaget din afmelding til FOREFRONT 2023 &ndash; vi h&aring;ber p&aring; at se dig til n&aelig;ste &aring;r!</p>\n" +
                        "<p>Skulle du fortsat have interesse i at se, hvordan konferencen udfolder sig, opfordrer vi dig til at f&oslash;lge med p&aring; <a href=\"https://forefront.trustworks.dk\">vores hjemmeside</a> eller <a href=\"https://dk.linkedin.com/company/trustworks-as\">LinkedIn</a>. </p>\n" +
                        "<p>Hvis du er interesseret i at modtage slides fra opl&aelig;ggene p&aring; konferencen, er du velkommen til at besvare denne e-mail. S&aring; sender vi dem din vej efter den 28. september.</p>\n" +
                        "<p>Vi h&aring;ber at kunne byde dig velkommen en anden gang.</p>\n" +
                        "<p>Venligst, </p>\n" +
                        "<p>Trustworks</p>\n" +
                        "<p>&nbsp;</p>\n" +
                        "<img src=\"cid:trustworks@trustworks.dk\" />" +
                        "<p>&nbsp;</p>\n" +
                        "<p><span style=\"font-size:10px\"><em>Hvis du har takket ja&nbsp;til at modtage e-mails&nbsp;med tilbud om kommende konferencer, men ikke l&aelig;ngere &oslash;nsker at modtage disse, bedes du&nbsp;skrive&nbsp;til&nbsp;forefrontkonf@trustworks.dk, s&aring; skal vi nok afmelde dig.&nbsp;Har du sp&oslash;rgsm&aring;l, som ikke er besvaret i vores <a href=\"http://forefront.trustworks.dk/faq/\">FAQ</a>, bedes du ligeledes kontakte os.</em></span></p>\n" +
                        "</div>");
        log.infof("MailResource sending conference mail %s", mail.getUuid());

        mailer.send(Mail.withHtml(
                        mail.getTo(),
                        mail.getSubject(),
                        mail.getBody())
                .addInlineAttachment("forefront-logo.png", photoService.findPhotoByRelatedUUID("c3395f9f-1d8d-476e-a517-c83e3b86545a").getFile(), "image/png", "<forefront@trustworks.dk>")
                .addInlineAttachment("trustworks-logo.png", photoService.findPhotoByRelatedUUID("91af1119-7725-4309-8ae8-131463d8d23c").getFile(), "image/png", "<trustworks@trustworks.dk>")
        );
        mail.setStatus(MailStatus.SENT);
        mail.persist();
    }


    public void sendingMail(String to, String subject, String body) {
        log.info("MailResource.sendingMail");
        log.info("to = " + to);
        log.info("subject = " + subject);
        TrustworksMail mail = new TrustworksMail(UUID.randomUUID().toString(), to, subject, body);
        mail.setStatus(MailStatus.READY);
        mail.persist();
    }

    /**
     * Send an email immediately with attachments.
     * This method bypasses the queue and sends the email directly.
     *
     * @param trustworksMail the email with attachments to send
     * @throws RuntimeException if email sending fails
     */
    public void sendWithAttachments(TrustworksMail trustworksMail) {
        log.info("MailResource.sendWithAttachments");
        log.info("to = " + trustworksMail.getTo());
        log.info("subject = " + trustworksMail.getSubject());
        log.info("attachment count = " + (trustworksMail.getAttachments() != null ? trustworksMail.getAttachments().size() : 0));

        try {
            Mail mail = applyHeaders(Mail.withHtml(
                trustworksMail.getTo(),
                trustworksMail.getSubject(),
                trustworksMail.getBody()
            ), trustworksMail);

            // Add attachments if present
            if (trustworksMail.hasAttachments()) {
                for (EmailAttachment attachment : trustworksMail.getAttachments()) {
                    log.info("Adding attachment: " + attachment.getFilename() +
                            " (" + attachment.getContentType() + ", " +
                            attachment.getSize() + " bytes)");
                    mail.addAttachment(
                        attachment.getFilename(),
                        attachment.getContent(),
                        attachment.getContentType()
                    );
                }
            }

            mailer.send(mail);
            log.info("Email with attachments sent successfully to " + trustworksMail.getTo());

        } catch (Exception e) {
            log.error("Failed to send email with attachments to " + trustworksMail.getTo(), e);
            throw new RuntimeException("Failed to send email: " + e.getMessage(), e);
        }
    }
}