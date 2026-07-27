package dk.trustworks.intranet.documentservice.migration.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dk.trustworks.intranet.apis.openai.OpenAIService;
import dk.trustworks.intranet.documentservice.migration.model.SharePointMigrationFolder;
import dk.trustworks.intranet.documentservice.migration.model.SharePointMigrationFolder.AiConfidence;
import dk.trustworks.intranet.documentservice.migration.model.SharePointMigrationFolder.FolderStatus;
import dk.trustworks.intranet.documentservice.migration.model.SharePointMigrationFolder.MatchMethod;
import dk.trustworks.intranet.documentservice.services.EmployeeDocumentsFeatureFlag;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.domain.user.entity.UserStatus;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * M2 — folders → users (runbook 2a-3 / spec §9.3, decisions A1/A2).
 *
 * <p><b>Stage 1</b> (deterministic, auto-applies): exact USERNAME tier,
 * then normalized FULLNAME tier (æ↔ae, ø↔oe, å↔aa both directions via a
 * shared canonical form). An exact hit sets {@code status=MAPPED}
 * directly — no AI call is made for it.</p>
 *
 * <p><b>Stage 2</b> (AI proposals, never auto-applied): one batched
 * OpenAI call over the full employee directory (active AND terminated —
 * decision D2) and the still-unmatched folder names. Strict-schema
 * response, hard-validated: a userUuid not in the directory is rejected.
 * Proposals land in the {@code ai_*} columns; <b>status stays
 * DISCOVERED</b> until a human confirms (stage 3, admin card). With the
 * kill switch OFF or OpenAI failing, stage 2 is skipped entirely — the
 * matcher run must never fail because OpenAI is down.</p>
 *
 * <p><b>Stage 3</b>: {@link #confirmSuggestion}, {@link #mapManually},
 * {@link #skipFolder} — all sticky across re-runs (stage 1/2 only ever
 * touch rows with {@code status=DISCOVERED} and no match).</p>
 */
@JBossLog
@ApplicationScoped
public class SharePointFolderMatcherService {

    @Inject
    EmployeeDocumentsFeatureFlag featureFlag;

    @Inject
    OpenAIService openAIService;

    @Inject
    ObjectMapper objectMapper;

    /** Decision A5: own model config, default the house model. */
    @ConfigProperty(name = "dk.trustworks.employee-documents.migration.ai-model",
            defaultValue = "gpt-5-nano")
    String aiModel;

    public record MatchSummary(
            int usernameMatched,
            int fullnameMatched,
            int aiProposals,
            int aiNoMatch,
            int aiRejected,
            int remainingUnresolved,
            boolean aiUsed,
            List<String> errors) { }

    public record DirectoryEntry(String uuid, String username, String fullName, String period) { }

    // ── The run ────────────────────────────────────────────────────────────

    public MatchSummary match() {
        List<DirectoryEntry> directory = QuarkusTransaction.requiringNew().call(this::loadDirectory);

        int usernameMatched = 0;
        int fullnameMatched = 0;
        List<String> errors = new ArrayList<>();

        List<Long> pendingIds = QuarkusTransaction.requiringNew().call(() ->
                SharePointMigrationFolder.findUnresolved().stream()
                        .map(SharePointMigrationFolder::getId).toList());

        Map<String, DirectoryEntry> byUsername = directory.stream()
                .filter(e -> e.username() != null && !e.username().isBlank())
                .collect(Collectors.toMap(e -> e.username().toLowerCase().trim(), e -> e, (a, b) -> a));
        Map<String, DirectoryEntry> byCanonicalName = directory.stream()
                .filter(e -> !canonicalName(e.fullName()).isBlank())
                .collect(Collectors.toMap(e -> canonicalName(e.fullName()), e -> e, (a, b) -> a));

        List<Long> stillUnmatched = new ArrayList<>();
        for (Long folderId : pendingIds) {
            MatchMethod applied = QuarkusTransaction.requiringNew().call(() -> {
                SharePointMigrationFolder folder = SharePointMigrationFolder.findById(folderId);
                if (folder == null || folder.getStatus() != FolderStatus.DISCOVERED
                        || folder.getMatchedUserUuid() != null) {
                    return null;
                }
                DirectoryEntry exact = exactMatch(folder.getFolderName(), byUsername, byCanonicalName);
                if (exact == null) return null;
                MatchMethod method = byUsername.get(folder.getFolderName().toLowerCase().trim()) == exact
                        ? MatchMethod.USERNAME : MatchMethod.FULLNAME;
                folder.setMatchedUserUuid(exact.uuid());
                folder.setMatchMethod(method);
                folder.setStatus(FolderStatus.MAPPED);
                folder.persist();
                return method;
            });
            if (applied == MatchMethod.USERNAME) usernameMatched++;
            else if (applied == MatchMethod.FULLNAME) fullnameMatched++;
            else stillUnmatched.add(folderId);
        }

        int aiProposals = 0;
        int aiNoMatch = 0;
        int aiRejected = 0;
        boolean aiUsed = false;

        // The job runner executes this on a worker thread with no request
        // context, so the settings read needs its own transaction like every
        // other DB access in this run.
        if (!stillUnmatched.isEmpty()
                && QuarkusTransaction.requiringNew().call(featureFlag::isMigrationAiEnabled)) {
            aiUsed = true;
            try {
                AiOutcome outcome = proposeWithAi(directory, stillUnmatched);
                aiProposals = outcome.proposals;
                aiNoMatch = outcome.noMatch;
                aiRejected = outcome.rejected;
            } catch (Exception e) {
                // Never fail the matcher run on an OpenAI problem (A5).
                log.errorf(e, "AI match-proposal stage failed — folders stay in the manual queue");
                errors.add("AI stage failed: " + e.getMessage());
            }
        }

        long remaining = QuarkusTransaction.requiringNew().call(() ->
                (long) SharePointMigrationFolder.findUnresolved().size());

        log.infof("Match done: %d USERNAME, %d FULLNAME, %d AI proposals, %d no-match, %d rejected, %d unresolved",
                usernameMatched, fullnameMatched, aiProposals, aiNoMatch, aiRejected, remaining);
        return new MatchSummary(usernameMatched, fullnameMatched, aiProposals, aiNoMatch,
                aiRejected, (int) remaining, aiUsed, errors);
    }

    // ── Stage 3: human resolution (admin card) ─────────────────────────────

    /** One click Confirm on an AI suggestion ⇒ AI_CONFIRMED + MAPPED. */
    public void confirmSuggestion(long folderId) {
        QuarkusTransaction.requiringNew().run(() -> {
            SharePointMigrationFolder folder = requireFolder(folderId);
            if (folder.getAiSuggestedUserUuid() == null) {
                throw new BadRequestException("Folder " + folderId + " has no AI suggestion to confirm");
            }
            if (User.findById(folder.getAiSuggestedUserUuid()) == null) {
                throw new BadRequestException("Suggested user no longer exists");
            }
            folder.setMatchedUserUuid(folder.getAiSuggestedUserUuid());
            folder.setMatchMethod(MatchMethod.AI_CONFIRMED);
            folder.setStatus(FolderStatus.MAPPED);
            folder.persist();
        });
    }

    /** Admin picked a user in the inline picker ⇒ MANUAL + MAPPED. */
    public void mapManually(long folderId, String userUuid) {
        QuarkusTransaction.requiringNew().run(() -> {
            SharePointMigrationFolder folder = requireFolder(folderId);
            if (userUuid == null || User.findById(userUuid) == null) {
                throw new BadRequestException("Unknown user uuid");
            }
            folder.setMatchedUserUuid(userUuid);
            folder.setMatchMethod(MatchMethod.MANUAL);
            folder.setStatus(FolderStatus.MAPPED);
            folder.persist();
        });
    }

    /** Department folder / never-hired person ⇒ SKIPPED with a mandatory note. */
    public void skipFolder(long folderId, String note) {
        QuarkusTransaction.requiringNew().run(() -> {
            SharePointMigrationFolder folder = requireFolder(folderId);
            if (note == null || note.isBlank()) {
                throw new BadRequestException("A note explaining the skip is required");
            }
            folder.setStatus(FolderStatus.SKIPPED);
            folder.setNote(note.length() > 1024 ? note.substring(0, 1024) : note);
            folder.persist();
        });
    }

    private static SharePointMigrationFolder requireFolder(long folderId) {
        SharePointMigrationFolder folder = SharePointMigrationFolder.findById(folderId);
        if (folder == null) throw new NotFoundException("Migration folder not found: " + folderId);
        return folder;
    }

    // ── Stage 1 helpers (package-private for unit tests) ───────────────────

    static DirectoryEntry exactMatch(String folderName,
                                     Map<String, DirectoryEntry> byUsername,
                                     Map<String, DirectoryEntry> byCanonicalName) {
        if (folderName == null || folderName.isBlank()) return null;
        DirectoryEntry username = byUsername.get(folderName.toLowerCase().trim());
        if (username != null) return username;
        return byCanonicalName.get(canonicalName(folderName));
    }

    /**
     * Canonical name form: trim, collapse whitespace, casefold, æ→ae /
     * ø→oe / å→aa. Mapping BOTH sides to the expanded form makes the
     * comparison symmetric in both directions ("Søren" matches "Soeren"
     * and vice versa).
     */
    static String canonicalName(String name) {
        if (name == null) return "";
        return name.toLowerCase()
                .replace("æ", "ae").replace("ø", "oe").replace("å", "aa")
                .replaceAll("\\s+", " ")
                .trim();
    }

    // ── Stage 2: AI proposals ──────────────────────────────────────────────

    private record AiOutcome(int proposals, int noMatch, int rejected) { }

    private AiOutcome proposeWithAi(List<DirectoryEntry> directory, List<Long> folderIds) {
        record PendingFolder(long id, String name) { }
        List<PendingFolder> pending = QuarkusTransaction.requiringNew().call(() ->
                folderIds.stream()
                        .map(id -> (SharePointMigrationFolder) SharePointMigrationFolder.findById(id))
                        .filter(f -> f != null && f.getStatus() == FolderStatus.DISCOVERED
                                && f.getMatchedUserUuid() == null)
                        .map(f -> new PendingFolder(f.getId(), f.getFolderName()))
                        .toList());
        if (pending.isEmpty()) return new AiOutcome(0, 0, 0);

        StringBuilder userMsg = new StringBuilder();
        userMsg.append("EMPLOYEE DIRECTORY (uuid | username | full name | employment):\n");
        for (DirectoryEntry entry : directory) {
            userMsg.append(entry.uuid()).append(" | ")
                    .append(entry.username() == null ? "-" : entry.username()).append(" | ")
                    .append(entry.fullName()).append(" | ")
                    .append(entry.period()).append('\n');
        }
        userMsg.append("\nFOLDERS TO MATCH (folderId | folder name):\n");
        for (PendingFolder folder : pending) {
            userMsg.append(folder.id()).append(" | ").append(folder.name()).append('\n');
        }

        String system = """
                You match SharePoint personal-folder names to employees of a Danish consultancy.
                Folder names are usually a person's full name, sometimes with extra decoration
                (initials, a role suffix in parentheses, misspellings, æ/ø/å written as ae/oe/aa).
                For every folderId, either propose the ONE employee the folder clearly belongs to,
                or return null for userUuid if no single employee is a clear match (e.g. department
                folders, archives, people not in the directory). Never guess between two plausible
                people — return null instead. confidence reflects how certain you are; reason is one
                short sentence a human reviewer reads next to the folder name.""";

        // gpt-5-family models spend max_output_tokens on hidden reasoning
        // before emitting JSON; 8192 was fully consumed by reasoning on the
        // real corpus (44 folders x full directory), yielding an empty
        // response. The cap costs nothing unless used.
        String response = openAIService.askQuestionWithSchema(
                system, userMsg.toString(), matchSchema(), "sharepoint_folder_matches",
                null, aiModel, 32768, false);

        return applyProposals(response, pending.stream()
                .collect(Collectors.toMap(PendingFolder::id, PendingFolder::name)),
                directory.stream().map(DirectoryEntry::uuid).collect(Collectors.toSet()));
    }

    /**
     * Hard server-side validation of the model output (house rule):
     * unknown folderId ⇒ ignored; unknown userUuid ⇒ rejected (counted,
     * folder stays in the manual queue); proposals never change status.
     * Package-private so the validation is unit-testable without OpenAI.
     *
     * @throws IllegalStateException when the response as a whole is unusable
     *         (not JSON, or no matches array — e.g. the model exhausted its
     *         token budget on reasoning and emitted nothing). The caller's
     *         catch turns this into a summary error the admin card shows;
     *         a silent zero-count here reads as "AI found nothing".
     */
    AiOutcome applyProposals(String responseJson, Map<Long, String> pendingById, Set<String> knownUserUuids) {
        JsonNode root;
        try {
            root = objectMapper.readTree(responseJson);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "AI match response was not valid JSON — likely truncated model output", e);
        }
        JsonNode matches = root.path("matches");
        if (!matches.isArray()) {
            throw new IllegalStateException(
                    "AI match response had no matches array — likely empty model output "
                            + "(token budget exhausted by reasoning); re-run Match");
        }
        int proposals = 0;
        int noMatch = 0;
        int rejected = 0;
        for (JsonNode match : matches) {
            long folderId = match.path("folderId").asLong(-1);
            if (!pendingById.containsKey(folderId)) continue;

            JsonNode uuidNode = match.path("userUuid");
            if (uuidNode.isNull() || uuidNode.asText().isBlank()) {
                noMatch++;
                continue;
            }
            String userUuid = uuidNode.asText();
            if (!knownUserUuids.contains(userUuid)) {
                // Hallucinated uuid — reject, never write it anywhere.
                log.warnf("AI proposed unknown userUuid for folder %d — rejected", folderId);
                rejected++;
                continue;
            }
            AiConfidence confidence = parseConfidence(match.path("confidence").asText(null));
            String reason = match.path("reason").asText("");
            QuarkusTransaction.requiringNew().run(() -> {
                SharePointMigrationFolder folder = SharePointMigrationFolder.findById(folderId);
                if (folder == null || folder.getStatus() != FolderStatus.DISCOVERED
                        || folder.getMatchedUserUuid() != null) {
                    return;
                }
                folder.setAiSuggestedUserUuid(userUuid);
                folder.setAiConfidence(confidence);
                folder.setAiReason(reason.length() > 512 ? reason.substring(0, 512) : reason);
                // Status deliberately stays DISCOVERED (decision A2).
                folder.persist();
            });
            proposals++;
        }
        return new AiOutcome(proposals, noMatch, rejected);
    }

    static AiConfidence parseConfidence(String raw) {
        if (raw == null) return AiConfidence.LOW;
        try {
            return AiConfidence.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return AiConfidence.LOW;
        }
    }

    private ObjectNode matchSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.putArray("required").add("matches");
        ObjectNode matches = schema.putObject("properties").putObject("matches");
        matches.put("type", "array");
        ObjectNode item = matches.putObject("items");
        item.put("type", "object");
        item.put("additionalProperties", false);
        item.putArray("required").add("folderId").add("userUuid").add("confidence").add("reason");
        ObjectNode props = item.putObject("properties");
        props.putObject("folderId").put("type", "integer");
        ObjectNode userUuid = props.putObject("userUuid");
        userUuid.putArray("type").add("string").add("null");
        props.putObject("confidence").put("type", "string")
                .putArray("enum").add("HIGH").add("MEDIUM").add("LOW");
        props.putObject("reason").put("type", "string");
        return schema;
    }

    // ── Employee directory (decision D2: active AND terminated) ────────────

    public List<DirectoryEntry> loadDirectory() {
        List<User> users = User.listAll();
        Map<String, List<UserStatus>> statusesByUser = UserStatus.<UserStatus>listAll().stream()
                .collect(Collectors.groupingBy(UserStatus::getUseruuid));

        List<DirectoryEntry> directory = new ArrayList<>();
        for (User user : users) {
            String fullName = ((user.getFirstname() == null ? "" : user.getFirstname()) + " "
                    + (user.getLastname() == null ? "" : user.getLastname())).trim();
            if (fullName.isBlank() && (user.getUsername() == null || user.getUsername().isBlank())) {
                continue;
            }
            directory.add(new DirectoryEntry(user.getUuid(), user.getUsername(), fullName,
                    employmentPeriod(statusesByUser.get(user.getUuid()))));
        }
        return directory;
    }

    static String employmentPeriod(List<UserStatus> statuses) {
        if (statuses == null || statuses.isEmpty()) return "employment unknown";
        List<UserStatus> sorted = statuses.stream()
                .sorted(Comparator.comparing(UserStatus::getStatusdate))
                .toList();
        LocalDate firstActive = sorted.stream()
                .filter(s -> s.getStatus() == StatusType.ACTIVE)
                .map(UserStatus::getStatusdate)
                .findFirst().orElse(null);
        UserStatus latest = sorted.get(sorted.size() - 1);
        if (latest.getStatus() == StatusType.TERMINATED) {
            return (firstActive == null ? "?" : firstActive) + " to " + latest.getStatusdate() + " (former)";
        }
        return (firstActive == null ? "?" : firstActive) + " to present (active)";
    }
}
