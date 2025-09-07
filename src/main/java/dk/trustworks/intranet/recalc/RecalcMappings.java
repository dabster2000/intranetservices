package dk.trustworks.intranet.recalc;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

public final class RecalcMappings {

    private RecalcMappings() {}

    public static Targets targetsFor(RecalcTrigger t) {
        return switch (t) {
            case SCHEDULED_BI, STATUS_CHANGE, FULL_RECALC ->
                new Targets(EnumSet.of(Target.SALARY, Target.OPERATIONS), Optional.of(PipelineStage.WORK_AGGREGATES));
            case WORK_ENTRY_CHANGE ->
                new Targets(EnumSet.of(Target.OPERATIONS), Optional.of(PipelineStage.WORK_AGGREGATES));
            case AVAILABILITY_CHANGE ->
                new Targets(EnumSet.of(Target.OPERATIONS), Optional.of(PipelineStage.AVAILABILITY));
            case CONTRACT_CONSULTANT_CHANGE ->
                new Targets(EnumSet.of(Target.OPERATIONS), Optional.of(PipelineStage.BUDGET));
        };
    }

    public static List<PipelineStage> orderedStagesFrom(PipelineStage start) {
        // Fixed order: WORK_AGGREGATES → AVAILABILITY → BUDGET
        List<PipelineStage> order = new ArrayList<>();
        switch (start) {
            case WORK_AGGREGATES -> {
                order.add(PipelineStage.WORK_AGGREGATES);
                order.add(PipelineStage.AVAILABILITY);
                order.add(PipelineStage.BUDGET);
            }
            case AVAILABILITY -> {
                order.add(PipelineStage.AVAILABILITY);
                order.add(PipelineStage.BUDGET);
            }
            case BUDGET -> {
                order.add(PipelineStage.BUDGET);
            }
        }
        return order;
    }
}
