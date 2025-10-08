# Project goal
Inventory features and candidate DDD aggregate roots to plan a staged migration from synchronous REST to async/reactive Quarkus.

# Conventions
- Outputs go under `.claude/out/`.
- If a subagent is about to compact context, it MUST checkpoint results to disk first.