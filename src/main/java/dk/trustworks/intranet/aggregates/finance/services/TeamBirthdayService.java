package dk.trustworks.intranet.aggregates.finance.services;

import dk.trustworks.intranet.aggregates.finance.dto.TeamBirthdayDTO;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Upcoming birthdays among a team's current members (JK Team 2.0 WP6 §4.6.6, D5).
 *
 * <p>Reads {@code user.birthday} directly — the field is already on the user table and is
 * not stripped by {@code UserScopeResponseFilter}, so this creates no new exposure. Day and
 * month only leave this service; the year never does.
 */
@ApplicationScoped
public class TeamBirthdayService {

    public static final int DEFAULT_WINDOW_DAYS = 30;

    @Inject
    EntityManager em;

    public List<TeamBirthdayDTO> upcoming(Set<String> memberUuids, LocalDate today, int windowDays) {
        if (memberUuids == null || memberUuids.isEmpty()) return List.of();
        @SuppressWarnings("unchecked")
        List<Tuple> rows = em.createNativeQuery("""
                SELECT u.uuid, u.firstname, u.lastname, u.birthday
                FROM user u
                WHERE u.uuid IN (:memberUuids) AND u.birthday IS NOT NULL
                """, Tuple.class)
                .setParameter("memberUuids", memberUuids)
                .getResultList();

        List<TeamBirthdayDTO> result = new ArrayList<>();
        for (Tuple row : rows) {
            LocalDate birthday = toLocalDate(row.get("birthday"));
            if (birthday == null) continue;
            int daysUntil = daysUntilNext(birthday, today);
            if (daysUntil > windowDays) continue;
            result.add(new TeamBirthdayDTO(
                    (String) row.get("uuid"),
                    (String) row.get("firstname"),
                    (String) row.get("lastname"),
                    birthday.getDayOfMonth(),
                    birthday.getMonthValue(),
                    daysUntil,
                    daysUntil == 0));
        }
        result.sort(Comparator.comparingInt(TeamBirthdayDTO::daysUntil)
                .thenComparing(TeamBirthdayDTO::lastname, Comparator.nullsLast(String::compareToIgnoreCase))
                .thenComparing(TeamBirthdayDTO::firstname, Comparator.nullsLast(String::compareToIgnoreCase)));
        return result;
    }

    /**
     * Days until the next occurrence of the birthday, 0 on the day itself, wrapping across the
     * year boundary. A 29 February birthday falls on 28 February in a common year.
     */
    static int daysUntilNext(LocalDate birthday, LocalDate today) {
        LocalDate next = birthday.withYear(today.getYear());
        if (next.isBefore(today)) {
            next = birthday.withYear(today.getYear() + 1);
        }
        return (int) ChronoUnit.DAYS.between(today, next);
    }

    private static LocalDate toLocalDate(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDate ld) return ld;
        if (value instanceof java.sql.Date d) return d.toLocalDate();
        if (value instanceof java.sql.Timestamp t) return t.toLocalDateTime().toLocalDate();
        return LocalDate.parse(value.toString().substring(0, 10));
    }
}
