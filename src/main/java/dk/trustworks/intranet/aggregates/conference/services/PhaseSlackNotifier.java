package dk.trustworks.intranet.aggregates.conference.services;

import com.slack.api.model.block.LayoutBlock;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.knowledgeservice.model.Conference;
import dk.trustworks.intranet.knowledgeservice.model.ConferenceParticipant;
import dk.trustworks.intranet.knowledgeservice.model.ConferencePhase;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ManagedExecutor;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.slack.api.model.block.Blocks.*;
import static com.slack.api.model.block.composition.BlockCompositions.*;
import static com.slack.api.model.block.element.BlockElements.*;

/**
 * Optional, best-effort Slack notification when a participant enters a phase.
 * Public methods run on the request thread (guard + conference-name lookup),
 * then dispatch the actual posting to a managed executor (no DB tx, off the
 * request thread). All Slack failures are swallowed and logged — the
 * participant create/move flow and the phase email are never affected.
 */
@JBossLog
@ApplicationScoped
public class PhaseSlackNotifier {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("MMM dd, yyyy 'at' HH:mm");

    private final SlackService slackService;
    private final ManagedExecutor managedExecutor;
    private final int perSubjectMax;
    private final long perSubjectDelayMs;

    @Inject
    public PhaseSlackNotifier(SlackService slackService,
                              ManagedExecutor managedExecutor,
                              @ConfigProperty(name = "conference.slack.per-subject-max", defaultValue = "10") int perSubjectMax,
                              @ConfigProperty(name = "conference.slack.per-subject-delay-ms", defaultValue = "1100") long perSubjectDelayMs) {
        this.slackService = slackService;
        this.managedExecutor = managedExecutor;
        this.perSubjectMax = perSubjectMax;
        this.perSubjectDelayMs = perSubjectDelayMs;
    }

    // ---- public API (request thread) ----

    public void notifyEntry(ConferencePhase phase, ConferenceParticipant participant) {
        if (isDisabled(phase) || participant == null) return;
        String channel = phase.getSlackChannel();
        String phaseName = phase.getName();
        String conferenceName = resolveConferenceName(participant.getConferenceuuid());
        managedExecutor.submit(() -> notifyEntrySync(channel, phaseName, conferenceName, participant));
    }

    public void notifyBatch(ConferencePhase phase, List<ConferenceParticipant> participants) {
        if (isDisabled(phase) || participants == null || participants.isEmpty()) return;
        String channel = phase.getSlackChannel();
        String phaseName = phase.getName();
        String conferenceName = resolveConferenceName(participants.get(0).getConferenceuuid());
        List<ConferenceParticipant> snapshot = new ArrayList<>(participants);
        managedExecutor.submit(() -> notifyBatchSync(channel, phaseName, conferenceName, snapshot));
    }

    void notifyBatchSync(String channel, String phaseName, String conferenceName, List<ConferenceParticipant> participants) {
        try {
            if (participants.size() > perSubjectMax) {
                slackService.sendMessage(channel,
                        buildSummaryText(phaseName, conferenceName, participants),
                        buildSummaryBlocks(phaseName, conferenceName, participants));
                return;
            }
            int posted = 0;
            for (int i = 0; i < participants.size(); i++) {
                ConferenceParticipant p = participants.get(i);
                notifyEntrySync(channel, phaseName, conferenceName, p);
                posted++;
                if (i < participants.size() - 1 && perSubjectDelayMs > 0) {
                    try {
                        Thread.sleep(perSubjectDelayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            log.infof("PhaseSlackNotifier batch: posted %d/%d to channel=%s", posted, participants.size(), channel);
        } catch (Exception e) {
            log.errorf(e, "PhaseSlackNotifier batch post failed for channel=%s: %s", channel, e.getMessage());
        }
    }

    String buildSummaryText(String phaseName, String conferenceName, List<ConferenceParticipant> participants) {
        int n = participants.size();
        int k = Math.min(3, n);
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < k; i++) {
            if (i > 0) names.append(", ");
            ConferenceParticipant p = participants.get(i);
            names.append(p.getName() != null && !p.getName().isBlank() ? p.getName() : p.getEmail());
        }
        String tail = (n > k) ? " … and " + (n - k) + " more" : "";
        return "➡️ " + n + " participants moved into " + phaseName
                + " (" + conferenceName + ") — " + names + tail;
    }

    private List<LayoutBlock> buildSummaryBlocks(String phaseName, String conferenceName, List<ConferenceParticipant> participants) {
        List<LayoutBlock> blocks = new ArrayList<>();
        blocks.add(header(h -> h.text(plainText("➡️ " + participants.size() + " participants moved into " + phaseName))));
        blocks.add(section(s -> s.text(markdownText(buildSummaryText(phaseName, conferenceName, participants)))));
        blocks.add(context(c -> c.elements(asContextElements(
                markdownText("Conference: " + conferenceName + " | Phase: " + phaseName)))));
        return blocks;
    }

    // ---- helpers (package-private for unit tests) ----

    boolean isDisabled(ConferencePhase phase) {
        return phase == null || phase.getSlackChannel() == null || phase.getSlackChannel().trim().isEmpty();
    }

    /** Runs on the request thread (Hibernate session available). Tolerates failure. */
    String resolveConferenceName(String conferenceUuid) {
        try {
            Conference c = Conference.findById(conferenceUuid);
            if (c != null && c.getName() != null && !c.getName().isBlank()) return c.getName();
        } catch (Exception e) {
            log.warnf("Could not resolve conference name for %s: %s", conferenceUuid, e.getMessage());
        }
        return conferenceUuid != null ? conferenceUuid : "a conference";
    }

    void notifyEntrySync(String channel, String phaseName, String conferenceName, ConferenceParticipant p) {
        try {
            slackService.sendMessage(channel, entryFallback(phaseName, conferenceName, p),
                    buildEntryBlocks(phaseName, conferenceName, p));
        } catch (Exception e) {
            log.errorf(e, "PhaseSlackNotifier entry post failed for channel=%s: %s", channel, e.getMessage());
        }
    }

    String entryFallback(String phaseName, String conferenceName, ConferenceParticipant p) {
        String name = (p.getName() != null && !p.getName().isBlank()) ? p.getName() : "New participant";
        return name + " entered " + phaseName + " (" + conferenceName + ")";
    }

    /** Ordered mrkdwn detail lines: real entity fields first, then every bag entry. Blanks omitted. */
    List<String> participantDetailLines(ConferenceParticipant p) {
        List<String> lines = new ArrayList<>();
        addLine(lines, "Email", p.getEmail());
        addLine(lines, "Company", p.getCompany());
        addLine(lines, "Title", p.getTitel());
        lines.add("*Marketing consent:* " + (p.isMarketingConsent() ? "✅" : "—"));
        lines.add("*Consent:* " + (p.isSamtykke() ? "✅" : "—"));
        if (p.getRegistered() != null) lines.add("*Registered:* " + p.getRegistered().format(TS));
        if (p.getFields() != null) {
            for (Map.Entry<String, Object> e : p.getFields().entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                String v = String.valueOf(e.getValue());
                if (v.isBlank()) continue;
                lines.add("*" + e.getKey() + ":* " + v);
            }
        }
        return lines;
    }

    private void addLine(List<String> lines, String label, String value) {
        if (value != null && !value.isBlank()) lines.add("*" + label + ":* " + value);
    }

    List<LayoutBlock> buildEntryBlocks(String phaseName, String conferenceName, ConferenceParticipant p) {
        String name = (p.getName() != null && !p.getName().isBlank()) ? p.getName() : "New participant";
        List<LayoutBlock> blocks = new ArrayList<>();
        blocks.add(header(h -> h.text(plainText("📥 " + name + " entered " + phaseName))));
        blocks.add(section(s -> s.text(markdownText(String.join("\n", participantDetailLines(p))))));
        if (p.getAndet() != null && !p.getAndet().isBlank()) {
            blocks.add(section(s -> s.text(markdownText("*Message:*\n" + p.getAndet()))));
        }
        blocks.add(context(c -> c.elements(asContextElements(
                markdownText("Conference: " + conferenceName + " | Phase: " + phaseName)))));
        return blocks;
    }
}
