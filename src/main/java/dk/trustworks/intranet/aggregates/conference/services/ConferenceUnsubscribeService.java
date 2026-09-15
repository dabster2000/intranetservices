package dk.trustworks.intranet.aggregates.conference.services;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.sql.DataSource;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Durable list/address withdrawal. Each operation suspends the caller's transaction and
 * uses a new JDBC connection, bypassing an older repeatable-read snapshot and ORM cache.
 * Token insert and withdrawal return only after their local transaction has committed.
 */
@ApplicationScoped
@Transactional(Transactional.TxType.NOT_SUPPORTED)
public class ConferenceUnsubscribeService {
    public static final String API_PATH = "knowledge/conferences/unsubscribe";
    public static final String TOKEN_HEADER = "X-Unsubscribe-Token";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    // Same accepted address syntax as the existing recruitment mail sender, with length,
    // local dot, and DNS-label checks to reject malformed addresses rather than repair them.
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private final DataSource dataSource;
    private final String publicOrigin;

    @Inject
    public ConferenceUnsubscribeService(AgroalDataSource dataSource,
            @ConfigProperty(name = "conference.unsubscribe.public-origin", defaultValue = "https://trustworks.dk") String publicOrigin) {
        this((DataSource) dataSource, publicOrigin);
    }

    ConferenceUnsubscribeService(DataSource dataSource, String publicOrigin) {
        this.dataSource = dataSource;
        this.publicOrigin = requireOrigin(publicOrigin);
    }

    public static boolean isUnsubscribePath(String path) {
        if (path == null) return false;
        return API_PATH.equals(path.replaceFirst("^/", "").replaceFirst("/$", ""));
    }

    public static String validatedEmail(String email) {
        if (email == null) throw invalid("INVALID_RECIPIENT_EMAIL");
        String value = email.trim();
        if (value.length() > 320 || !EMAIL.matcher(value).matches()) throw invalid("INVALID_RECIPIENT_EMAIL");
        int at = value.indexOf('@');
        String local = value.substring(0, at);
        if (local.length() > 64 || local.startsWith(".") || local.endsWith(".") || local.contains("..")) {
            throw invalid("INVALID_RECIPIENT_EMAIL");
        }
        for (String label : value.substring(at + 1).split("\\.", -1)) {
            if (label.isEmpty() || label.length() > 63 || label.startsWith("-") || label.endsWith("-")) {
                throw invalid("INVALID_RECIPIENT_EMAIL");
            }
        }
        return value;
    }

    public static String normalizeEmail(String email) {
        return validatedEmail(email).toLowerCase(Locale.ROOT);
    }

    public static String requireUuid(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw invalid("INVALID_RECIPIENT");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    public static WebApplicationException invalid(String code) {
        return new WebApplicationException(Response.status(400).entity(Map.of("error", code)).build());
    }

    public boolean isSuppressedFresh(String conferenceUuid, String email) {
        return unsubscribedAtFresh(conferenceUuid, email) != null;
    }

    public LocalDateTime unsubscribedAtFresh(String conferenceUuid, String email) {
        String list = requireUuid(conferenceUuid);
        String normalized = normalizeEmail(email);
        try (Connection connection = connectFresh()) {
            return suppressionTime(connection, list, normalized);
        } catch (SQLException e) {
            throw unavailable();
        }
    }

    /** One fresh read for a participant list; includes only the caller-authorized list. */
    public Map<String, LocalDateTime> suppressionTimesFresh(String conferenceUuid) {
        String list = requireUuid(conferenceUuid);
        try (Connection connection = connectFresh(); var query = connection.prepareStatement(
                "SELECT normalized_email, unsubscribed_at FROM conference_email_suppression WHERE conference_uuid = ?")) {
            query.setString(1, list);
            Map<String, LocalDateTime> result = new LinkedHashMap<>();
            try (var rows = query.executeQuery()) {
                while (rows.next()) result.put(rows.getString(1), rows.getObject(2, LocalDateTime.class));
            }
            return result;
        } catch (SQLException e) {
            throw unavailable();
        }
    }

    /** Raw token is returned for in-memory rendering only. No raw value enters persistence. */
    public String issueToken(String conferenceUuid, String email) {
        String list = requireUuid(conferenceUuid);
        String normalized = normalizeEmail(email);
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        try (Connection connection = connectFresh()) {
            connection.setAutoCommit(false);
            try (var insert = connection.prepareStatement("""
                    INSERT INTO conference_unsubscribe_token
                        (token_hash, conference_uuid, normalized_email, created_at)
                    VALUES (?, ?, ?, UTC_TIMESTAMP(6))
                    """)) {
                insert.setBytes(1, hashToken(token));
                insert.setString(2, list);
                insert.setString(3, normalized);
                insert.executeUpdate();
                connection.commit();
                return token;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw unavailable();
            }
        } catch (SQLException e) {
            throw unavailable();
        }
    }

    public String unsubscribeUrl(String token) {
        hashToken(token); // refuse a noncanonical capability before composing its URL
        return publicOrigin + "/unsubscribe#token=" + token;
    }

    public String listName(String conferenceUuid) {
        try (Connection connection = connectFresh()) {
            return listName(connection, requireUuid(conferenceUuid));
        } catch (SQLException e) {
            throw unavailable();
        }
    }

    public PublicState status(String token) {
        byte[] hash = hashToken(token);
        try (Connection connection = connectFresh()) {
            TokenIdentity identity = identity(connection, hash);
            boolean suppressed = suppressionTime(connection, identity.conferenceUuid(), identity.email()) != null;
            return state(connection, identity, suppressed);
        } catch (SQLException e) {
            throw unavailable();
        }
    }

    public PublicState unsubscribe(String token) {
        byte[] hash = hashToken(token);
        try (Connection connection = connectFresh()) {
            connection.setAutoCommit(false);
            try {
                TokenIdentity identity = identity(connection, hash);
                String suppressionUuid = UUID.randomUUID().toString();
                try (var insert = connection.prepareStatement("""
                        INSERT INTO conference_email_suppression
                            (uuid, conference_uuid, normalized_email, unsubscribed_at, source)
                        VALUES (?, ?, ?, UTC_TIMESTAMP(6), 'PUBLIC_PAGE')
                        ON DUPLICATE KEY UPDATE uuid = uuid
                        """)) {
                    insert.setString(1, suppressionUuid);
                    insert.setString(2, identity.conferenceUuid());
                    insert.setString(3, identity.email());
                    insert.executeUpdate();
                }
                // Do not use affected-row count: MariaDB CLIENT_FOUND_ROWS changes no-op counts.
                // The upsert serializes concurrent POSTs, then the UUID tells who inserted.
                try (var query = connection.prepareStatement("""
                        SELECT uuid FROM conference_email_suppression
                        WHERE conference_uuid = ? AND normalized_email = ? FOR UPDATE
                        """)) {
                    query.setString(1, identity.conferenceUuid());
                    query.setString(2, identity.email());
                    try (var rows = query.executeQuery()) {
                        if (!rows.next()) throw unavailable();
                        if (suppressionUuid.equals(rows.getString(1))) audit(connection, identity, suppressionUuid);
                    }
                }
                PublicState result = state(connection, identity, true);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                if (e instanceof WebApplicationException exception) throw exception;
                throw unavailable();
            }
        } catch (SQLException e) {
            throw unavailable();
        }
    }

    private Connection connectFresh() throws SQLException {
        Connection connection = dataSource.getConnection();
        connection.setAutoCommit(true);
        return connection;
    }

    private static LocalDateTime suppressionTime(Connection connection, String list, String email) throws SQLException {
        try (var query = connection.prepareStatement("""
                SELECT unsubscribed_at FROM conference_email_suppression
                WHERE conference_uuid = ? AND normalized_email = ?
                """)) {
            query.setString(1, list);
            query.setString(2, email);
            try (var rows = query.executeQuery()) {
                return rows.next() ? rows.getObject(1, LocalDateTime.class) : null;
            }
        }
    }

    private static TokenIdentity identity(Connection connection, byte[] hash) throws SQLException {
        try (var query = connection.prepareStatement("""
                SELECT conference_uuid, normalized_email FROM conference_unsubscribe_token
                WHERE token_hash = ? AND revoked_at IS NULL
                """)) {
            query.setBytes(1, hash);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) throw notFound();
                return new TokenIdentity(rows.getString(1), rows.getString(2));
            }
        }
    }

    private static PublicState state(Connection connection, TokenIdentity identity, boolean suppressed) throws SQLException {
        return new PublicState(listName(connection, identity.conferenceUuid()), maskEmail(identity.email()),
                suppressed ? "UNSUBSCRIBED" : "NOT_UNSUBSCRIBED");
    }

    private static String listName(Connection connection, String list) throws SQLException {
        try (var query = connection.prepareStatement("SELECT name FROM conferences WHERE uuid = ?")) {
            query.setString(1, list);
            try (var rows = query.executeQuery()) {
                if (rows.next()) {
                    String name = rows.getString(1);
                    if (name != null && !name.isBlank()) return name;
                }
                return "this mailing list";
            }
        }
    }

    private static void audit(Connection connection, TokenIdentity identity, String suppressionUuid) throws SQLException {
        // The existing structured aggregate audit is persisted synchronously, with no
        // employee identity and no token/address copy. Suppression itself owns the address.
        try (var insert = connection.prepareStatement("""
                INSERT INTO aggregate_events
                    (uuid, event_user, event_type, aggregate_root_uuid, event_content, DTYPE)
                VALUES (?, NULL, 'CONFERENCE_EMAIL_UNSUBSCRIBED', ?, ?, 'ConferenceUnsubscribeEvent')
                """)) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, identity.conferenceUuid());
            insert.setString(3, "{\"source\":\"PUBLIC_PAGE\",\"suppressionUuid\":\"" + suppressionUuid + "\"}");
            insert.executeUpdate();
        }
    }

    public static byte[] hashToken(String token) {
        if (token == null || !TOKEN.matcher(token).matches()) throw notFound();
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(token);
            if (decoded.length != 32 || !token.equals(Base64.getUrlEncoder().withoutPadding().encodeToString(decoded))) {
                throw notFound();
            }
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
        } catch (IllegalArgumentException e) {
            throw notFound();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    static String maskEmail(String email) {
        int at = email.indexOf('@');
        return email.substring(0, 1) + "••••" + email.substring(at);
    }

    private static String requireOrigin(String configured) {
        try {
            URI uri = URI.create(configured);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getPort() != -1 || uri.getQuery() != null || uri.getFragment() != null
                    || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
                throw new IllegalArgumentException();
            }
            return "https://" + uri.getHost().toLowerCase(Locale.ROOT);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid conference unsubscribe public origin");
        }
    }

    private static WebApplicationException notFound() {
        return new WebApplicationException(Response.status(404).entity(Map.of("error", "NOT_FOUND")).build());
    }

    private static IllegalStateException unavailable() {
        // Do not attach driver exceptions: some drivers include SQL parameter values.
        return new IllegalStateException("CONFERENCE_POLICY_UNAVAILABLE");
    }

    private record TokenIdentity(String conferenceUuid, String email) {}
    public record PublicState(String listName, String maskedEmail, String status) {}
}
