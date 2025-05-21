package dk.trustworks.intranet.bi.events;

import dk.trustworks.intranet.dao.workservice.model.WorkFull;

import java.util.List;

public record AvailabilityData(List<WorkFull> workList) { }