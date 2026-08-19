package dk.trustworks.intranet.userservice.services;

import dk.trustworks.intranet.domain.user.entity.Team;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Database-free coverage of the filename the team-logo write persists. The
 * value lands in {@code files.filename}, which is echoed back to browsers, so
 * what the client asked for is advisory and everything outside the conservative
 * set has to be replaced rather than trusted.
 */
class TeamLogoServiceTest {

    private static Team team(String name) {
        Team t = new Team();
        t.setName(name);
        return t;
    }

    @Test
    void keepsAConservativeName() {
        assertEquals("alpha-logo.jpg",
                TeamLogoService.resolveFilename("alpha-logo.png", team("Alpha"), ".jpg"));
    }

    @Test
    void extensionComesFromTheDetectedBytesNotTheClaimedName() {
        // The client sent .png; the decoded bytes were JPEG. The stored name
        // must describe what is actually in S3, not what the caller called it.
        assertEquals("alpha.jpg", TeamLogoService.resolveFilename("alpha.png", team("Alpha"), ".jpg"));
    }

    @Test
    void fallsBackToTheTeamNameWhenNoneIsGiven() {
        assertEquals("Team_Alpha.jpg", TeamLogoService.resolveFilename(null, team("Team Alpha"), ".jpg"));
        assertEquals("Team_Alpha.jpg", TeamLogoService.resolveFilename("   ", team("Team Alpha"), ".jpg"));
    }

    @Test
    void fallsBackToALiteralWhenTheTeamHasNoUsableName() {
        assertEquals("team.jpg", TeamLogoService.resolveFilename(null, team(null), ".jpg"));
        assertEquals("team.jpg", TeamLogoService.resolveFilename(null, team("  "), ".jpg"));
        // A name made entirely of rejected characters sanitizes to underscores,
        // never to the empty string — an extension-only filename is not a name.
        assertEquals("___.jpg", TeamLogoService.resolveFilename("###", team("Alpha"), ".jpg"));
    }

    @Test
    void keepsOnlyTheLastPathSegment() {
        // Both separators — a browser on Windows sends backslashes. Taking the
        // basename before stripping the extension is what stops the dots in
        // "../.." being read as one, which truncated the name to "../.".
        assertEquals("passwd.jpg", TeamLogoService.resolveFilename("../../etc/passwd", team("Alpha"), ".jpg"));
        assertEquals("logo.jpg", TeamLogoService.resolveFilename("C:\\Users\\hans\\logo.png", team("Alpha"), ".jpg"));
    }

    @Test
    void replacesEverythingOutsideTheConservativeSet() {
        assertEquals("logo_name.jpg", TeamLogoService.resolveFilename("logo\nname", team("Alpha"), ".jpg"));
        assertEquals("logo__.jpg", TeamLogoService.resolveFilename("logo\r\n", team("Alpha"), ".jpg"));
        String resolved = TeamLogoService.resolveFilename("../../etc/passwd", team("Alpha"), ".jpg");
        assertTrue(resolved.indexOf('/') < 0, resolved);
    }

    @Test
    void aDotOnlyNameIsNotAName() {
        // "..." survives the character filter untouched and would otherwise
        // concatenate into "...jpg", which reads as an extension and no name.
        assertEquals("team.jpg", TeamLogoService.resolveFilename("...", team("Alpha"), ".jpg"));
    }

    @Test
    void boundsTheLengthSoTheExtensionAlwaysFits() {
        String resolved = TeamLogoService.resolveFilename("x".repeat(500), team("Alpha"), ".jpg");
        assertEquals(204, resolved.length());
        assertTrue(resolved.endsWith(".jpg"), resolved);
    }
}
