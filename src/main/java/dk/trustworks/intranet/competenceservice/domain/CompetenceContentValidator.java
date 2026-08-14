package dk.trustworks.intranet.competenceservice.domain;

import dk.trustworks.intranet.competenceservice.content.CompetenceContent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Server-side validation of authored content (spec §11.1, §11.2).
 *
 * <p>Messages are Danish because they name Danish content back to a Danish-speaking
 * author; the UI chrome around them stays English, consistent with the rest of the
 * intranet. The client mirrors these rules for immediate feedback, but this is the
 * enforcement point — a draft can be PUT by anything holding {@code competence:write}.
 *
 * <p>Returns the full list rather than throwing on the first problem: an author fixing a
 * thirteen-screen course should see everything wrong with it in one pass.
 */
public final class CompetenceContentValidator {

    /** Spec §9.5. Validated on save, not only on render. */
    public static final Pattern VIDEO_ID = Pattern.compile("^[A-Za-z0-9_-]{6,20}$");

    /** The only embed provider accepted. Anything else is an editor validation error. */
    public static final String ALLOWED_VIDEO_PROVIDER = "youtube-nocookie";

    private static final int MAX_VERSION_LABEL = 32;
    private static final int MAX_HEADING = 200;
    private static final int MAX_TEXT = 4000;
    private static final int MAX_CODE = 4000;
    private static final int MAX_QUESTION = 1000;

    private CompetenceContentValidator() {
    }

    public static List<String> validateVersionLabel(String label) {
        List<String> errors = new ArrayList<>();
        if (label == null || label.isBlank()) {
            errors.add("Version må ikke være tom.");
        } else if (label.length() > MAX_VERSION_LABEL) {
            errors.add("Version må højst være " + MAX_VERSION_LABEL + " tegn.");
        }
        return errors;
    }

    // -----------------------------------------------------------------------
    // Course
    // -----------------------------------------------------------------------

    public static List<String> validateCourse(CompetenceContent.CoursePayload payload) {
        List<String> errors = new ArrayList<>();
        if (payload == null || payload.screens().isEmpty()) {
            errors.add("Kurset skal have mindst én skærm.");
            return errors;
        }
        int n = 0;
        for (CompetenceContent.Screen screen : payload.screens()) {
            n++;
            String title = screen.title();
            if (title == null || title.isBlank()) {
                errors.add("Skærm " + n + " mangler en titel.");
                title = "";
            }
            if (screen.role() != null && !CompetenceContent.SCREEN_ROLES.contains(screen.role())) {
                errors.add("Skærm " + n + " har ukendt rolle: " + screen.role() + ".");
            }
            if (screen.blocks().isEmpty()) {
                errors.add("Skærm " + n + " (\"" + title + "\") har intet indhold.");
                continue;
            }
            int b = 0;
            for (CompetenceContent.Block block : screen.blocks()) {
                b++;
                errors.addAll(validateBlock(block, n, b));
            }
        }
        return errors;
    }

    private static List<String> validateBlock(CompetenceContent.Block block, int screenNo, int blockNo) {
        List<String> errors = new ArrayList<>();
        String where = "Skærm " + screenNo + ", blok " + blockNo;
        String type = block.type();
        if (type == null || !CompetenceContent.BLOCK_TYPES.contains(type)) {
            errors.add("Ukendt blok-type: " + type + ".");
            return errors;
        }
        switch (type) {
            case "heading" -> {
                if (isBlank(block.text())) {
                    errors.add(where + ": overskrift må ikke være tom.");
                } else if (block.text().length() > MAX_HEADING) {
                    errors.add(where + ": overskrift må højst være " + MAX_HEADING + " tegn.");
                }
            }
            case "paragraph", "callout" -> {
                if (isBlank(block.text())) {
                    errors.add(where + ": teksten må ikke være tom.");
                } else if (block.text().length() > MAX_TEXT) {
                    errors.add(where + ": teksten må højst være " + MAX_TEXT + " tegn.");
                }
            }
            case "list", "keypoints" -> {
                List<String> items = block.items();
                if (items == null || items.stream().allMatch(CompetenceContentValidator::isBlank)) {
                    errors.add(where + ": listen skal have mindst ét punkt med indhold.");
                } else {
                    for (String item : items) {
                        if (item != null && item.length() > MAX_TEXT) {
                            errors.add(where + ": et listepunkt må højst være " + MAX_TEXT + " tegn.");
                            break;
                        }
                    }
                }
            }
            case "code" -> {
                if (isBlank(block.code())) {
                    errors.add(where + ": kodeblokken må ikke være tom.");
                } else if (block.code().length() > MAX_CODE) {
                    errors.add(where + ": kodeblokken må højst være " + MAX_CODE + " tegn.");
                }
            }
            case "video" -> errors.addAll(validateVideo(block, where));
            default -> errors.add("Ukendt blok-type: " + type + ".");
        }
        return errors;
    }

    /**
     * Provider and id are optional: a video block is an inert placeholder until an author
     * sets them, and the whole 2026-08 export ships that way. Once set, both are checked.
     */
    private static List<String> validateVideo(CompetenceContent.Block block, String where) {
        List<String> errors = new ArrayList<>();
        boolean hasProvider = !isBlank(block.provider());
        boolean hasId = !isBlank(block.videoId());
        if (!hasProvider && !hasId) {
            return errors;
        }
        if (!hasProvider || !ALLOWED_VIDEO_PROVIDER.equals(block.provider())) {
            errors.add(where + ": video-udbyder skal være \"" + ALLOWED_VIDEO_PROVIDER + "\".");
        }
        if (!hasId || !VIDEO_ID.matcher(block.videoId()).matches()) {
            errors.add(where + ": video-id har et ugyldigt format.");
        }
        return errors;
    }

    // -----------------------------------------------------------------------
    // Test
    // -----------------------------------------------------------------------

    public static List<String> validateTest(CompetenceContent.TestPayload payload) {
        List<String> errors = new ArrayList<>();
        if (payload == null || payload.questions().isEmpty()) {
            errors.add("Testen skal have mindst ét spørgsmål.");
            return errors;
        }
        Set<String> questionIds = new HashSet<>();
        int n = 0;
        for (CompetenceContent.Question question : payload.questions()) {
            n++;
            if (isBlank(question.id())) {
                errors.add("Spørgsmål " + n + " mangler et id.");
            } else if (!questionIds.add(question.id())) {
                errors.add("Spørgsmål " + n + " har et id der allerede er brugt: " + question.id() + ".");
            }
            if (isBlank(question.text())) {
                errors.add("Spørgsmål " + n + " mangler en spørgsmålstekst.");
            } else if (question.text().length() > MAX_QUESTION) {
                errors.add("Spørgsmål " + n + " må højst være " + MAX_QUESTION + " tegn.");
            }
            List<CompetenceContent.Option> options = question.options();
            if (options.size() < 2) {
                errors.add("Spørgsmål " + n + " skal have mindst 2 svarmuligheder.");
            } else if (options.size() > 6) {
                errors.add("Spørgsmål " + n + " må højst have 6 svarmuligheder.");
            }
            long correct = options.stream().filter(CompetenceContent.Option::correct).count();
            if (correct != 1) {
                errors.add("Spørgsmål " + n + " skal have præcis ét korrekt svar markeret (har "
                        + correct + ").");
            }
            Set<String> optionIds = new HashSet<>();
            int m = 0;
            for (CompetenceContent.Option option : options) {
                m++;
                if (isBlank(option.text())) {
                    errors.add("Spørgsmål " + n + ", svarmulighed " + m + " er tom.");
                }
                if (isBlank(option.id())) {
                    errors.add("Spørgsmål " + n + ", svarmulighed " + m + " mangler et id.");
                } else if (!optionIds.add(option.id())) {
                    errors.add("Spørgsmål " + n + " har to svarmuligheder med samme id: "
                            + option.id() + ".");
                }
            }
        }
        return errors;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
