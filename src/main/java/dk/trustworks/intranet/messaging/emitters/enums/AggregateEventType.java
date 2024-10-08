package dk.trustworks.intranet.messaging.emitters.enums;

public enum AggregateEventType {
    CREATE_CLIENT,
    MODIFY_CONTRACT_CONSULTANT,
    UPDATE_WORK,
    CREATE_CONFERENCE_PARTICIPANT, UPDATE_CONFERENCE_PARTICIPANT, CHANGE_CONFERENCE_PARTICIPANT_PHASE,
    CREATE_USER, UPDATE_USER, CREATE_USER_STATUS, DELETE_USER_STATUS, CREATE_USER_SALARY, DELETE_USER_SALARY,
    CREATE_BANK_INFO, CREATE_USER_BANK_INFO, DELETE_USER_BANK_INFO
}
