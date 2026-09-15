package dk.trustworks.intranet.aggregates.crm.calendar.services;

import java.util.Locale;
import java.util.Set;

/** Conservative functional-mailbox detection. A shared address must never identify a person. */
public final class CalendarSharedAddressFilter {
    private static final Set<String> FUNCTIONAL = Set.of("it", "sg.it", "info", "support", "helpdesk",
            "servicedesk", "reception", "kontakt", "contact", "booking", "noreply", "no-reply",
            "donotreply", "do-not-reply", "postmaster", "noreply-meeting-booking");

    private CalendarSharedAddressFilter() { }

    public static boolean isShared(String email) {
        if (email == null || !email.contains("@")) return false;
        String local = email.trim().toLowerCase(Locale.ROOT).split("@", 2)[0];
        return FUNCTIONAL.contains(local) || local.startsWith("noreply-")
                || local.startsWith("no-reply-") || local.startsWith("meeting-booking");
    }
}
