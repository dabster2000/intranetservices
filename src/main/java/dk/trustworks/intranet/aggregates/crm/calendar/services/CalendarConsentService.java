package dk.trustworks.intranet.aggregates.crm.calendar.services;

import dk.trustworks.intranet.aggregates.crm.calendar.dto.CalendarConsentDTO;
import dk.trustworks.intranet.aggregates.crm.calendar.model.UserCalendarConsent;
import dk.trustworks.intranet.domain.user.entity.Role;
import dk.trustworks.intranet.domain.user.entity.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Who has agreed to let Intra read their calendar's metadata (CRM spec §3.7).
 *
 * <h2>The rule, decided 2026-09-13</h2>
 * SALES, PARTNER and ADMIN are on by default — meeting clients is the job those roles do,
 * and a relationship graph missing exactly those people would be useless on day one.
 * Everybody else is off until they turn it on themselves, from their own profile page.
 *
 * <p>An <b>absent row means the role default</b>; a <b>present row is always an explicit
 * decision</b> and always wins. So a partner who turns it off stays off even though their
 * role says otherwise, and a consultant who turns it on stays on. A role change does not
 * silently reverse somebody's decision.
 *
 * <h2>What consent buys</h2>
 * The Graph app registration can read every mailbox in the tenant; nothing technical stops
 * it. This table is the rule Intra imposes on itself, and {@link #consentedUserUuids()} is
 * the only list the sync job is allowed to read from. That makes the consent real in the
 * one place it can be made real — the code that decides which mailboxes to open.
 */
@JBossLog
@ApplicationScoped
public class CalendarConsentService {

    /** Roles whose work is client contact. On unless the person says otherwise. */
    static final Set<String> DEFAULT_ON_ROLES = Set.of("SALES", "PARTNER", "ADMIN");

    /** The signed-in person's own setting, with the reason it is what it is. */
    public CalendarConsentDTO readFor(String userUuid) {
        requireUser(userUuid);
        UserCalendarConsent row = UserCalendarConsent.findById(userUuid);
        boolean roleDefault = roleDefaultFor(userUuid);
        if (row == null) {
            return new CalendarConsentDTO(userUuid, roleDefault, false, roleDefault, null);
        }
        return new CalendarConsentDTO(userUuid, row.isEnabled(), true, roleDefault, row.getDecidedAt());
    }

    /**
     * Records a person's own decision.
     *
     * @param userUuid the person deciding, which must be the acting person — the resource
     *                 enforces that, so nobody sets consent on somebody else's behalf
     */
    @Transactional
    public CalendarConsentDTO decide(String userUuid, boolean enabled) {
        requireUser(userUuid);
        UserCalendarConsent row = UserCalendarConsent.findById(userUuid);
        if (row == null) {
            row = new UserCalendarConsent();
            row.setUserUuid(userUuid);
        }
        row.setEnabled(enabled);
        row.setDecidedAt(LocalDateTime.now());
        row.setDecidedBy(userUuid);
        row.persist();

        log.infof("Calendar consent set: user=%s enabled=%s", userUuid, enabled);
        return readFor(userUuid);
    }

    /** Whether one person's calendar may be read. */
    public boolean isEnabled(String userUuid) {
        if (userUuid == null || userUuid.isBlank()) {
            return false;
        }
        UserCalendarConsent row = UserCalendarConsent.findById(userUuid.trim());
        return row == null ? roleDefaultFor(userUuid.trim()) : row.isEnabled();
    }

    /**
     * Every mailbox the sync job may open: explicit opt-ins, plus the role defaults, minus
     * every explicit opt-out.
     *
     * <p>Order matters. The explicit rows are applied LAST so that a partner who opted out
     * is removed again after the role default put them in.
     */
    public Set<String> consentedUserUuids() {
        Set<String> enabled = new LinkedHashSet<>();

        List<Role> roles = Role.list("role in ?1", List.copyOf(DEFAULT_ON_ROLES));
        for (Role role : roles) {
            if (role.getUseruuid() != null && !role.getUseruuid().isBlank()) {
                enabled.add(role.getUseruuid());
            }
        }

        for (UserCalendarConsent row : UserCalendarConsent.<UserCalendarConsent>listAll()) {
            if (row.isEnabled()) {
                enabled.add(row.getUserUuid());
            } else {
                enabled.remove(row.getUserUuid());
            }
        }
        return enabled;
    }

    boolean roleDefaultFor(String userUuid) {
        for (Role role : Role.findByUseruuid(userUuid)) {
            if (role != null && DEFAULT_ON_ROLES.contains(role.getRole())) {
                return true;
            }
        }
        return false;
    }

    private static void requireUser(String userUuid) {
        if (userUuid == null || userUuid.isBlank()) {
            throw new WebApplicationException("A user uuid is required", Response.Status.BAD_REQUEST);
        }
        if (User.<User>findById(userUuid.trim()) == null) {
            throw new WebApplicationException("Unknown user", Response.Status.NOT_FOUND);
        }
    }
}
