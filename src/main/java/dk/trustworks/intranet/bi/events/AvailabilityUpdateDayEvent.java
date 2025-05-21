package dk.trustworks.intranet.bi.events;

import java.time.LocalDate;

public record AvailabilityUpdateDayEvent(String useruuid, LocalDate testDay, AvailabilityData availabilityData) { }