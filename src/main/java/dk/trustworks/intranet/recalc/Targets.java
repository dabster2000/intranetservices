package dk.trustworks.intranet.recalc;

import java.util.EnumSet;
import java.util.Optional;

public record Targets(EnumSet<Target> set, Optional<PipelineStage> startStage) {
    public boolean includesSalary()    { return set.contains(Target.SALARY); }
    public boolean includesOperations(){ return set.contains(Target.OPERATIONS); }
}
