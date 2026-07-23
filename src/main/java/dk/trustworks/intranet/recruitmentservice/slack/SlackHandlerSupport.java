package dk.trustworks.intranet.recruitmentservice.slack;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.notifications.SlackCandidateFacts;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Shared plumbing for the P14 Slack inbound handlers: modal
 * {@code private_metadata} round-trips, display-name resolution and the
 * validation-message → input-block mapping that powers inline
 * {@code response_action: errors}.
 */
public final class SlackHandlerSupport {

    /** Slack channel ids: C (public), D (DM), G (private/group). */
    private static final Pattern CHANNEL_ID = Pattern.compile("^[CDG][A-Z0-9]{5,20}$");
    /** Slack message ts: seconds.micros. */
    private static final Pattern MESSAGE_TS = Pattern.compile("^\\d{6,16}\\.\\d{1,9}$");

    private SlackHandlerSupport() {
    }

    /**
     * The modal round-trip state (P14): which referral a triage modal acts
     * on and where the originating ping lives (for the outcome rewrite).
     * Round-tripped through Slack's {@code private_metadata}, therefore a
     * CLAIM — the submit handlers re-authorize the referral through
     * {@code ReferralService.triage} and only ever use channel/ts for a
     * fixed-content {@code chat.update} of a bot-authored message.
     */
    public record ModalMetadata(String referralUuid, String channelId, String messageTs) {

        public String toJson(ObjectMapper mapper) {
            try {
                return mapper.writeValueAsString(Map.of(
                        "referralUuid", referralUuid == null ? "" : referralUuid,
                        "channelId", channelId == null ? "" : channelId,
                        "messageTs", messageTs == null ? "" : messageTs));
            } catch (Exception e) {
                return "{}";
            }
        }

        public static ModalMetadata parse(ObjectMapper mapper, String json) {
            if (json == null || json.isBlank()) {
                return new ModalMetadata(null, null, null);
            }
            try {
                Map<?, ?> map = mapper.readValue(json, Map.class);
                return new ModalMetadata(
                        blankToNull(map.get("referralUuid")),
                        blankToNull(map.get("channelId")),
                        blankToNull(map.get("messageTs")));
            } catch (Exception e) {
                return new ModalMetadata(null, null, null);
            }
        }

        /** Channel + ts present AND shaped like real Slack ids (defensive). */
        public boolean hasValidPingRef() {
            return channelId != null && messageTs != null
                    && CHANNEL_ID.matcher(channelId).matches()
                    && MESSAGE_TS.matcher(messageTs).matches();
        }

        private static String blankToNull(Object value) {
            return value instanceof String s && !s.isBlank() ? s : null;
        }
    }

    /** mrkdwn-safe "First Last" for message copy; "someone" when empty. */
    public static String displayName(User user) {
        if (user == null) {
            return "someone";
        }
        String name = ((user.getFirstname() == null ? "" : user.getFirstname()) + " "
                + (user.getLastname() == null ? "" : user.getLastname())).trim();
        return name.isEmpty() ? "someone" : SlackCandidateFacts.mrkdwnSafe(name);
    }

    /**
     * Maps a service validation message (400 — findings §P4: explicit
     * validation with field names in the message) to the input block that
     * caused it, so the error renders inline on the right field. Unmapped
     * messages anchor to {@code fallbackBlockId}.
     */
    public static Map<String, String> validationErrors(String message, String fallbackBlockId) {
        String text = message == null ? "Invalid input" : message;
        String blockId = fallbackBlockId;
        if (text.contains("linkedinUrl")) {
            blockId = SlackRecruitmentViews.BLOCK_LINKEDIN;
        } else if (text.contains("email")) {
            blockId = SlackRecruitmentViews.BLOCK_EMAIL;
        } else if (text.contains("candidateName")) {
            blockId = SlackRecruitmentViews.BLOCK_CANDIDATE_NAME;
        } else if (text.contains("whyText")) {
            blockId = SlackRecruitmentViews.BLOCK_WHY;
        } else if (text.contains("referrerRelation")) {
            blockId = SlackRecruitmentViews.BLOCK_RELATION;
        } else if (text.contains("externalReferrerName")) {
            blockId = SlackRecruitmentViews.BLOCK_EXTERNAL_REFERRER;
        } else if (text.contains("firstName")) {
            blockId = SlackRecruitmentViews.BLOCK_FIRST_NAME;
        } else if (text.contains("lastName")) {
            blockId = SlackRecruitmentViews.BLOCK_LAST_NAME;
        } else if (text.contains("phone")) {
            blockId = SlackRecruitmentViews.BLOCK_PHONE;
        } else if (text.contains("experienceLevel")) {
            blockId = SlackRecruitmentViews.BLOCK_EXPERIENCE;
        } else if (text.contains("dismissReason")) {
            blockId = SlackRecruitmentViews.BLOCK_DISMISS_REASON;
        }
        return Map.of(blockId, text);
    }

    /** {@code INTERVIEW_1} → {@code Interview 1} — stage names for cards. */
    public static String humanizeStage(String enumName) {
        String lower = enumName.toLowerCase().replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
