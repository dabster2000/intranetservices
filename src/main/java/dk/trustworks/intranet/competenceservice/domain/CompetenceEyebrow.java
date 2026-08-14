package dk.trustworks.intranet.competenceservice.domain;

import dk.trustworks.intranet.competenceservice.content.CompetenceContent;

import java.util.ArrayList;
import java.util.List;

/**
 * Derives the small label above each screen title.
 *
 * <p>Computed on read and never stored, so it cannot drift after an author inserts,
 * removes or reorders a screen — a stored "Afsnit 4 af 10" would be wrong the moment
 * someone added a section, and wrong in a way nobody would notice.
 */
public final class CompetenceEyebrow {

    private CompetenceEyebrow() {
    }

    /**
     * One eyebrow per screen, positionally aligned with {@code payload.screens()}.
     *
     * <p>Only {@code content} screens are numbered, and they are numbered against the
     * count of content screens rather than of all screens — so the intro, the video and
     * the summary do not inflate the denominator.
     */
    public static List<String> derive(CompetenceContent.CoursePayload payload) {
        List<String> out = new ArrayList<>();
        if (payload == null) {
            return out;
        }
        long contentTotal = payload.screens().stream()
                .filter(s -> "content".equals(s.role()))
                .count();
        int contentSeen = 0;
        for (CompetenceContent.Screen screen : payload.screens()) {
            String role = screen.role() == null ? "content" : screen.role();
            switch (role) {
                case "intro" -> out.add("Introduktion");
                case "video" -> out.add("Supplerende materiale");
                case "summary" -> out.add("Nøglepunkter");
                default -> {
                    contentSeen++;
                    out.add("Afsnit " + contentSeen + " af " + contentTotal);
                }
            }
        }
        return out;
    }
}
