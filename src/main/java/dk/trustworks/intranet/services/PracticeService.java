package dk.trustworks.intranet.services;

import dk.trustworks.intranet.domain.user.entity.Team;
import dk.trustworks.intranet.model.Practice;
import dk.trustworks.intranet.model.PracticeLead;
import dk.trustworks.intranet.utils.DateUtils;
import io.quarkus.panache.common.Sort;
import lombok.extern.jbosslog.JBossLog;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Application service over the practice registry (V418). Since Phase 5A the
 * registry uuid is the only persisted practice key anywhere — codes are display
 * attributes and filter/write aliases resolved through this service.
 * <p>
 * Registry-driven write validation for the user- and team-practice writes lives
 * here; the pure decision logic is factored into static package-visible helpers
 * ({@link #userPracticeRejection}, {@link #teamPracticeCodeRejection}) so it can
 * be unit-tested without a database.
 */
@JBossLog
@ApplicationScoped
public class PracticeService {

    /**
     * The permanent no-practice MEMBER TOKEN (Phase 5 graduation of the former
     * UD registry row / stored sentinel): the warehouse member code the views
     * synthesize for NULL practice, the filter token selecting the no-practice
     * population, and the write alias meaning "no practice" — one coherent,
     * registry-INDEPENDENT concept. The UD registry row itself is deleted by
     * V429; only this literal survives.
     */
    static final String NO_PRACTICE_CODE = "UD";

    /** Retired junior pseudo-practice — rejected on every write from V418 onward. */
    static final String JK_CODE = "JK";

    /**
     * The single value {@code type} has resolved to since V429 deleted the UD
     * row, the only SEGMENT. Kept solely to validate the optional wire field on
     * {@link #create}: the column is unread since V431 and dropped by V432.
     */
    static final String PRACTICE_TYPE = "PRACTICE";

    /** Codes are short uppercase keys — also keeps them URI- and ENUM-safe. */
    static final java.util.regex.Pattern CODE_PATTERN = java.util.regex.Pattern.compile("[A-Z0-9_-]{1,10}");

    // ── Registry reads ────────────────────────────────────────────────────

    /** All registry rows (incl. inactive + SEGMENT) ordered by sort order — the frontend filters. */
    public List<Practice> findAll() {
        return Practice.listAll(Sort.by("sortOrder"));
    }

    /**
     * Storage codes of the active registry rows, in registry {@code sort_order}.
     * The registry-derived successor of the deleted
     * {@code UtilizationCalculationHelper.BILLABLE_PRACTICES} constant (Phase 3):
     * the default population for the practice-filtered utilization analytics.
     * Until V431 this also required {@code type = 'PRACTICE'} — dropped
     * deliberately: V429 deleted the UD row (the only SEGMENT), so every
     * registry row is a practice and the entity no longer maps a type field.
     */
    public List<String> activePracticeCodes() {
        return Practice.<Practice>list("active = true order by sortOrder")
                .stream().map(Practice::getCode).toList();
    }

    /**
     * Codes of ALL registry rows (incl. inactive) in registry {@code sort_order},
     * with the no-practice MEMBER TOKEN ({@code 'UD'}) appended last — the
     * grouping/ordering universe for practice-dimensioned analytics (career
     * matrix, compensation groups), whose no-practice population groups under
     * the member. The append is registry-independent (Phase 5 graduation): while
     * the UD registry row still exists (sort_order 90, last) it already appears
     * in the list and the append is a no-op; after V429 deleted the row, the
     * token keeps its last-place bucket with no behavior change.
     */
    public List<String> orderedRegistryCodes() {
        List<String> codes = new java.util.ArrayList<>(findAll().stream().map(Practice::getCode).toList());
        if (!codes.contains(NO_PRACTICE_CODE)) codes.add(NO_PRACTICE_CODE);
        return List.copyOf(codes);
    }

    /**
     * Resolves a practice identified by uuid (canonical, §4.5) or storage code
     * (compatibility alias until Phase 5). Uuid is tried first; both matches are
     * case-insensitive on the uuid, exact on the code.
     *
     * @return the registry row, or {@code null} when nothing matches
     */
    public Practice resolveByIdOrCode(String id) {
        if (id == null || id.isBlank()) return null;
        Practice byUuid = Practice.find("lower(uuid) = ?1", id.toLowerCase(java.util.Locale.ROOT)).firstResult();
        if (byUuid != null) return byUuid;
        return Practice.findById(id);
    }

    /**
     * Maps a uuid-or-code identifier to the registry uuid for query filters,
     * passing unresolvable values through unchanged. Used by the list-shaped
     * lookups ({@code /practices/{id}/leads}, {@code /practices/{id}/teams})
     * whose contract is an empty list — never a 404 — for unknown identifiers
     * (a passed-through garbage value matches no {@code practice_uuid} row).
     */
    public String resolveToUuidOrPassthrough(String id) {
        Practice practice = resolveByIdOrCode(id);
        return practice != null ? practice.getUuid() : id;
    }

    /**
     * Normalizes an incoming practice value on a WRITE path to the operational
     * representation: {@code null}/blank and the {@code 'UD'} member token —
     * the PERMANENT no-practice write alias (Phase 5 graduation; the forms
     * submit it for an explicit "no practice") — normalize to {@code null}.
     * The token check is a registry-independent literal. The UD registry ROW's
     * uuid additionally normalizes while that row still exists (pre-V429); the
     * uuid alias dies with the row — clients must send the token or null. Any
     * other value passes through unchanged (validation is a separate concern).
     * Callers: {@code UserService}, {@code SalesService},
     * {@code PracticeSyncService.applyManualPractice}, {@code QuestionnaireService}.
     */
    public String normalizeNoPracticeAlias(String practice) {
        if (practice == null || practice.isBlank()) return null;
        String trimmed = practice.trim();
        if (NO_PRACTICE_CODE.equalsIgnoreCase(trimmed)) return null;
        Practice udRow = Practice.<Practice>find("code", NO_PRACTICE_CODE).firstResult();
        if (udRow != null && udRow.getUuid() != null && udRow.getUuid().equalsIgnoreCase(trimmed)) return null;
        return trimmed;
    }

    /**
     * Resolves one {@code practices=} filter token — a registry uuid (canonical),
     * a registry code (alias), or the {@code 'UD'} no-practice MEMBER TOKEN —
     * to its member/storage code. The {@code UD} token is a registry-INDEPENDENT
     * literal (Phase 5 graduation: it selects the NULL no-practice population on
     * operational tables and the synthetic member on warehouse tables, and must
     * keep resolving after V429 deleted the UD registry row). Any registry row
     * resolves, including inactive rows: filters are reads, and the widest
     * backward-compatible universe is the whole registry. Retired codes with no
     * registry row (JK now; SA/BA/DEV after the V429 rename) do not resolve.
     */
    public java.util.Optional<String> resolveFilterToken(String token) {
        if (token == null || token.isBlank()) return java.util.Optional.empty();
        String trimmed = token.trim();
        if (NO_PRACTICE_CODE.equalsIgnoreCase(trimmed)) return java.util.Optional.of(NO_PRACTICE_CODE);
        Practice byUuid = Practice.find("lower(uuid) = ?1", trimmed.toLowerCase(java.util.Locale.ROOT)).firstResult();
        if (byUuid != null) return java.util.Optional.of(byUuid.getCode());
        Practice byCode = Practice.findById(trimmed.toUpperCase(java.util.Locale.ROOT));
        return byCode != null ? java.util.Optional.of(byCode.getCode()) : java.util.Optional.empty();
    }

    /**
     * Validates and normalizes a caller-supplied practice filter set (codes or
     * uuids, §4.5) to storage codes, preserving iteration order. {@code null}
     * or empty input passes through as {@code null} ("no filter supplied" — the
     * endpoints then apply their registry-derived default population).
     *
     * @throws BadRequestException on any token that matches no registry row
     */
    public java.util.Set<String> normalizePracticeFilter(java.util.Set<String> tokens) {
        if (tokens == null || tokens.isEmpty()) return null;
        java.util.Set<String> codes = new java.util.LinkedHashSet<>();
        for (String token : tokens) {
            codes.add(resolveFilterToken(token).orElseThrow(() -> new BadRequestException(
                    "Invalid practices value '" + token + "'; expected a practice uuid or storage code from the registry")));
        }
        return codes;
    }

    public List<PracticeLead> findLeads(String practiceUuid) {
        return PracticeLead.list("practiceUuid = ?1 order by startdate", practiceUuid);
    }

    public List<Team> findTeams(String practiceUuid) {
        return Team.list("practiceUuid = ?1", practiceUuid);
    }

    // ── Registry mutations ────────────────────────────────────────────────

    /**
     * {@code displayCode} and {@code type} remain in the request contract for
     * wire compatibility (V431), but neither is persisted anymore: displayCode
     * is derived from {@code code} and type is the constant {@code PRACTICE}.
     * Both are OPTIONAL — echoing the derived value is accepted (the BFF create
     * route sends {@code displayCode == code}), a DIVERGENT value is a 400: the
     * field can no longer store it, and silently returning a row that
     * contradicts the request would hide that.
     */
    @Transactional
    public Practice create(String code, String displayCode, String name, String type, Boolean active, Integer sortOrder) {
        if (code == null || !CODE_PATTERN.matcher(code).matches()) {
            throw new BadRequestException("code is required and must match " + CODE_PATTERN.pattern());
        }
        if (displayCode != null && !displayCode.isBlank() && !displayCode.equals(code)) {
            throw new BadRequestException(
                    "displayCode is derived from code since V431 — omit it or send the same value as code");
        }
        if (type != null && !type.isBlank() && !PRACTICE_TYPE.equals(type)) {
            throw new BadRequestException(
                    "type is always " + PRACTICE_TYPE + " since V429 retired the last SEGMENT row — omit it");
        }
        if (name == null || name.isBlank()) throw new BadRequestException("name is required");
        if (Practice.findById(code) != null) throw new BadRequestException("Practice code already exists: " + code);
        Practice practice = new Practice(
                code, name,
                active == null || active,
                sortOrder == null ? 0 : sortOrder);
        // Surrogate identity (V424): existing rows are minted by the migration;
        // new rows get their uuid here (the column is NOT NULL, insertable).
        practice.setUuid(UUID.randomUUID().toString());
        Practice.persist(practice);
        return practice;
    }

    /**
     * {@code displayCode} stays in the request contract for wire compatibility
     * (V431) but is no longer an independently stored value: echoing the
     * current code is a no-op (the admin edit form always sends the field), a
     * divergent value is a 400 — see {@link #create}.
     */
    @Transactional
    public Practice update(String code, String name, String displayCode, Boolean active, Integer sortOrder) {
        Practice practice = Practice.findById(code);
        if (practice == null) throw new NotFoundException("Practice not found: " + code);
        if (displayCode != null && !displayCode.isBlank() && !displayCode.equals(practice.getCode())) {
            throw new BadRequestException(
                    "displayCode is derived from code since V431 and cannot diverge — omit it or send the current code");
        }
        if (name != null) {
            if (name.isBlank()) throw new BadRequestException("name must not be blank");
            practice.setName(name);
        }
        if (active != null) practice.setActive(active);
        if (sortOrder != null) practice.setSortOrder(sortOrder);
        return practice;
    }

    @Transactional
    public PracticeLead startLead(Practice practice, String useruuid, LocalDate startdate) {
        if (practice == null) throw new NotFoundException("Practice not found");
        // Phase 0 hardening: leads attach only to active registry rows. (The
        // SEGMENT rejection retired with the type column — V429 deleted the
        // last SEGMENT row, so the registry cannot hold one anymore.)
        String practiceRejection = leadPracticeRejection(practice.getCode(), practice.isActive());
        if (practiceRejection != null) throw new BadRequestException(practiceRejection);
        if (useruuid == null || useruuid.isBlank()) throw new BadRequestException("useruuid is required");
        // practice_lead.useruuid FK exists (V425), but validate here for a clean 400.
        if (dk.trustworks.intranet.domain.user.entity.User.findById(useruuid) == null) {
            throw new BadRequestException("Unknown user: " + useruuid);
        }
        if (startdate == null) throw new BadRequestException("startdate is required");
        // A new lead is open-ended [startdate, ∞) — reject if it overlaps any
        // existing row for the same user + practice. Concurrent leads across
        // DIFFERENT users remain an intentional feature.
        List<PracticeLead> sameUserLeads = PracticeLead.list("practiceUuid = ?1 and useruuid = ?2",
                practice.getUuid(), useruuid);
        String overlapRejection = leadOverlapRejection(startdate, null, null, sameUserLeads);
        if (overlapRejection != null) throw new BadRequestException(overlapRejection);
        // Phase 5A: the uuid is the only persisted key; the code rides along
        // in memory so the create response carries it (the field is a formula).
        PracticeLead lead = new PracticeLead(UUID.randomUUID().toString(),
                practice.getUuid(), practice.getCode(), useruuid, startdate, null);
        PracticeLead.persist(lead);
        return lead;
    }

    @Transactional
    public PracticeLead endLead(Practice practice, String uuid, LocalDate enddate) {
        PracticeLead lead = PracticeLead.findById(uuid);
        if (lead == null || practice == null || !Objects.equals(lead.getPracticeUuid(), practice.getUuid())) {
            throw new NotFoundException("Practice lead not found: " + uuid);
        }
        if (enddate == null) throw new BadRequestException("enddate is required");
        String enddateRejection = leadEnddateRejection(lead.getStartdate(), enddate);
        if (enddateRejection != null) throw new BadRequestException(enddateRejection);
        // Moving the enddate must not make [startdate, enddate) overlap another
        // row of the same user + practice (extending past a successor period).
        List<PracticeLead> sameUserLeads = PracticeLead.list("practiceUuid = ?1 and useruuid = ?2",
                practice.getUuid(), lead.getUseruuid());
        String overlapRejection = leadOverlapRejection(lead.getStartdate(), enddate, uuid, sameUserLeads);
        if (overlapRejection != null) throw new BadRequestException(overlapRejection);
        lead.setEnddate(enddate);
        return lead;
    }

    // ── Write validation ──────────────────────────────────────────────────

    /**
     * Validates a {@code user.practice} storage-code value on write: null/blank
     * is a valid "no practice" (stored as NULL since Phase 4), {@code UD} is
     * allowed as the deprecated no-practice ALIAS (normalized to NULL by
     * {@link #normalizeNoPracticeAlias}; removed in Phase 5 with the registry
     * row), {@code JK} is rejected, and any other value must be an active
     * registry row. Re-typed from the deleted {@code PrimarySkillType} enum in
     * Phase 3 — the registry is the only authority.
     */
    public void validateUserPracticeAssignable(String practice) {
        if (practice == null || practice.isBlank()) return;
        String rejection = userPracticeRejection(practice, isActivePractice(practice));
        if (rejection != null) throw new BadRequestException(rejection);
    }

    /**
     * Validates a {@code team.practice_code} value on write: null/blank clears
     * the assignment; any other value must be an active registry row (teams
     * never carry the {@code UD} sentinel — they use NULL).
     */
    public void validateTeamPracticeCode(String code) {
        if (code == null || code.isBlank()) return;
        String rejection = teamPracticeCodeRejection(code, isActivePractice(code));
        if (rejection != null) throw new BadRequestException(rejection);
    }

    private boolean isActivePractice(String code) {
        return Practice.count("code = ?1 and active = true", code) > 0;
    }

    /**
     * Pure decision for {@link #validateUserPracticeAssignable}. Returns a
     * rejection reason, or {@code null} if the value is assignable.
     *
     * @param code                  the practice code (already resolved from the enum, may be null)
     * @param activePracticeRowExists whether an active {@code type='PRACTICE'} registry row exists for {@code code}
     */
    static String userPracticeRejection(String code, boolean activePracticeRowExists) {
        if (code == null || code.isBlank()) return null;         // no practice — allowed
        if (NO_PRACTICE_CODE.equals(code)) return null;          // sentinel — allowed
        if (JK_CODE.equals(code)) return "Practice 'JK' is retired and cannot be assigned";
        if (!activePracticeRowExists) return "Practice '" + code + "' is not an active practice";
        return null;
    }

    /**
     * Pure decision for {@link #validateTeamPracticeCode}. Returns a rejection
     * reason, or {@code null} if the value is assignable.
     */
    static String teamPracticeCodeRejection(String code, boolean activePracticeRowExists) {
        if (code == null || code.isBlank()) return null;         // clears the assignment
        if (!activePracticeRowExists) return "Practice '" + code + "' is not an active practice";
        return null;
    }

    /**
     * Pure decision for {@link #startLead}: a lead may only attach to an active
     * registry row (Phase 0 hardening, spec §1.6.E). The historical SEGMENT
     * rejection is structurally impossible since V429 deleted the UD row — the
     * registry holds nothing but practices, so only inactivity can reject.
     * Returns a rejection reason, or {@code null} if the practice can carry leads.
     */
    static String leadPracticeRejection(String code, boolean active) {
        if (!active) return "Practice '" + code + "' is not active and cannot have leads";
        return null;
    }

    /**
     * Pure decision for {@link #endLead}: the half-open period [startdate,
     * enddate) must not be negative. {@code enddate == startdate} is allowed —
     * a zero-length period that retracts a lead that never took effect.
     */
    static String leadEnddateRejection(LocalDate startdate, LocalDate enddate) {
        if (enddate.isBefore(startdate)) {
            return "enddate " + enddate + " must be on or after startdate " + startdate;
        }
        return null;
    }

    /**
     * Pure decision for the same-user overlap guard on {@link #startLead} /
     * {@link #endLead}: the candidate period [startdate, enddate) (null enddate
     * = open-ended) must not overlap any other lead row of the same user +
     * practice. Concurrent co-leads across DIFFERENT users are an intentional
     * feature and never reach this check. Returns a rejection reason, or
     * {@code null} if the period is free.
     *
     * @param excludeUuid the row being edited (skipped), or {@code null} on create
     * @param sameUserLeads all lead rows for the same user + practice
     */
    static String leadOverlapRejection(LocalDate startdate, LocalDate enddate,
                                       String excludeUuid, List<PracticeLead> sameUserLeads) {
        for (PracticeLead other : sameUserLeads) {
            if (other.getUuid().equals(excludeUuid)) continue;
            if (DateUtils.periodsOverlap(startdate, enddate, other.getStartdate(), other.getEnddate())) {
                return "Lead period overlaps this user's existing lead " + other.getUuid()
                        + " [" + other.getStartdate() + " – "
                        + (other.getEnddate() == null ? "open" : other.getEnddate()) + ")";
            }
        }
        return null;
    }
}
