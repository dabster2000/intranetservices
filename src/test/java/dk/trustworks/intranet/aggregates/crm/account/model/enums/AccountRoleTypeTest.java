package dk.trustworks.intranet.aggregates.crm.account.model.enums;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One store for the people on an account (account-people-merge spec, V598).
 *
 * <p>A three-line test for a two-value enum, and it earns its place: the enum is persisted
 * as a STRING into a narrowed MySQL {@code ENUM('SUPPORTED_BY','MEMBER')} column. Putting
 * {@code RESPONSIBLE} back in Java without widening the column writes an empty string into
 * every row it touches — silently, because MySQL's enum coercion does not raise — and the
 * rows come back as unreadable roles nobody can attribute.
 *
 * <p>{@code RESPONSIBLE} was a MIRROR of {@code client.accountmanager}, rewritten by
 * {@code AccountService} on every write path and read by nothing in main, in test, or in
 * the frontend. Its 9 rows all agreed with the column, which is why deleting them changed
 * no behaviour — and why re-adding the value would quietly recreate a second store for the
 * owner that nothing consults.
 */
class AccountRoleTypeTest {

    @Test
    void theOnlyTwoRolesAreSupportedByAndMember() {
        List<AccountRoleType> values = Arrays.asList(AccountRoleType.values());
        assertEquals(2, values.size(), "V598 narrowed the column to two values: " + values);
        assertTrue(values.contains(AccountRoleType.SUPPORTED_BY));
        assertTrue(values.contains(AccountRoleType.MEMBER));
    }

    @Test
    void theOwnerMirrorIsGoneForGood() {
        assertFalse(Arrays.stream(AccountRoleType.values()).anyMatch(role -> role.name().equals("RESPONSIBLE")),
                "client.accountmanager is the single store for the owner since V598");
    }
}
