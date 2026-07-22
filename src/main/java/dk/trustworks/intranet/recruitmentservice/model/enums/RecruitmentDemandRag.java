package dk.trustworks.intranet.recruitmentservice.model.enums;

/**
 * RAG (red/amber/green) urgency of a position's hiring demand (ATS spec
 * §4.1) — powers the coverage rollup on the positions overview. GREEN =
 * comfortably staffed pipeline, YELLOW = needs attention, RED = urgent gap.
 */
public enum RecruitmentDemandRag {
    GREEN,
    YELLOW,
    RED
}
