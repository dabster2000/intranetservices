package dk.trustworks.intranet.vacationservice.services;

import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.domain.user.entity.UserStatus;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the one mistake that would disable the company gate completely and
 * leave no trace of having done so.
 *
 * <p>{@code VacationImportService.createBatch} loads users with
 * {@code userService.listAll(true)} — shallow — because name matching needs
 * nothing but names, and hydrating instead would cost eight bulk queries and
 * pull every employee's salary and bank details into heap on each CSV upload.
 * A shallow {@code User} carries no statuses. Ask such a user
 * {@code getUserStatus(date)} and it does not fail: it returns a fabricated
 * TERMINATED status whose company is null. Every row in every file would then
 * be UNKNOWN_COMPANY, every apply would be refused, and nothing would throw or
 * log an error.</p>
 *
 * <p>These tests therefore pin two things: that the hazard in {@code User} is
 * real and still shaped the way the design assumed, and that the import path
 * does not go anywhere near it — it asks {@link EmploymentCompanyLookup},
 * which reads {@code userstatus} directly.</p>
 *
 * <p><b>Limits, stated plainly.</b> The fast tier — the CI deploy gate — runs
 * with {@code -DexcludedGroups=io.quarkus.test.junit.QuarkusTest}, and
 * {@code createBatch} cannot execute outside a container: it persists Panache
 * entities and calls static Panache finders throughout. So no DB-free test can
 * observe createBatch's real behaviour. The second test below is a structural
 * assertion over the source instead. It is not a substitute for an end-to-end
 * test; it is the strongest guard that actually runs in the gate, and it fails
 * on exactly the regression described above.</p>
 */
class VacationImportCompanyLookupWiringTest {

    private static final Path SERVICE =
            Path.of("src/main/java/dk/trustworks/intranet/vacationservice/services/VacationImportService.java");
    private static final Path LOOKUP =
            Path.of("src/main/java/dk/trustworks/intranet/vacationservice/services/EmploymentCompanyLookup.java");

    /**
     * The hazard itself, demonstrated. A user with no statuses loaded — which
     * is every user {@code listAll(true)} returns — answers with a company of
     * null rather than admitting it does not know.
     */
    @Test
    void aUserWithoutLoadedStatusesFabricatesATerminatedStatusWithNoCompany() {
        User shallow = new User();
        shallow.setUuid("user-a");

        UserStatus fabricated = shallow.getUserStatus(LocalDate.of(2026, 6, 30));

        assertNotNull(fabricated, "getUserStatus never returns null — that is what makes it dangerous here");
        assertNull(fabricated.getCompany(),
                "the fallback status carries no company; a company gate built on it would flag every employee");
    }

    /**
     * The import must resolve the employment company through the lookup, which
     * queries {@code userstatus} for the resolved users, and must never ask a
     * {@code User} object for a status. A reimplementation that went back to
     * {@code getUserStatus} would compile, pass every other test, and silently
     * break the gate — this is the assertion that stops it.
     */
    @Test
    void theImportResolvesTheCompanyThroughTheLookupAndNeverThroughUser() throws IOException {
        String service = code(SERVICE);
        assertFalse(service.contains("getUserStatus"),
                "VacationImportService must not ask a User for its status — the users it holds are loaded shallow, "
                        + "so getUserStatus would answer every one of them with a fabricated company-less status");
        assertTrue(service.contains("employmentCompanyLookup.companiesAt("),
                "the company gate must go through EmploymentCompanyLookup");

        String lookup = code(LOOKUP);
        assertTrue(lookup.contains("FROM UserStatus s"),
                "EmploymentCompanyLookup must read userstatus directly, not via User");
        assertFalse(lookup.contains("getUserStatus"),
                "not even the lookup may fall back to the fabricating accessor");
    }

    /**
     * The source with comments removed. Both files explain the trap in prose,
     * and prose naming the hazard must not read as committing it — the
     * assertions above are about what the code does.
     */
    private static String code(Path path) throws IOException {
        assertTrue(Files.exists(path), "expected to find " + path.toAbsolutePath()
                + " — this test reads the source, so it must run from the module root");
        String source = Files.readString(path);

        StringBuilder stripped = new StringBuilder(source.length());
        boolean inString = false;
        boolean inChar = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
                    stripped.append(c);
                }
            } else if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                }
            } else if (inString || inChar) {
                stripped.append(c);
                if (c == '\\') {
                    stripped.append(next);
                    i++;
                } else if (inString && c == '"') {
                    inString = false;
                } else if (inChar && c == '\'') {
                    inChar = false;
                }
            } else if (c == '/' && next == '/') {
                inLineComment = true;
                i++;
            } else if (c == '/' && next == '*') {
                inBlockComment = true;
                i++;
            } else {
                if (c == '"') inString = true;
                else if (c == '\'') inChar = true;
                stripped.append(c);
            }
        }
        return stripped.toString();
    }
}
