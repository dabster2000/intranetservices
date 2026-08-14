package dk.trustworks.intranet.recruitmentservice.slack;

import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundRequest;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentSlotApproval;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The card-update address fallback the approve/decline handlers share
 * (plan §9.2): the stored DM channel wins over the interaction's, but
 * the CLICKED message's ts wins over the stored one — a click on an
 * older/nudge card must rewrite that very card.
 */
class SlackSchedulingCardAddressTest {

    @Test
    void prefersStoredChannel_andClickedMessageTs() {
        RecruitmentSlotApproval approval = new RecruitmentSlotApproval();
        approval.setSlackChannelId("D111");
        approval.setSlackMessageTs("1000.1");
        SlackInboundRequest click = inbound("D222", "2000.2");

        assertEquals("D111", SlackSchedulingApproveHandler.channelOf(approval, click));
        assertEquals("2000.2", SlackSchedulingApproveHandler.tsOf(approval, click));
    }

    @Test
    void fallsBackWhenEitherSideIsMissing() {
        RecruitmentSlotApproval bare = new RecruitmentSlotApproval();
        SlackInboundRequest click = inbound("D222", "2000.2");
        assertEquals("D222", SlackSchedulingApproveHandler.channelOf(bare, click));
        assertEquals("2000.2", SlackSchedulingApproveHandler.tsOf(bare, click));

        RecruitmentSlotApproval stored = new RecruitmentSlotApproval();
        stored.setSlackChannelId("D111");
        stored.setSlackMessageTs("1000.1");
        SlackInboundRequest blankClick = inbound(null, null);
        assertEquals("D111", SlackSchedulingApproveHandler.channelOf(stored, blankClick));
        assertEquals("1000.1", SlackSchedulingApproveHandler.tsOf(stored, blankClick));
    }

    private static SlackInboundRequest inbound(String channelId, String messageTs) {
        return new SlackInboundRequest("interactions", "p1", "U1", "T1",
                "block_actions", "recruitment_sched_approve", null, null,
                null, channelId, messageTs, null, "approval-1", null, null, null, null);
    }
}
