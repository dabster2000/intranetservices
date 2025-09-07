package dk.trustworks.intranet.recalc;

import java.util.Optional;

public record StageResult(boolean changed, String summary, Optional<Throwable> error) {
    public static StageResult ok(String summary, boolean changed) {
        return new StageResult(changed, summary, Optional.empty());
    }
    public static StageResult failed(String summary, Throwable error) {
        return new StageResult(false, summary, Optional.ofNullable(error));
    }
}
