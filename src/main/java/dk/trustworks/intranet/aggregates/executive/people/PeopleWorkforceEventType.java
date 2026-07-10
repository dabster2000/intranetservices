package dk.trustworks.intranet.aggregates.executive.people;

/** Closed event vocabulary shared by workforce flow and upcoming detail. */
public enum PeopleWorkforceEventType {
    FIRST_HIRE,
    REHIRE,
    DEPARTURE,
    COMPANY_TRANSFER,
    TRANSFER_IN,
    TRANSFER_OUT,
    LEAVE_START,
    LEAVE_RETURN
}
