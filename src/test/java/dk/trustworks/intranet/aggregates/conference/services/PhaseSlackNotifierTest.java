package dk.trustworks.intranet.aggregates.conference.services;

import com.slack.api.model.block.LayoutBlock;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.knowledgeservice.model.ConferenceParticipant;
import dk.trustworks.intranet.knowledgeservice.model.ConferencePhase;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PhaseSlackNotifierTest {

    private ConferencePhase phase(String channel) {
        ConferencePhase p = new ConferencePhase();
        p.setUuid("phase-uuid");
        p.setName("Registered");
        p.setStep(0);
        p.setSlackChannel(channel);
        return p;
    }

    private ConferenceParticipant participant(String name, String email) {
        ConferenceParticipant p = new ConferenceParticipant();
        p.setUuid("p-uuid");
        p.setConferenceuuid("conf-uuid");
        p.setName(name);
        p.setEmail(email);
        p.setCompany("Acme A/S");
        p.setTitel("CTO");
        p.setAndet("Looking forward to it");
        p.setMarketingConsent(true);
        Map<String, Object> bag = new LinkedHashMap<>();
        bag.put("phone", "+45 12345678");
        p.setFields(bag);
        return p;
    }

    // perSubjectMax=10, delay=0; executor is null because *Sync paths never touch it.
    private PhaseSlackNotifier notifier(SlackService slack) {
        return new PhaseSlackNotifier(slack, null, 10, 0L);
    }

    @Test
    void blankChannel_isNoOp() {
        SlackService slack = mock(SlackService.class);
        PhaseSlackNotifier n = notifier(slack);

        n.notifyEntry(phase(null), participant("Alice", "a@x.dk"));
        n.notifyEntry(phase("   "), participant("Bob", "b@x.dk"));
        n.notifyEntry(null, participant("Carol", "c@x.dk"));

        verifyNoInteractions(slack);
    }

    @Test
    void entryFallback_containsNamePhaseConference() {
        PhaseSlackNotifier n = notifier(mock(SlackService.class));
        String fallback = n.entryFallback("Registered", "Forefront 2025", participant("Alice", "a@x.dk"));
        assertTrue(fallback.contains("Alice"));
        assertTrue(fallback.contains("Registered"));
        assertTrue(fallback.contains("Forefront 2025"));
    }

    @Test
    void detailLines_containEntityFieldsAndBag() {
        PhaseSlackNotifier n = notifier(mock(SlackService.class));
        String all = String.join("\n", n.participantDetailLines(participant("Alice", "alice@x.dk")));
        assertTrue(all.contains("alice@x.dk"), "email");
        assertTrue(all.contains("Acme A/S"), "company");
        assertTrue(all.contains("CTO"), "title");
        assertTrue(all.toLowerCase().contains("consent"), "marketing consent label");
        assertTrue(all.contains("+45 12345678"), "phone from bag");
    }

    @Test
    void notifyEntrySync_sendsOneBlockMessage() {
        SlackService slack = mock(SlackService.class);
        PhaseSlackNotifier n = notifier(slack);

        n.notifyEntrySync("C123", "Registered", "Forefront 2025", participant("Alice", "alice@x.dk"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LayoutBlock>> blocks = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> fallback = ArgumentCaptor.forClass(String.class);
        verify(slack, times(1)).sendMessage(eq("C123"), fallback.capture(), blocks.capture());
        assertFalse(blocks.getValue().isEmpty(), "blocks built");
        assertTrue(fallback.getValue().contains("Alice"));
    }
}
