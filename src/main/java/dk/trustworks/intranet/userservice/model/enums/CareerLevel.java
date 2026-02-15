package dk.trustworks.intranet.userservice.model.enums;

public enum CareerLevel {

    // Entry level (no track)
    JUNIOR_CONSULTANT(null),

    // Delivery track
    CONSULTANT(CareerTrack.DELIVERY),
    PROFESSIONAL_CONSULTANT(CareerTrack.DELIVERY),
    SENIOR_CONSULTANT(CareerTrack.DELIVERY),

    // Advisory track
    LEAD_CONSULTANT(CareerTrack.ADVISORY),
    MANAGING_CONSULTANT(CareerTrack.ADVISORY),
    PRINCIPAL_CONSULTANT(CareerTrack.ADVISORY),

    // Leadership track
    ASSOCIATE_MANAGER(CareerTrack.LEADERSHIP),
    MANAGER(CareerTrack.LEADERSHIP),
    SENIOR_MANAGER(CareerTrack.LEADERSHIP),

    // Client Engagement track
    ENGAGEMENT_MANAGER(CareerTrack.CLIENT_ENGAGEMENT),
    SENIOR_ENGAGEMENT_MANAGER(CareerTrack.CLIENT_ENGAGEMENT),
    ENGAGEMENT_DIRECTOR(CareerTrack.CLIENT_ENGAGEMENT),

    // Partner/Director level
    ASSOCIATE_PARTNER(CareerTrack.PARTNER),
    PARTNER(CareerTrack.PARTNER),
    THOUGHT_LEADER_PARTNER(CareerTrack.PARTNER),
    PRACTICE_LEADER(CareerTrack.PARTNER),
    DIRECTOR(CareerTrack.PARTNER),

    // C-Level
    MANAGING_DIRECTOR(CareerTrack.C_LEVEL),
    MANAGING_PARTNER(CareerTrack.C_LEVEL);

    private final CareerTrack track;

    CareerLevel(CareerTrack track) {
        this.track = track;
    }

    public CareerTrack getTrack() {
        return track;
    }
}
