package dk.trustworks.intranet.aggregates.conference.services;

import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Opt-in disposable LOCAL MariaDB only; never uses application datasource configuration. */
@EnabledIfSystemProperty(named = "conference.unsubscribe.test.jdbc-url",
        matches = "jdbc:mariadb://127\\.0\\.0\\.1:[0-9]+/conference_unsubscribe_test")
class ConferenceUnsubscribeDatabaseTest {
    private static final String A = "00000000-0000-0000-0000-000000000001";
    private static final String B = "00000000-0000-0000-0000-000000000002";
    private Connection connect() throws Exception {
        return DriverManager.getConnection(System.getProperty("conference.unsubscribe.test.jdbc-url"), "root",
                System.getProperty("conference.unsubscribe.test.password"));
    }
    private long count(String sql) throws Exception {
        try (var connection = connect(); var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next()); return rows.getLong(1);
        }
    }
    private void execute(String sql) throws Exception {
        try (var connection = connect(); var statement = connection.createStatement()) { statement.execute(sql); }
    }
    @Test
    void migrationConcurrentWithdrawalIsolationAndImmutableAddressBinding() throws Exception {
        // No IF NOT EXISTS: refuse to mutate an already populated test database.
        execute("CREATE TABLE conferences (uuid CHAR(36) PRIMARY KEY, name VARCHAR(255)) ENGINE=InnoDB");
        execute("CREATE TABLE aggregate_events (uuid VARCHAR(36) PRIMARY KEY, event_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, event_user VARCHAR(50), event_type VARCHAR(40), aggregate_root_uuid VARCHAR(36), event_content MEDIUMTEXT, DTYPE VARCHAR(31)) ENGINE=InnoDB");
        execute("CREATE TABLE conference_participants (uuid CHAR(36) PRIMARY KEY, participantuuid CHAR(36), conferenceuuid CHAR(36), email VARCHAR(320), name VARCHAR(255), registered DATETIME(6)) ENGINE=InnoDB");
        for (String sql : Files.readString(Path.of("src/main/resources/db/migration/V617__Conference_email_unsubscribe.sql")).split(";")) {
            if (!sql.isBlank()) execute(sql);
        }
        execute("INSERT INTO conferences VALUES ('" + A + "', 'TechTalk'), ('" + B + "', 'Another list')");
        DataSource datasource = mock(DataSource.class);
        when(datasource.getConnection()).thenAnswer(ignored -> connect());
        var service = new ConferenceUnsubscribeService(datasource, "https://trustworks.dk");
        try (var connection = connect(); var statement = connection.createStatement(); var rows = statement.executeQuery("SHOW FULL COLUMNS FROM conference_email_suppression LIKE 'normalized_email'")) {
            assertTrue(rows.next()); assertEquals("utf8mb4_bin", rows.getString("Collation"));
        }
        String first = service.issueToken(A, " Mary+tag@EXAMPLE.COM ");
        String second = service.issueToken(A, "mary+tag@example.com");
        String otherList = service.issueToken(B, "mary+tag@example.com");
        assertNotEquals(first, second); assertEquals(43, first.length());
        assertEquals("NOT_UNSUBSCRIBED", service.status(first).status());
        assertEquals("NOT_UNSUBSCRIBED", service.status(first).status());
        assertEquals(0, count("SELECT COUNT(*) FROM conference_email_suppression"));
        assertEquals(0, count("SELECT COUNT(*) FROM aggregate_events"));
        try (var connection = connect(); var query = connection.prepareStatement("SELECT normalized_email FROM conference_unsubscribe_token WHERE token_hash = ?")) {
            query.setBytes(1, ConferenceUnsubscribeService.hashToken(first));
            try (var rows = query.executeQuery()) { assertTrue(rows.next()); assertEquals("mary+tag@example.com", rows.getString(1)); }
        }
        assertEquals(404, assertThrows(WebApplicationException.class, () -> service.status("A".repeat(43))).getResponse().getStatus());
        int writers = 8;
        CountDownLatch ready = new CountDownLatch(writers), start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(writers)) {
            var futures = new ArrayList<java.util.concurrent.Future<String>>();
            for (int i = 0; i < writers; i++) {
                String token = i % 2 == 0 ? first : second;
                futures.add(pool.submit(() -> { ready.countDown(); assertTrue(start.await(15, TimeUnit.SECONDS)); return service.unsubscribe(token).status(); }));
            }
            assertTrue(ready.await(15, TimeUnit.SECONDS)); start.countDown();
            for (var result : futures) assertEquals("UNSUBSCRIBED", result.get(30, TimeUnit.SECONDS));
        }
        assertEquals(1, count("SELECT COUNT(*) FROM conference_email_suppression"));
        assertEquals(1, count("SELECT COUNT(*) FROM aggregate_events WHERE event_type = 'CONFERENCE_EMAIL_UNSUBSCRIBED' AND event_user IS NULL"));
        var originalTime = service.unsubscribedAtFresh(A, "MARY+TAG@EXAMPLE.COM");
        service.unsubscribe(first);
        assertEquals(originalTime, service.unsubscribedAtFresh(A, "mary+tag@example.com"));
        assertEquals(1, count("SELECT COUNT(*) FROM aggregate_events"));
        assertEquals("NOT_UNSUBSCRIBED", service.status(otherList).status());
        assertFalse(service.isSuppressedFresh(A, "mary@example.com"));

        // A real old repeatable-read chunk cannot see the commit; the fresh policy read can.
        String later = service.issueToken(A, "later@example.com");
        try (var oldChunk = connect()) {
            oldChunk.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ); oldChunk.setAutoCommit(false);
            try (var query = oldChunk.prepareStatement("SELECT COUNT(*) FROM conference_email_suppression WHERE normalized_email = 'later@example.com'")) {
                try (var rows = query.executeQuery()) { rows.next(); assertEquals(0, rows.getLong(1)); }
                service.unsubscribe(later);
                try (var rows = query.executeQuery()) { rows.next(); assertEquals(0, rows.getLong(1)); }
                assertTrue(service.isSuppressedFresh(A, "later@example.com"));
            }
            oldChunk.rollback();
        }
        String participant = "10000000-0000-0000-0000-000000000001";
        String snapshot = UUID.randomUUID().toString();
        execute("INSERT INTO conference_participants VALUES ('" + snapshot + "', '" + participant + "', '" + A + "', 'new@example.com', 'New', '2026-09-15 12:00:00')");
        assertTrue(service.isSuppressedFresh(A, "mary+tag@example.com")); assertFalse(service.isSuppressedFresh(A, "new@example.com"));
        execute("DELETE FROM conference_participants"); assertEquals("UNSUBSCRIBED", service.status(first).status());
        execute("UPDATE conferences SET name = 'Renamed' WHERE uuid = '" + A + "'"); assertEquals("Renamed", service.status(first).listName());
        execute("DELETE FROM conferences WHERE uuid = '" + B + "'"); assertEquals("this mailing list", service.unsubscribe(otherList).listName());

        // Audit failure rolls back the preference; no partial success can escape.
        String atomic = service.issueToken(A, "atomic@example.com"); execute("RENAME TABLE aggregate_events TO withheld_audit");
        try { assertThrows(IllegalStateException.class, () -> service.unsubscribe(atomic)); assertFalse(service.isSuppressedFresh(A, "atomic@example.com")); }
        finally { execute("RENAME TABLE withheld_audit TO aggregate_events"); }
        service.unsubscribe(atomic); assertTrue(service.isSuppressedFresh(A, "atomic@example.com"));
        String revoked = service.issueToken(A, "revoked@example.com");
        try (var connection = connect(); var update = connection.prepareStatement("UPDATE conference_unsubscribe_token SET revoked_at = UTC_TIMESTAMP(6) WHERE token_hash = ?")) {
            update.setBytes(1, ConferenceUnsubscribeService.hashToken(revoked)); update.executeUpdate();
        }
        assertEquals(404, assertThrows(WebApplicationException.class, () -> service.unsubscribe(revoked)).getResponse().getStatus());
        assertFalse(service.isSuppressedFresh(A, "revoked@example.com"));

        // Exercise the actual immediate/attachment SMTP boundary with a committed
        // withdrawal between preparation and final dispatch (no mocked policy lookup).
        final String[] deliveryToken = new String[1];
        var racingPolicy = new ConferenceUnsubscribeService(datasource, "https://trustworks.dk") {
            @Override public String issueToken(String list, String email) {
                deliveryToken[0] = super.issueToken(list, email);
                return deliveryToken[0];
            }
            @Override public String listName(String list) {
                service.unsubscribe(deliveryToken[0]);
                return super.listName(list);
            }
        };
        var dispatch = new ConferenceMailDispatch();
        dispatch.policy = racingPolicy;
        dispatch.enabled = true;
        var mailResource = new dk.trustworks.intranet.communicationsservice.resources.MailResource();
        var smtp = mock(io.quarkus.mailer.Mailer.class);
        inject(mailResource, "conferenceDispatch", dispatch);
        inject(mailResource, "mailer", smtp);
        var message = new dk.trustworks.intranet.communicationsservice.model.TrustworksMail(
                UUID.randomUUID().toString(), "race@example.com", "Test", "<html><body><p>Message</p></body></html>");
        message.setMailOrigin("CONFERENCE"); message.setConferenceUuid(A); message.setParticipantUuid(participant);
        message.setNormalizedEmail("race@example.com");
        message.setAttachments(List.of(new dk.trustworks.intranet.communicationsservice.model.EmailAttachment(
                "note.txt", "text/plain", new byte[]{1, 2, 3})));
        assertFalse(mailResource.sendConferenceWithAttachments(message));
        assertTrue(service.isSuppressedFresh(A, "race@example.com"));
        verifyNoInteractions(smtp);
        long tokenCount = count("SELECT COUNT(*) FROM conference_unsubscribe_token");
        assertFalse(mailResource.sendConferenceWithAttachments(message)); // retry checks again before issuing
        assertEquals(tokenCount, count("SELECT COUNT(*) FROM conference_unsubscribe_token"));
        verifyNoInteractions(smtp);
        assertEquals("<html><body><p>Message</p></body></html>", message.getBody());
        var systemMessage = new dk.trustworks.intranet.communicationsservice.model.TrustworksMail(
                UUID.randomUUID().toString(), "race@example.com", "System", "Unrelated system content");
        mailResource.sendWithAttachments(systemMessage);
        verify(smtp).send(any(io.quarkus.mailer.Mail.class));

        String p2 = "10000000-0000-0000-0000-000000000002";
        execute("INSERT INTO conference_participants VALUES ('" + UUID.randomUUID() + "', '" + participant + "', '" + A + "', 'same@example.com', 'Alpha', '2026-09-15 12:00:00'), ('" + UUID.randomUUID() + "', '" + p2 + "', '" + A + "', 'SAME@example.com', 'Beta', '2026-09-15 12:00:00')");
        var resolver = new ConferenceRecipientResolver(datasource);
        var recipients = resolver.resolve(A, List.of(p2, participant)); assertEquals(1, recipients.size()); assertEquals("Alpha", recipients.getFirst().name());
        assertEquals(400, assertThrows(WebApplicationException.class, () -> resolver.resolveOne(A, snapshot)).getResponse().getStatus());
        execute("INSERT INTO conference_participants VALUES ('" + UUID.randomUUID() + "', '" + participant + "', '" + A + "', 'conflict@example.com', 'Conflict', '2026-09-15 12:00:00')");
        assertEquals(409, assertThrows(WebApplicationException.class, () -> resolver.resolveOne(A, participant)).getResponse().getStatus());
    }
    private static void inject(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true); field.set(target, value);
    }

    @Test
    void deliveryMigrationHoldsUnclassifiedBacklogAndSupportsSkippedAccounting() throws Exception {
        execute("CREATE TABLE conference_phases (uuid CHAR(36) PRIMARY KEY) ENGINE=InnoDB");
        execute("CREATE TABLE mail (uuid CHAR(36) PRIMARY KEY, status VARCHAR(10), mail VARCHAR(255)) ENGINE=InnoDB");
        execute("CREATE TABLE bulk_email_job (uuid CHAR(36) PRIMARY KEY, status ENUM('PENDING','PROCESSING','COMPLETED','FAILED') NOT NULL DEFAULT 'PENDING', total_recipients INT NOT NULL DEFAULT 0, sent_count INT NOT NULL DEFAULT 0, failed_count INT NOT NULL DEFAULT 0) ENGINE=InnoDB");
        execute("CREATE TABLE bulk_email_recipient (id BIGINT PRIMARY KEY, job_uuid CHAR(36), recipient_email VARCHAR(255), status ENUM('PENDING','SENT','FAILED') NOT NULL DEFAULT 'PENDING') ENGINE=InnoDB");
        execute("INSERT INTO mail (uuid,status) VALUES ('ready', 'READY'), ('sent', 'SENT'), ('failed', 'FAILED')");
        execute("INSERT INTO bulk_email_job (uuid, status, total_recipients) VALUES ('pending','PENDING',2), ('processing','PROCESSING',1), ('completed','COMPLETED',0)");
        execute("INSERT INTO bulk_email_recipient VALUES (1, 'pending', 'first@example.com', 'PENDING'), (2, 'pending', 'second@example.com', 'PENDING')");
        for (String sql : Files.readString(Path.of("src/main/resources/db/migration/V618__Conference_mail_policy_delivery.sql")).replaceAll("(?m)^--.*$", "").split(";")) {
            if (!sql.isBlank()) execute(sql);
        }
        assertEquals(1, count("SELECT COUNT(*) FROM mail WHERE status = 'HELD' AND hold_reason = 'LEGACY_UNCLASSIFIED'"));
        assertEquals(2, count("SELECT COUNT(*) FROM bulk_email_job WHERE status = 'HELD' AND hold_reason = 'LEGACY_UNCLASSIFIED'"));
        assertEquals(1, count("SELECT COUNT(*) FROM mail WHERE status = 'SENT'"));
        execute("UPDATE mail SET status = 'SKIPPED', skip_reason = 'UNSUBSCRIBED' WHERE uuid = 'ready'");
        execute("UPDATE bulk_email_recipient SET status = 'SKIPPED', skip_reason = 'UNSUBSCRIBED' WHERE job_uuid = 'pending'");
        execute("UPDATE bulk_email_job SET status = 'COMPLETED', skipped_count = 2 WHERE uuid = 'pending'");
        assertEquals(1, count("SELECT COUNT(*) FROM bulk_email_job WHERE uuid = 'pending' AND total_recipients = sent_count + failed_count + skipped_count AND sent_count = 0"));
        execute("UPDATE mail SET status = 'POLICY_READY' WHERE uuid = 'ready'");
        execute("UPDATE bulk_email_job SET status = 'POLICY_PENDING' WHERE uuid = 'pending'");
        assertEquals(0, count("SELECT COUNT(*) FROM mail WHERE status = 'READY'"));
        assertEquals(0, count("SELECT COUNT(*) FROM bulk_email_job WHERE status IN ('PENDING','PROCESSING')"));
    }

}
