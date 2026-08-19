package dk.trustworks.intranet.userservice.services;

import dk.trustworks.intranet.domain.user.entity.Team;
import dk.trustworks.intranet.fileservice.model.File;
import dk.trustworks.intranet.fileservice.resources.PhotoService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.Base64;

/**
 * Writes a team's logo — the image {@code /organization} and the team dashboard
 * render for that team.
 * <p>
 * Deliberately its own service rather than a method on {@link TeamService}: the
 * team domain otherwise has no dependency on the file service, and adding one
 * would drag S3 and the photo caches into every test that touches team data.
 */
@ApplicationScoped
@JBossLog
public class TeamLogoService {

    /**
     * The {@code files.type} a team logo MUST be stored under.
     * <p>
     * Not decoration, and not interchangeable with {@code "LOGO"}: the only
     * reader — {@code PhotoService.findPhotoByRelatedUUID}, which serves
     * {@code GET /files/photos/{relateduuid}/jpg} and therefore the
     * {@code <img>} on every page — hard-filters
     * {@code relateduuid = ?1 AND type = 'PHOTO'}. A row written under any other
     * type is invisible to it, and the supersede query in
     * {@code PhotoService.update} is scoped the same way, so such a row would
     * also fail to replace the logo already on screen. The upload would report
     * success and change nothing.
     */
    private static final String TEAM_LOGO_FILE_TYPE = "PHOTO";

    @Inject
    PhotoService photoService;

    /**
     * Replaces {@code teamuuid}'s logo with {@code base64} image bytes.
     * <p>
     * The team is looked up first so an unknown uuid is a 404 rather than an
     * orphaned {@code files} row keyed on nothing: the photo tables carry no FK
     * to {@code team}, so nothing downstream would ever notice the mistake.
     *
     * @throws NotFoundException       no such team
     * @throws WebApplicationException 400 — the payload is not valid base64, or
     *                                 decodes to something that is not a
     *                                 storable raster image
     */
    public void updateTeamLogo(String teamuuid, String base64, String filename) {
        Team team = Team.findById(teamuuid);
        if (team == null) {
            throw new NotFoundException("Team not found: " + teamuuid);
        }

        byte[] decoded = decode(base64);

        // The allowlist is enforced for real inside PhotoService.update — this
        // pre-check exists only so the caller gets a 400 naming the format
        // instead of one surfacing from the photo service mid-write.
        String mimeType = photoService.detectMimeType(decoded);
        if (!PhotoService.isStorableImageType(mimeType)) {
            throw new WebApplicationException("Unsupported image format: " + mimeType,
                    Response.Status.BAD_REQUEST);
        }

        File logo = new File();
        // Empty uuid means "mint one and supersede the previous row" — the
        // insert branch of PhotoService.update. Reusing the old uuid instead
        // would take the update branch, which never rewrites the S3 object.
        logo.setUuid("");
        logo.setRelateduuid(teamuuid);
        logo.setType(TEAM_LOGO_FILE_TYPE);
        logo.setName(team.getName());
        logo.setFilename(resolveFilename(filename, team, photoService.extensionFromMimeType(mimeType)));
        logo.setUploaddate(LocalDate.now());
        logo.setFile(decoded);

        photoService.updateLogo(logo);
    }

    private byte[] decode(String base64) {
        if (base64 == null || base64.isBlank()) {
            throw new WebApplicationException("file is required", Response.Status.BAD_REQUEST);
        }
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException("file must be valid base64", Response.Status.BAD_REQUEST);
        }
    }

    /**
     * A safe filename for the stored row. The client's name is advisory only —
     * it lands in a column that is echoed back to browsers, so anything outside
     * the conservative set is replaced rather than trusted.
     */
    static String resolveFilename(String requested, Team team, String extension) {
        String base = (requested == null || requested.isBlank())
                ? (team.getName() == null || team.getName().isBlank() ? "team" : team.getName())
                : stripExtension(basename(requested));
        String sanitized = base.replaceAll("[^a-zA-Z0-9._-]", "_");
        // A name of nothing but dots is not a name: it survives the character
        // filter untouched and would concatenate into "...jpg", which reads as
        // an extension-only file. Blank does the same via a different route.
        if (sanitized.isBlank() || sanitized.chars().allMatch(c -> c == '.')) {
            sanitized = "team";
        }
        // files.filename is varchar(255); leave room for the extension.
        if (sanitized.length() > 200) {
            sanitized = sanitized.substring(0, 200);
        }
        return sanitized + extension;
    }

    /**
     * The last path segment, for both separators — a browser on Windows sends
     * backslashes. Taken before {@link #stripExtension} because a dot in a
     * directory name is not an extension: scanning the whole string for the
     * last dot turns {@code ../../etc/passwd} into {@code ../.}.
     */
    private static String basename(String filename) {
        int cut = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        return cut >= 0 ? filename.substring(cut + 1) : filename;
    }

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}
