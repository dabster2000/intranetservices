---
description: Scan the repo for features and candidate aggregate roots, then build the DDD inventory.
allowed-tools: Bash, Read, Grep, Glob, SlashCommand
argument-hint: [optional-notes]
---

Please:

1) Use the **orchestrator-ddd** subagent to:
    - Initialize `.claude/out/`
    - Call `repo-cartographer`, `feature-miner`, and `ddd-aggregate-hunter` in sequence
    - Merge and write `.claude/out/ddd-inventory.md` and `.claude/out/ddd-inventory.json`

2) If any step produces more than ~200 lines of console output, write it to `.claude/out/scan.log` and reference it.

$ARGUMENTS