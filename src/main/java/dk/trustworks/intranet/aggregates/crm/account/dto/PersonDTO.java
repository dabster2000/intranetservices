package dk.trustworks.intranet.aggregates.crm.account.dto;

import dk.trustworks.intranet.domain.user.entity.User;

/**
 * A Trustworks person as every CRM surface shows one: uuid, full name, initials.
 *
 * <p>Matches {@code IAccountPerson} in {@code src/lib/crm/accountTypes.ts} field for
 * field. Deliberately narrow — nothing here is filtered by
 * {@code UserScopeResponseFilter}, because nothing here could need to be. Serialising a
 * whole {@code User} would ship salaries and bank details to the browser the moment the
 * BFF's {@code admin:*} made the strip filter inert.
 */
public record PersonDTO(String uuid, String name, String initials) {

    public static PersonDTO from(User user) {
        if (user == null) {
            return null;
        }
        String name = fullName(user);
        return new PersonDTO(user.getUuid(), name, initialsOf(name));
    }

    /** For a name that has no {@code User} behind it — an attendee read from a calendar. */
    public static PersonDTO named(String uuid, String name) {
        return new PersonDTO(uuid, name, initialsOf(name));
    }

    private static String fullName(User user) {
        String first = user.getFirstname() == null ? "" : user.getFirstname().trim();
        String last = user.getLastname() == null ? "" : user.getLastname().trim();
        String joined = (first + " " + last).trim();
        return joined.isEmpty() ? user.getUsername() : joined;
    }

    /** First letter of the first and last word, matching the frontend's initialsOf(). */
    public static String initialsOf(String name) {
        if (name == null || name.isBlank()) {
            return "?";
        }
        String[] parts = name.trim().split("\\s+");
        if (parts.length >= 2) {
            return ("" + parts[0].charAt(0) + parts[parts.length - 1].charAt(0)).toUpperCase();
        }
        return name.substring(0, Math.min(2, name.length())).toUpperCase();
    }
}
