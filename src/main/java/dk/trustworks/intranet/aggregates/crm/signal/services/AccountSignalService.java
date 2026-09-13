package dk.trustworks.intranet.aggregates.crm.signal.services;

import dk.trustworks.intranet.aggregates.crm.signal.dto.AccountSignalRequest;
import dk.trustworks.intranet.aggregates.crm.signal.model.AccountSignal;
import dk.trustworks.intranet.aggregates.crm.signal.model.AccountSignalColleague;
import dk.trustworks.intranet.aggregates.crm.signal.model.enums.SignalSource;
import dk.trustworks.intranet.aggregates.crm.signal.model.enums.SignalStatus;
import dk.trustworks.intranet.aggregates.crm.signal.model.enums.SignalType;
import dk.trustworks.intranet.aggregates.crm.sector.services.SectorLeadService;
import dk.trustworks.intranet.aggregates.crm.sector.services.SectorService;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.services.ClientService;
import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
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

    /**
     * How many accounts one capture may name (V593).
     *
     * <p>Two is the case this was built for and three is plausible. A line naming ten is
     * not a signal, it is a mailing list, and every one of them costs a row some owner
     * has to decide on — so it is refused outright rather than quietly shortened.
     */
    public static final int MAX_CLIENTS_PER_CAPTURE = 5;

    /**
     * How many colleagues one capture may name. Same reasoning as the client cap, and the
     * same refusal: every name here becomes an edge in somebody's relationship graph.
     */
    public static final int MAX_COLLEAGUES_PER_CAPTURE = 10;
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

    /**
     * The colleague allowlist, cached on the same terms and for the same reason as the
     * client one: names and uuids only, identical for every caller, so a process-wide
     * cache leaks nothing between employees.
     */
    private volatile List<String[]> colleagueCache;
    private volatile long colleagueExpiresAt;

    @Inject
    ClientService clientService;

    @Inject
    SectorLeadService sectorLeadService;

    @Inject
    UserService userService;

    /**
     * Creates one capture: one row per account it names (V593).
     *
     * <p><b>Why N rows.</b> A line naming two accounts used to store one, because
     * {@code client_uuid} was a single column and the panel held a single pick — the
     * second {@code @} silently replaced the first, and the account that lost was the one
     * the signal was about. The rows share a {@code captureUuid} so a reader can say
     * "also filed on Rigspolitiet", but they are otherwise independent: {@code status},
     * {@code leadUuid} and {@code decidedBy} are per account, and {@link #decide}
     * authorizes against ONE client's account manager. One owner parking a signal must
     * not park it for another's.
     *
     * <p><b>All or nothing.</b> One transaction. An unknown client in the list fails the
     * whole capture rather than filing it on the accounts that did resolve — a partial
     * save is indistinguishable, afterwards, from the author having named fewer accounts.
     *
     * @param request    what the author accepted in the preview
     * @param authorUuid the acting employee, resolved from {@code X-Requested-By} by the
     *                   resource; required — an unattributed signal is worthless in a
     *                   feature whose whole value is knowing who heard it
     * @return the saved rows, in the order the author named the accounts
     */
    @Transactional
    public List<AccountSignal> create(AccountSignalRequest request, String authorUuid) {
        if (request == null) {
            throw new WebApplicationException("A body is required", Response.Status.BAD_REQUEST);
        }
        if (isBlank(authorUuid)) {
            throw new WebApplicationException(
                    "X-Requested-By is required — a signal records who heard something",
                    Response.Status.BAD_REQUEST);
        }
        String text = requireText(request.text());
        List<String> clientUuids = requireClients(request.allClientUuids());
        List<String> colleagueUuids = requireColleagues(request.allColleagueUuids(), authorUuid);

        // One timestamp and one capture uuid for the whole capture. Minting either per
        // row would make the sibling rows look like separate captures that happened to
        // arrive together, which is exactly what they are not.
        String captureUuid = UUID.randomUUID().toString();
        LocalDateTime capturedAt = LocalDateTime.now();

        String personName = trimToNull(request.personName(), MAX_PERSON_NAME_CHARS);
        String personRole = trimToNull(request.personRole(), MAX_PERSON_ROLE_CHARS);
        String relationText = trimToNull(request.relationText(), MAX_RELATION_CHARS);
        SignalType signalType = parseType(request.signalType());

        List<AccountSignal> rows = new ArrayList<>();
        for (String clientUuid : clientUuids) {
            AccountSignal row = new AccountSignal();
            row.setUuid(UUID.randomUUID().toString());
            row.setCaptureUuid(captureUuid);
            row.setClientUuid(clientUuid);
            row.setAuthorUuid(authorUuid);
            row.setSource(SignalSource.INTRA);
            row.setText(text);
            row.setPersonName(personName);
            row.setPersonRole(personRole);
            row.setRelationText(relationText);
            row.setSignalType(signalType);
            row.setStatus(SignalStatus.NEW);
            row.setCreatedAt(capturedAt);
            row.persist();
            rows.add(row);

            for (String colleagueUuid : colleagueUuids) {
                AccountSignalColleague named = new AccountSignalColleague();
                named.setUuid(UUID.randomUUID().toString());
                named.setSignalUuid(row.getUuid());
                named.setUserUuid(colleagueUuid);
                named.setCreatedAt(capturedAt);
                named.persist();
            }
        }

        log.infof("Account signal captured: capture=%s clients=%d colleagues=%d type=%s hasPerson=%s actor=%s",
                captureUuid, rows.size(), colleagueUuids.size(), signalType,
                personName != null, authorUuid);
        return rows;
    }

    /** Every signal filed on one account, newest first — the plan tab's "what we've heard". */
    public List<AccountSignal> listForClient(String clientUuid) {
        if (isBlank(clientUuid)) {
            return List.of();
        }
        return AccountSignal.list("clientUuid = ?1 order by createdAt desc", clientUuid.trim());
    }

    /**
     * Records the owner's verdict (CRM spec §3.4).
     *
     * <p><b>Who may decide is not a scope question.</b> {@code signals:decide} says the
     * caller is in the sales tier at all; WHICH signals they may decide is this check: the
     * account's owner; the SECTOR LEAD when the account has no owner (spec §3.4); or
     * management. A scope cannot express "the owner of this particular account", so it is
     * enforced here, against {@code client.accountmanager} and {@code sector_lead}.
     *
     * <p>A decision is not reversible through this endpoint — {@code NEW} is refused as a
     * target status. Re-opening a signal somebody decided is a conversation, not an API
     * call, and an audit trail that can be rewound is not an audit trail.
     *
     * @param managementOverride true when the caller holds ADMIN/PARTNER, who may decide
     *                           anywhere; resolved by the resource from the PERSON's roles,
     *                           never from the JWT, whose groups are the BFF's scopes
     */
    @Transactional
    public AccountSignal decide(String signalUuid, String statusRaw, String leadUuid,
                                String actorUuid, boolean managementOverride) {
        if (isBlank(actorUuid)) {
            throw new WebApplicationException(
                    "X-Requested-By is required — a decision records who took it",
                    Response.Status.BAD_REQUEST);
        }
        AccountSignal signal = AccountSignal.findById(signalUuid);
        if (signal == null) {
            throw new WebApplicationException("Unknown signal", Response.Status.NOT_FOUND);
        }

        SignalStatus status = parseDecision(statusRaw);
        Client client = clientService.findByUuid(signal.getClientUuid());
        boolean hasOwner = client != null && client.getAccountmanager() != null && !client.getAccountmanager().isBlank();
        boolean isOwner = hasOwner && client.getAccountmanager().equals(actorUuid);
        boolean isSectorLead = !hasOwner && client != null
                && sectorLeadService.isCurrentLead(SectorService.segmentOf(client), actorUuid);
        if (!mayDecide(managementOverride, isOwner, hasOwner, isSectorLead)) {
            throw new WebApplicationException(
                    hasOwner
                            ? "Only the account's owner can decide what to do with a signal filed on it"
                            : "This account has no owner — only its sector lead can decide what to do with a signal filed on it",
                    Response.Status.FORBIDDEN);
        }
        if (status == SignalStatus.LEAD_CREATED && isBlank(leadUuid)) {
            throw new WebApplicationException(
                    "A signal turned into a lead must name the lead", Response.Status.BAD_REQUEST);
        }

        signal.setStatus(status);
        signal.setLeadUuid(status == SignalStatus.LEAD_CREATED ? leadUuid.trim() : null);
        signal.setDecidedBy(actorUuid);
        signal.setDecidedAt(LocalDateTime.now());
        signal.persist();

        log.infof("Account signal decided: uuid=%s client=%s status=%s actor=%s",
                signal.getUuid(), signal.getClientUuid(), status, actorUuid);
        return signal;
    }

    /**
     * Who may decide a signal (spec §3.4): the owner; the sector lead when there is no
     * owner; management anywhere. Pure, so the rule is locked in the fast tier.
     */
    static boolean mayDecide(boolean management, boolean isOwner, boolean hasOwner, boolean isSectorLead) {
        return management || isOwner || (!hasOwner && isSectorLead);
    }

    /** The account manager is the Responsible; nobody else owns the account. */
    boolean isOwnerOf(String clientUuid, String actorUuid) {
        Client client = clientService.findByUuid(clientUuid);
        return client != null
                && client.getAccountmanager() != null
                && client.getAccountmanager().equals(actorUuid);
    }

    /**
     * A decision is one of three. {@code NEW} is not a decision, and an unknown value is a
     * caller bug rather than something to guess at — unlike {@link #parseType}, where an
     * unrecognised type must never cost somebody their capture.
     */
    static SignalStatus parseDecision(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new WebApplicationException("A decision is required", Response.Status.BAD_REQUEST);
        }
        SignalStatus status;
        try {
            status = SignalStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException("Unknown decision: " + raw, Response.Status.BAD_REQUEST);
        }
        if (status == SignalStatus.NEW) {
            throw new WebApplicationException(
                    "A signal cannot be moved back to undecided", Response.Status.BAD_REQUEST);
        }
        return status;
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

    /**
     * The colleague allowlist handed to the extractor and to the {@code @} picker:
     * {@code [uuid, name]} for everyone currently employed (V593).
     *
     * <p>Read in its own transaction by the caller BEFORE the model call, never during it
     * (the §P9 M1 rule). Same cache and same TTL as {@link #clientAllowlist()}, for the
     * same reason: a full employee scan per debounced keystroke burst, per employee, just
     * to build a prompt.
     *
     * <p>Employed in any status — at work, on leave of any kind — and every consultant
     * type including EXTERNAL. Somebody on parental leave still knows the people they
     * knew last month, and an external consultant on an account is exactly the person
     * whose relationship the graph is missing. PREBOARDING is out: they have not started,
     * so they cannot yet have met anybody through us.
     *
     * <p>Only uuid and name. A {@code User} is never serialised out of here — the BFF's
     * own token carries {@code admin:*}, which makes {@code UserScopeResponseFilter}
     * inert, so a whole User row would put salaries and bank details in the browser to
     * autocomplete a name.
     */
    public List<String[]> colleagueAllowlist() {
        List<String[]> cached = colleagueCache;
        if (cached != null && System.nanoTime() < colleagueExpiresAt) {
            return cached;
        }
        List<String[]> allowlist = new ArrayList<>();
        List<User> employed = userService.findEmployedUsersByDate(
                java.time.LocalDate.now(), true,
                ConsultantType.CONSULTANT, ConsultantType.STUDENT,
                ConsultantType.STAFF, ConsultantType.EXTERNAL);
        for (User user : employed) {
            if (user == null || user.getUuid() == null) {
                continue;
            }
            String name = fullNameOf(user);
            if (!isBlank(name)) {
                allowlist.add(new String[]{user.getUuid(), name});
            }
        }
        allowlist.sort((left, right) -> left[1].compareToIgnoreCase(right[1]));
        List<String[]> immutable = List.copyOf(allowlist);
        colleagueCache = immutable;
        colleagueExpiresAt = System.nanoTime() + ALLOWLIST_TTL_NANOS;
        return immutable;
    }

    /** "Firstname Lastname", falling back to the username — matches {@code PersonDTO}. */
    static String fullNameOf(User user) {
        String first = user.getFirstname() == null ? "" : user.getFirstname().trim();
        String last = user.getLastname() == null ? "" : user.getLastname().trim();
        String joined = (first + " " + last).trim();
        return joined.isEmpty() ? user.getUsername() : joined;
    }

    /**
     * The colleagues named on each of a set of signals, keyed by signal uuid.
     *
     * <p>One query for the whole account rather than one per row — the account page reads
     * every signal on a client at once, and resolving colleagues row by row is the N+1
     * the read surfaces exist to avoid.
     */
    public java.util.Map<String, List<String>> colleaguesBySignal(List<String> signalUuids) {
        java.util.Map<String, List<String>> bySignal = new java.util.LinkedHashMap<>();
        for (AccountSignalColleague row : AccountSignalColleague.listForSignals(signalUuids)) {
            bySignal.computeIfAbsent(row.getSignalUuid(), key -> new ArrayList<>())
                    .add(row.getUserUuid());
        }
        return bySignal;
    }

    /** The colleagues named on one signal. */
    public List<String> colleaguesFor(String signalUuid) {
        List<String> uuids = new ArrayList<>();
        for (AccountSignalColleague row : AccountSignalColleague.listForSignal(signalUuid)) {
            uuids.add(row.getUserUuid());
        }
        return uuids;
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
     * Every client must exist, and there must be at least one. The capture panel resolves
     * them from the {@code @} picker, so a miss here means a stale or forged uuid, not an
     * ordinary typo — 400, never a row pointing at nothing.
     *
     * <p>Order is the author's. The first account they named is the one they led with.
     */
    private List<String> requireClients(List<String> clientUuids) {
        if (clientUuids == null || clientUuids.isEmpty()) {
            throw new WebApplicationException("Pick the client with @ first", Response.Status.BAD_REQUEST);
        }
        if (clientUuids.size() > MAX_CLIENTS_PER_CAPTURE) {
            throw new WebApplicationException(
                    "One line can name at most " + MAX_CLIENTS_PER_CAPTURE + " accounts",
                    Response.Status.BAD_REQUEST);
        }
        List<String> resolved = new ArrayList<>();
        for (String clientUuid : clientUuids) {
            Client client = clientService.findByUuid(clientUuid);
            if (client == null) {
                throw new WebApplicationException("Unknown client", Response.Status.BAD_REQUEST);
            }
            resolved.add(client.getUuid());
        }
        return resolved;
    }

    /**
     * Every named colleague must be a real user, and never the author.
     *
     * <p>The author is dropped silently rather than refused: the extractor can quite
     * reasonably read "jeg og Tobias" as naming two colleagues, and the author is already
     * recorded as {@code author_uuid}. Keeping them here would double every KNOWS edge
     * {@code AccountRelationshipService} draws from this signal.
     *
     * <p>An unknown uuid IS refused. Unlike an unreadable signal type, this is not
     * something a capture should survive: the panel only ever sends uuids it got from the
     * mentionables endpoint or from the verified extraction, so anything else is stale or
     * forged, and the row it would create asserts that a named employee knows a named
     * third party.
     */
    private List<String> requireColleagues(List<String> colleagueUuids, String authorUuid) {
        if (colleagueUuids == null || colleagueUuids.isEmpty()) {
            return List.of();
        }
        if (colleagueUuids.size() > MAX_COLLEAGUES_PER_CAPTURE) {
            throw new WebApplicationException(
                    "One line can name at most " + MAX_COLLEAGUES_PER_CAPTURE + " colleagues",
                    Response.Status.BAD_REQUEST);
        }
        List<String> resolved = new ArrayList<>();
        for (String colleagueUuid : colleagueUuids) {
            if (colleagueUuid.equals(authorUuid)) {
                continue;
            }
            User user = User.findById(colleagueUuid);
            if (user == null) {
                throw new WebApplicationException("Unknown colleague", Response.Status.BAD_REQUEST);
            }
            resolved.add(user.getUuid());
        }
        return resolved;
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
