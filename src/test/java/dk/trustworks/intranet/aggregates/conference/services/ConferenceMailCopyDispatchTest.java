package dk.trustworks.intranet.aggregates.conference.services;

import dk.trustworks.intranet.aggregates.conference.dto.UnsubscribeFooter;
import dk.trustworks.intranet.aggregates.conference.dto.UnsubscribePageCopy;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConferenceMailCopyDispatchTest {
    @Test void snapshotsExactlyTheCopyRenderedInTheEmailWhileKeepingIdentity() {
        String list = "00000000-0000-0000-0000-000000000001";
        String recipient = "recipient@example.com";
        var copy = new UnsubscribePageCopy("Afmeld {listName}?", null, null, null, null, null, null);
        var footer = new UnsubscribeFooter("conference-unsubscribe", "Afmeld", "text-link", "centre",
                "Nyheder fra {listName}", "Offentligt navn", copy);
        var policy = mock(ConferenceUnsubscribeService.class);
        when(policy.listName(list)).thenReturn("INTERNAL_LIST_V2");
        when(policy.issueToken(list, recipient, "Offentligt navn", copy)).thenReturn("a".repeat(43));
        when(policy.unsubscribeUrl("a".repeat(43))).thenReturn("https://trustworks.dk/unsubscribe#token=" + "a".repeat(43));
        var dispatch = new ConferenceMailDispatch(); dispatch.enabled = true; dispatch.policy = policy;

        var html = Jsoup.parse(dispatch.prepare(list, recipient, "<p>Message</p>", footer));

        assertEquals("Nyheder fra Offentligt navn", html.select("[data-conference-footer] p").text());
        assertFalse(html.text().contains("INTERNAL_LIST_V2"));
        verify(policy).issueToken(list, recipient, "Offentligt navn", copy);
        verify(policy).isSuppressedFresh(list, recipient);
    }
}
