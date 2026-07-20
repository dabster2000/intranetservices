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
import java.util.UUID;

/**
 * Application service over the practice registry (V418). Keeps the code strings
 * (PM/BA/SA/DEV/CYB) as the universal key while adding a data-driven layer for
 * naming, leadership and team grouping.
 * <p>
 * Registry-driven write validation for {@code user.practice} and
 * {@code team.practice_code} lives here; the pure decision logic is factored into
 * static package-visible helpers ({@link #userPracticeRejection},
 * {@link #teamPracticeCodeRejection}) so it can be unit-tested without a database.
 */
@JBossLog
@ApplicationScoped
public class PracticeService {

    /** Sentinel "no practice" code stored on {@code user.practice}. */
    static final String NO_PRACTICE_CODE = "UD";

    /** Retired junior pseudo-practice — rejected on every write from V418 onward. */
    static final String JK_CODE = "JK";

    /** Registry type discriminator for real practices (vs SEGMENT). */
    static final String PRACTICE_TYPE = "PRACTICE";

    /** The only values the practice.type ENUM column accepts. */
    static final java.util.Set<String> VALID_TYPES = java.util.Set.of("PRACTICE", "SEGMENT");

    /** Codes are short uppercase keys — also keeps them URI- and ENUM-safe. */
    static final java.util.regex.Pattern CODE_PATTERN = java.util.regex.Pattern.compile("[A-Z0-9_-]{1,10}");

    // ── Registry reads ────────────────────────────────────────────────────

    /** All registry rows (incl. inactive + SEGMENT) ordered by sort order — the frontend filters. */
    public List<Practice> findAll() {
        return Practice.listAll(Sort.by("sortOrder"));
    }

    /**
     * Storage codes of the active {@code type='PRACTICE'} rows, in registry
     * {@code sort_order}. The registry-derived successor of the deleted
     * {@code UtilizationCalculationHelper.BILLABLE_PRACTICES} constant (Phase 3):
     * the default population for the practice-filtered utilization analytics.
     */
    public List<String> activePracticeCodes() {
        return Practice.<Practice>list("type = ?1 and active = true order by sortOrder", PRACTICE_TYPE)
                .stream().map(Practice::getCode).toList();
    }

    /**
     * Storage codes of ALL registry rows (incl. {@code UD} and inactive rows) in
     * registry {@code sort_order} — the grouping/ordering universe for
     * practice-dimensioned analytics (career matrix, compensation groups).
     * Replaces the deleted enum's {@code values()} declaration order; unlike
     * {@link #activePracticeCodes()} it keeps the {@code UD} bucket, whose users
     * are real data in those groupings until the Phase 4 operational-NULL flip.
     */
    public List<String> orderedRegistryCodes() {
        return findAll().stream().map(Practice::getCode).toList();
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
     * Maps a uuid-or-code identifier to its storage code for query filters,
     * passing unresolvable values through unchanged. Used by the list-shaped
     * lookups ({@code /practices/{id}/leads}, {@code /practices/{id}/teams})
     * whose contract is an empty list — never a 404 — for unknown identifiers.
     */
    public String resolveToCodeOrPassthrough(String id) {
        Practice practice = resolveByIdOrCode(id);
        return practice != null ? practice.getCode() : id;
    }

    /**
     * Resolves one {@code practices=} filter token — a registry uuid (canonical)
     * or a storage code (alias) — to its storage code. Any registry row resolves,
     * including {@code UD} and inactive rows: filters are reads, and the widest
     * backward-compatible universe is the whole registry. Retired codes with no
     * registry row (e.g. {@code JK}) do not resolve.
     */
    public java.util.Optional<String> resolveFilterToken(String token) {
        if (token == null || token.isBlank()) return java.util.Optional.empty();
        String trimmed = token.trim();
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

    public List<PracticeLead> findLeads(String code) {
        return PracticeLead.list("practiceCode = ?1 order by startdate", code);
    }

    public List<Team> findTeams(String code) {
        return Team.list("practiceCode = ?1", code);
    }

    // ── Registry mutations ────────────────────────────────────────────────

    @Transactional
    public Practice create(String code, String displayCode, String name, String type, Boolean active, Integer sortOrder) {
        if (code == null || !CODE_PATTERN.matcher(code).matches()) {
            throw new BadRequestException("code is required and must match " + CODE_PATTERN.pattern());
        }
        if (displayCode == null || !CODE_PATTERN.matcher(displayCode).matches()) {
            throw new BadRequestException("displayCode is required and must match " + CODE_PATTERN.pattern());
        }
        if (name == null || name.isBlank()) throw new BadRequestException("name is required");
        if (Practice.findById(code) != null) throw new BadRequestException("Practice code already exists: " + code);
        if (Practice.count("displayCode = ?1", displayCode) > 0) {
            throw new BadRequestException("Practice displayCode already exists: " + displayCode);
        }
        String resolvedType = (type == null || type.isBlank()) ? PRACTICE_TYPE : type;
        if (!VALID_TYPES.contains(resolvedType)) {
            throw new BadRequestException("type must be one of " + VALID_TYPES);
        }
        Practice practice = new Practice(
                code, displayCode, name, resolvedType,
                active == null || active,
                sortOrder == null ? 0 : sortOrder);
        // Surrogate identity (V424): existing rows are minted by the migration;
        // new rows get their uuid here (the column is NOT NULL, insertable).
        practice.setUuid(UUID.randomUUID().toString());
        Practice.persist(practice);
        return practice;
    }

    @Transactional
    public Practice update(String code, String name, String displayCode, Boolean active, Integer sortOrder) {
        Practice practice = Practice.findById(code);
        if (practice == null) throw new NotFoundException("Practice not found: " + code);
        if (displayCode != null && !displayCode.equals(practice.getDisplayCode())) {
            if (!CODE_PATTERN.matcher(displayCode).matches()) {
                throw new BadRequestException("displayCode must match " + CODE_PATTERN.pattern());
            }
            if (Practice.count("displayCode = ?1 and code <> ?2", displayCode, code) > 0) {
                throw new BadRequestException("Practice displayCode already exists: " + displayCode);
            }
            practice.setDisplayCode(displayCode);
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
    public PracticeLead startLead(String code, String useruuid, LocalDate startdate) {
        Practice practice = Practice.findById(code);
        if (practice == null) throw new NotFoundException("Practice not found: " + code);
        // Phase 0 hardening: leads attach only to active type='PRACTICE' rows
        // (never UD/SEGMENT rows, never inactive rows).
        String practiceRejection = leadPracticeRejection(code, practice.getType(), practice.isActive());
        if (practiceRejection != null) throw new BadRequestException(practiceRejection);
        if (useruuid == null || useruuid.isBlank()) throw new BadRequestException("useruuid is required");
        // practice_lead.useruuid has no FK to user — validate existence here instead.
        if (dk.trustworks.intranet.domain.user.entity.User.findById(useruuid) == null) {
            throw new BadRequestException("Unknown user: " + useruuid);
        }
        if (startdate == null) throw new BadRequestException("startdate is required");
        // A new lead is open-ended [startdate, ∞) — reject if it overlaps any
        // existing row for the same user + practice. Concurrent leads across
        // DIFFERENT users remain an intentional feature.
        List<PracticeLead> sameUserLeads = PracticeLead.list("practiceCode = ?1 and useruuid = ?2", code, useruuid);
        String overlapRejection = leadOverlapRejection(startdate, null, null, sameUserLeads);
        if (overlapRejection != null) throw new BadRequestException(overlapRejection);
        PracticeLead lead = new PracticeLead(UUID.randomUUID().toString(), code, useruuid, startdate, null);
        // Dual-key window: the app maintains the uuid twin on new rows (Phase 3).
        lead.setPracticeUuid(practice.getUuid());
        PracticeLead.persist(lead);
        return lead;
    }

    @Transactional
    public PracticeLead endLead(String code, String uuid, LocalDate enddate) {
        PracticeLead lead = PracticeLead.findById(uuid);
        if (lead == null || !lead.getPracticeCode().equals(code)) {
            throw new NotFoundException("Practice lead not found: " + uuid);
        }
        if (enddate == null) throw new BadRequestException("enddate is required");
        String enddateRejection = leadEnddateRejection(lead.getStartdate(), enddate);
        if (enddateRejection != null) throw new BadRequestException(enddateRejection);
        // Moving the enddate must not make [startdate, enddate) overlap another
        // row of the same user + practice (extending past a successor period).
        List<PracticeLead> sameUserLeads = PracticeLead.list("practiceCode = ?1 and useruuid = ?2", code, lead.getUseruuid());
        String overlapRejection = leadOverlapRejection(lead.getStartdate(), enddate, uuid, sameUserLeads);
        if (overlapRejection != null) throw new BadRequestException(overlapRejection);
        lead.setEnddate(enddate);
        return lead;
    }

    // ── Write validation ──────────────────────────────────────────────────

    /**
     * Validates a {@code user.practice} storage-code value on write: null/blank
     * is allowed (no practice), {@code UD} is allowed (sentinel), {@code JK} is
     * rejected, and any other value must be an active {@code type='PRACTICE'}
     * registry row. Re-typed from the deleted {@code PrimarySkillType} enum in
     * Phase 3 — semantics identical, the registry is the only authority.
     */
    public void validateUserPracticeAssignable(String practice) {
        if (practice == null || practice.isBlank()) return;
        String rejection = userPracticeRejection(practice, isActivePractice(practice));
        if (rejection != null) throw new BadRequestException(rejection);
    }

    /**
     * Validates a {@code team.practice_code} value on write: null/blank clears
     * the assignment; any other value must be an active {@code type='PRACTICE'}
     * registry row (teams never carry the {@code UD} sentinel — they use NULL).
     */
    public void validateTeamPracticeCode(String code) {
        if (code == null || code.isBlank()) return;
        String rejection = teamPracticeCodeRejection(code, isActivePractice(code));
        if (rejection != null) throw new BadRequestException(rejection);
    }

    private boolean isActivePractice(String code) {
        return Practice.count("code = ?1 and type = ?2 and active = true", code, PRACTICE_TYPE) > 0;
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
     * {@code type='PRACTICE'} registry row — never a SEGMENT (UD) and never an
     * inactive row (Phase 0 hardening, spec §1.6.E). Returns a rejection reason,
     * or {@code null} if the practice can carry leads.
     */
    static String leadPracticeRejection(String code, String type, boolean active) {
        if (!PRACTICE_TYPE.equals(type)) {
            return "Practice '" + code + "' is not a real practice (type " + type + ") and cannot have leads";
        }
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
