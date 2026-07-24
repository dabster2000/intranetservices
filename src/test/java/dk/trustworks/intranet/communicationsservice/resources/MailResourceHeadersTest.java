package dk.trustworks.intranet.communicationsservice.resources;

import dk.trustworks.intranet.communicationsservice.model.TrustworksMail;
import io.quarkus.mailer.Mail;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The step where the persisted outbox columns become actual mail headers.
 * Everything else in this change only proves the right values reach the
 * {@code mail} row — this proves the row reaches the message.
 * <p>
 * Two invariants are load-bearing:
 * <ul>
 *   <li><b>Absent fields change nothing.</b> Every caller predating the
 *       sender/copy columns (payslips, expenses, conference mails) must
 *       produce a byte-identical message.</li>
 *   <li><b>The envelope address is never swapped.</b> {@code fromName}
 *       only decorates the configured, provider-verified address — a
 *       per-person From would be rejected with a 554.</li>
 * </ul>
 */
class MailResourceHeadersTest {

    private static final String DEFAULT_FROM = "no-reply@trustworks.dk";

    private static MailResource resourceWithDefaultFrom() {
        MailResource resource = new MailResource();
        resource.defaultFrom = DEFAULT_FROM;
        return resource;
    }

    private static TrustworksMail mail() {
        return new TrustworksMail(UUID.randomUUID().toString(),
                "kandidat@example.com", "Emne", "<div>Tekst</div>");
    }

    private static Mail apply(TrustworksMail source) {
        return resourceWithDefaultFrom().applyHeaders(
                Mail.withHtml(source.getTo(), source.getSubject(), source.getBody()),
                source);
    }

    @Test
    void noOptionalFields_leaveTheMessageExactlyAsBefore() {
        Mail out = apply(mail());

        assertNull(out.getFrom(), "no from override — the configured sender stands");
        assertNull(out.getReplyTo());
        assertTrue(out.getCc().isEmpty());
        assertTrue(out.getBcc().isEmpty());
    }

    @Test
    void fromName_decoratesTheConfiguredAddress_neverReplacesIt() {
        TrustworksMail source = mail();
        source.setFromName("Trustworks Rekruttering");

        Mail out = apply(source);

        assertEquals("Trustworks Rekruttering <" + DEFAULT_FROM + ">", out.getFrom());
        assertTrue(out.getFrom().contains(DEFAULT_FROM),
                "the verified envelope address must survive the decoration");
    }

    @Test
    void replyTo_isApplied_andTrimmed() {
        TrustworksMail source = mail();
        source.setReplyTo("  rina@trustworks.dk  ");

        assertEquals("rina@trustworks.dk", apply(source).getReplyTo());
    }

    @Test
    void ccAndBcc_splitOnCommas_andTrim() {
        TrustworksMail source = mail();
        source.setCc("anna@trustworks.dk, bo@trustworks.dk");
        source.setBcc("carl@trustworks.dk");

        Mail out = apply(source);

        assertEquals(java.util.List.of("anna@trustworks.dk", "bo@trustworks.dk"), out.getCc());
        assertEquals(java.util.List.of("carl@trustworks.dk"), out.getBcc());
    }

    @Test
    void blankAndRaggedLists_produceNoRecipients_notEmptyAddresses() {
        // A stored ", ," must never become an empty-string recipient, which
        // SMTP would reject and take the whole message down with it.
        TrustworksMail source = mail();
        source.setCc("  ");
        source.setBcc(", ,");
        source.setReplyTo("   ");
        source.setFromName("  ");

        Mail out = apply(source);

        assertTrue(out.getCc().isEmpty());
        assertTrue(out.getBcc().isEmpty());
        assertNull(out.getReplyTo());
        assertNull(out.getFrom());
    }
}
