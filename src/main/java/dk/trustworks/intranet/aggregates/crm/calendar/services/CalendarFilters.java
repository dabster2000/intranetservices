package dk.trustworks.intranet.aggregates.crm.calendar.services;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Everything one calendar sync run needs in order to decide what a meeting IS, carried
 * together so the decision functions take one parameter instead of three.
 *
 * <p>Loaded <b>once per {@code syncAll()}</b>, in its own short transaction, exactly as the
 * domain index and the consent list are — the three queries behind it read the whole
 * contract, employee and learned-address tables, and doing that per mailbox would mean a
 * hundred repeats of the same answer while the §P9 M1 rule says no transaction may be open
 * during a Graph call anyway.
 *
 * <h2>Why the e-mail set is mutable</h2>
 * The learned-address set GROWS during the run, and that is deliberate. The first mailbox
 * that sees {@code "MYGX (Malthe Yde Andreasen)"} with a display name identifies Malthe by
 * name; every later mailbox in the same run that sees the same address as a bare
 * {@code mygx@novonordisk.com} with no name at all must drop it too, rather than wait for
 * tomorrow's run to read the row this one is about to write. The set is per-run state, not
 * a cache, and it is never shared between runs.
 */
final class CalendarFilters {

    private final DeliveryContractIndex delivery;
    private final ColleagueDirectory colleagues;

    /**
     * Lower-cased address to the uuid of the colleague who holds it. Seeded from
     * {@code crm_colleague_client_email}; grows during the run.
     *
     * <p><b>The uuid is not decoration.</b> The address rule has to ask the same employment
     * question the name rule asks, and it can only ask it about a person. Keeping just the
     * addresses would make the rule date-blind and drop a former colleague's meetings for
     * ever — see {@link ColleagueDirectory#wasEmployedOn(String, LocalDate)}.
     */
    private final Map<String, String> colleagueClientEmails;

    private CalendarFilters(DeliveryContractIndex delivery,
                            ColleagueDirectory colleagues,
                            Map<String, String> colleagueClientEmails) {
        this.delivery = delivery;
        this.colleagues = colleagues;
        this.colleagueClientEmails = colleagueClientEmails;
    }

    static CalendarFilters of(DeliveryContractIndex delivery,
                              ColleagueDirectory colleagues,
                              Map<String, String> colleagueClientEmails) {
        Map<String, String> emails = new LinkedHashMap<>();
        if (colleagueClientEmails != null) {
            for (Map.Entry<String, String> entry : colleagueClientEmails.entrySet()) {
                String email = entry.getKey();
                if (email != null && !email.isBlank() && entry.getValue() != null) {
                    emails.put(email.trim().toLowerCase(Locale.ROOT), entry.getValue());
                }
            }
        }
        return new CalendarFilters(
                delivery == null ? DeliveryContractIndex.empty() : delivery,
                colleagues == null ? ColleagueDirectory.empty() : colleagues,
                emails);
    }

    /** Filters that drop nothing. Used when the sync is off and by tests that need neither. */
    static CalendarFilters empty() {
        return of(DeliveryContractIndex.empty(), ColleagueDirectory.empty(), Map.of());
    }

    DeliveryContractIndex delivery() {
        return delivery;
    }

    ColleagueDirectory colleagues() {
        return colleagues;
    }

    /**
     * Is this address one a colleague held at a client <b>on this date</b>? Argument must be
     * lower-cased.
     *
     * <p>The date is the whole point. Knowing the address is Malthe's says nothing about
     * whether Malthe still worked here on the day of the meeting, and a colleague who left
     * and stayed at the client is a client contact, not one of us — the same rule the name
     * branch enforces, asked the same way. Without it, learning an address once would
     * silently override the employment test for every meeting that address ever appears on,
     * including meetings held after the person left.
     */
    boolean isColleagueEmailOn(String email, LocalDate date) {
        if (email == null) {
            return false;
        }
        String userUuid = colleagueClientEmails.get(email);
        return userUuid != null && colleagues.wasEmployedOn(userUuid, date);
    }

    /**
     * Records an address a name match has just proved belongs to a colleague.
     *
     * @return true when the run had not seen this address before, which is what makes it
     *         a NEW row in {@code crm_colleague_client_email} rather than a refreshed one
     */
    boolean rememberColleagueEmail(String email, String userUuid) {
        return email != null && userUuid != null
                && colleagueClientEmails.put(email, userUuid) == null;
    }

    /** How many colleague addresses are known. Logged once per run. */
    int knownColleagueEmailCount() {
        return colleagueClientEmails.size();
    }
}
