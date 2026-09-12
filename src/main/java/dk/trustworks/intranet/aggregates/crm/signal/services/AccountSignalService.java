package dk.trustworks.intranet.aggregates.crm.signal.services;

import dk.trustworks.intranet.aggregates.crm.signal.dto.AccountSignalRequest;
import dk.trustworks.intranet.aggregates.crm.signal.model.AccountSignal;
import dk.trustworks.intranet.aggregates.crm.signal.model.enums.SignalSource;
import dk.trustworks.intranet.aggregates.crm.signal.model.enums.SignalStatus;
import dk.trustworks.intranet.aggregates.crm.signal.model.enums.SignalType;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.services.ClientService;
import dk.trustworks.intranet.domain.user.entity.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Saves one "Heard something?" capture (CRM spec §3.4).
 *
 * <p>Everything that identifies WHO heard it is derived here from the request context,
 * never read from the body: author, source, status and timestamp. A caller cannot forge
 * attribution or pre-decide a signal.
 *
 * <p>Validation is hand-rolled rather than annotated. Bean validation is NOT active in
 * this service — {@code quarkus-hibernate-validator} is absent from the build, so every
 * {@code @NotBlank} in this codebase is inert decoration. Checks that matter are written
 * as plain Java and throw 400.
 *
 * <p>No OpenAI call happens anywhere in this class: extraction is
 * {@link SignalExtractionService}'s job and runs before this, outside any transaction.
 */
@JBossLog
@ApplicationScoped
public class AccountSignalService {

    /** A signal is one sentence. Matches the column and the extractor's own cap. */
    public static final int MAX_TEXT_CHARS = 2000;
    public static final int MAX_PERSON_NAME_CHARS = 255;
    public static final int MAX_PERSON_ROLE_CHARS = 255;
    public static final int MAX_RELATION_CHARS = 500;

    /** How long a built allowlist may be reused before the client table is read again. */
    static final long ALLOWLIST_TTL_SECONDS = 60L;
    private static final long ALLOWLIST_TTL_NANOS = ALLOWLIST_TTL_SECONDS * 1_000_000_000L;

    /**
     * Client names and uuids only — no personal data, and identical for every caller, so
     * a process-wide cache leaks nothing between employees. Volatile rather than
     * synchronized: a racing rebuild costs one extra query and nothing else.
     */
    private volatile List<String[]> allowlistCache;
    private volatile long allowlistExpiresAt;

    @Inject
    ClientService clientService;

    /**
     * Creates a signal.
     *
     * @param request    what the author accepted in the preview
     * @param authorUuid the acting employee, resolved from {@code X-Requested-By} by the
     *                   resource; required — an unattributed signal is worthless in a
     *                   feature whose whole value is knowing who heard it
     * @return the saved row
     */
    @Transactional
    public AccountSignal create(AccountSignalRequest request, String authorUuid) {
        if (request == null) {
            throw new WebApplicationException("A body is required", Response.Status.BAD_REQUEST);
        }
        if (isBlank(authorUuid)) {
            throw new WebApplicationException(
                    "X-Requested-By is required — a signal records who heard something",
                    Response.Status.BAD_REQUEST);
        }
        String text = requireText(request.text());
        String clientUuid = requireClient(request.clientUuid());

        AccountSignal row = new AccountSignal();
        row.setUuid(UUID.randomUUID().toString());
        row.setClientUuid(clientUuid);
        row.setAuthorUuid(authorUuid);
        row.setSource(SignalSource.INTRA);
        row.setText(text);
        row.setPersonName(trimToNull(request.personName(), MAX_PERSON_NAME_CHARS));
        row.setPersonRole(trimToNull(request.personRole(), MAX_PERSON_ROLE_CHARS));
        row.setRelationText(trimToNull(request.relationText(), MAX_RELATION_CHARS));
        row.setSignalType(parseType(request.signalType()));
        row.setStatus(SignalStatus.NEW);
        row.setCreatedAt(LocalDateTime.now());
        row.persist();

        log.infof("Account signal created: uuid=%s client=%s type=%s hasPerson=%s actor=%s",
                row.getUuid(), row.getClientUuid(), row.getSignalType(),
                row.getPersonName() != null, authorUuid);
        return row;
    }

    /**
     * The client allowlist handed to the extractor: {@code [uuid, name]} for every client.
     *
     * <p>Read in its own transaction by the caller BEFORE the model call, never during it
     * (the §P9 M1 rule). Deliberately the full client list rather than the caller's own
     * clients: an employee most often hears something about a company Trustworks does not
     * serve yet, and a contract-scoped list would find nothing exactly then.
     *
     * <p>Cached for {@value #ALLOWLIST_TTL_SECONDS}s. Without it every debounced keystroke
     * burst from every employee is a full scan of the client table just to build a prompt;
     * the list changes when someone creates a client, which is rare enough that a minute
     * of staleness costs nothing and a missing client resolves on the next open anyway.
     */
    public List<String[]> clientAllowlist() {
        List<String[]> cached = allowlistCache;
        if (cached != null && System.nanoTime() < allowlistExpiresAt) {
            return cached;
        }
        List<String[]> allowlist = new ArrayList<>();
        for (Client client : clientService.listAllClients()) {
            if (client != null && client.getUuid() != null && !isBlank(client.getName())) {
                allowlist.add(new String[]{client.getUuid(), client.getName()});
            }
        }
        List<String[]> immutable = List.copyOf(allowlist);
        allowlistCache = immutable;
        allowlistExpiresAt = System.nanoTime() + ALLOWLIST_TTL_NANOS;
        return immutable;
    }

    /** First name of the acting employee, used to phrase the relation from the author. */
    public String authorFirstName(String authorUuid) {
        if (isBlank(authorUuid)) {
            return null;
        }
        User user = User.findById(authorUuid);
        return user == null ? null : user.getFirstname();
    }

    private String requireText(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            throw new WebApplicationException("Nothing was typed", Response.Status.BAD_REQUEST);
        }
        if (text.length() > MAX_TEXT_CHARS) {
            throw new WebApplicationException(
                    "A signal is one line — keep it under " + MAX_TEXT_CHARS + " characters",
                    Response.Status.BAD_REQUEST);
        }
        return text;
    }

    /**
     * The client must exist. The capture panel resolves it from the {@code @} picker, so a
     * miss here means a stale or forged uuid, not an ordinary typo — 400, never a row
     * pointing at nothing.
     */
    private String requireClient(String clientUuid) {
        if (isBlank(clientUuid)) {
            throw new WebApplicationException("Pick the client with @ first", Response.Status.BAD_REQUEST);
        }
        Client client = clientService.findByUuid(clientUuid.trim());
        if (client == null) {
            throw new WebApplicationException("Unknown client", Response.Status.BAD_REQUEST);
        }
        return client.getUuid();
    }

    /** Unknown, misspelled and absent types all become OTHER — a capture is never refused over it. */
    static SignalType parseType(String raw) {
        if (raw == null || raw.isBlank()) {
            return SignalType.OTHER;
        }
        try {
            return SignalType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return SignalType.OTHER;
        }
    }

    static String trimToNull(String raw, int maxChars) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > maxChars ? trimmed.substring(0, maxChars) : trimmed;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
