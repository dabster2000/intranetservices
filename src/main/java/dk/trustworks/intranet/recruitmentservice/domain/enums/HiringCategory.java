package dk.trustworks.intranet.recruitmentservice.domain.enums;

public enum HiringCategory {
    PRACTICE_CONSULTANT(PipelineKind.CONSULTANT),
    JUNIOR_CONSULTANT(PipelineKind.CONSULTANT),
    STAFF(PipelineKind.OTHER),
    PARTNER_OR_LEADERSHIP(PipelineKind.OTHER),
    SPECIAL_CASE(PipelineKind.OTHER);

    private final PipelineKind pipelineKind;

    HiringCategory(PipelineKind pipelineKind) {
        this.pipelineKind = pipelineKind;
    }

    public PipelineKind pipelineKind() {
        return pipelineKind;
    }
}
