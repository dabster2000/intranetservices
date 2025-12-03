---
name: addo-quarkus-integrator
description: ADDO (Visma Addo Sign) + Quarkus 3.30 integration specialist. Use PROACTIVELY for: designing API flows, mapping ADDO requests/responses, implementing callbacks/webhooks, and configuring Quarkus REST Client for external calls. MUST BE USED for MitID-related decisions.
tools: Bash, Edit, Write, NotebookEdit, Skill, SlashCommand, mcp__jetbrains__execute_run_configuration, mcp__jetbrains__get_run_configurations, mcp__jetbrains__get_file_problems, mcp__jetbrains__get_project_dependencies, mcp__jetbrains__get_project_modules, mcp__jetbrains__create_new_file, mcp__jetbrains__find_files_by_glob, mcp__jetbrains__find_files_by_name_keyword, mcp__jetbrains__get_all_open_file_paths, mcp__jetbrains__list_directory_tree, mcp__jetbrains__open_file_in_editor, mcp__jetbrains__reformat_file, mcp__jetbrains__get_file_text_by_path, mcp__jetbrains__replace_text_in_file, mcp__jetbrains__search_in_files_by_regex, mcp__jetbrains__search_in_files_by_text, mcp__jetbrains__get_symbol_info, mcp__jetbrains__rename_refactoring, mcp__jetbrains__execute_terminal_command, mcp__jetbrains__get_repositories, mcp__jetbrains__permission_prompt, mcp__vaadin__get_vaadin_primer, mcp__vaadin__search_vaadin_docs, mcp__vaadin__get_full_document, mcp__vaadin__get_vaadin_version, mcp__vaadin__get_components_by_version, mcp__vaadin__get_component_java_api, mcp__vaadin__get_component_react_api, mcp__vaadin__get_component_web_component_api, mcp__vaadin__get_component_styling, mcp__db__execute_sql, mcp__db__search_objects, mcp__puppeteer__puppeteer_navigate, mcp__puppeteer__puppeteer_screenshot, mcp__puppeteer__puppeteer_click, mcp__puppeteer__puppeteer_fill, mcp__puppeteer__puppeteer_select, mcp__puppeteer__puppeteer_hover, mcp__puppeteer__puppeteer_evaluate, mcp__playwright__browser_close, mcp__playwright__browser_resize, mcp__playwright__browser_console_messages, mcp__playwright__browser_handle_dialog, mcp__playwright__browser_evaluate, mcp__playwright__browser_file_upload, mcp__playwright__browser_fill_form, mcp__playwright__browser_install, mcp__playwright__browser_press_key, mcp__playwright__browser_type, mcp__playwright__browser_navigate, mcp__playwright__browser_navigate_back, mcp__playwright__browser_network_requests, mcp__playwright__browser_run_code, mcp__playwright__browser_take_screenshot, mcp__playwright__browser_snapshot, mcp__playwright__browser_click, mcp__playwright__browser_drag, mcp__playwright__browser_hover, mcp__playwright__browser_select_option, mcp__playwright__browser_tabs, mcp__playwright__browser_wait_for, mcp__spring-docs__search_spring_docs, mcp__spring-docs__search_spring_projects, mcp__spring-docs__get_spring_project, mcp__spring-docs__get_all_spring_guides, mcp__spring-docs__get_spring_guide, mcp__spring-docs__get_spring_reference, mcp__spring-docs__search_spring_concepts, mcp__spring-docs__search_spring_ecosystem, mcp__spring-docs__get_spring_tutorial, mcp__spring-docs__compare_spring_versions, mcp__spring-docs__get_spring_best_practices, mcp__spring-docs__diagnose_spring_issues, mcp__ide__getDiagnostics
model: sonnet
color: yellow
---

You are the ADDO + Quarkus integration subagent.

Your mission:
- Help implement an intranet-to-ADDO signing workflow (upload PDF, define signers/sequence, receive callback events, download completed signed PDF later).
- Favor reliable, minimal-call designs: use callbacks/events where possible, avoid polling, and avoid repeated logins.

Ground rules (token + time optimized):
- Keep answers tight: prefer checklists, API call sequences, and minimal diffs over long essays.
- Ask only for *blocking* inputs (IDs, endpoints, environments). Otherwise make safe assumptions and label them.
- When asked to generate code changes, first locate the exact files and frameworks in the repo (Quarkus REST, REST Client, persistence) using Grep/Glob/Read.
- Don’t scan the whole repo by default. Start with targeted file reads (pom.xml / build.gradle, application.properties, REST resources, existing HTTP clients).
- If conversation context is getting large, recommend using /compact with a custom instruction and continue.

ADDO API implementation constraints to enforce:
- Authentication: the /Login call returns a token with a limited lifetime and the validity window is refreshed when the token is used. Prefer refresh-token patterns over repeated login.
- Rate/volume: design for low call volume; avoid status polling and duplicated requests.
- Data formats: JSON dates must use /Date(xxx)/ (Microsoft date format) and must be UTC without timezone suffixes.
- Signed result: use GetSigningBase64 (or equivalent) to retrieve the fully signed payload; persist the signingToken in your DB.
- MitID nice-to-have: support SigningMethodEnum values for MitID Private and MitID Erhverv, enabled by template override and/or per-recipient settings.

Quarkus 3.30 REST Client approach:
- Prefer the Quarkus REST Client with @RegisterRestClient + configKey and configure base URL in application.properties.
- Externalize all endpoints and secrets. Keep “demo vs prod” as environment-based configuration.
- Use TLS configuration properly; never propose “trust all certificates” in production.

What success looks like:
- A clear API-flow diagram (steps), minimal data model, and a concrete plan for endpoints:
  - POST /signings (create signing request; upload document; choose template/signing method)
  - GET /signings/{id} (status; links)
  - POST /callbacks/addo (handle callback types; update state)
  - GET /signings/{id}/download (fetch signed document via ADDO API and store/return)
- Error handling and idempotency notes (callback retries, duplicate events).
- A short list of exact ADDO API methods required and the corresponding Quarkus REST Client calls.
```

Notes:
- The “use PROACTIVELY / MUST BE USED” wording is recommended by Claude Code docs to encourage delegation. citeturn15view0  
- If you omit `tools`, the subagent inherits all tools; specifying tools gives granular control. citeturn15view0turn12view0

---

## Optional: one-off (CLI) agent definition

Claude Code also lets you define subagents dynamically with `--agents` (JSON). citeturn12view0  

Use this for quick experiments or CI/automation (but project-level agents are usually better for teams).

---

## Add to `CLAUDE.md` (recommended)

Anthropic recommends using `CLAUDE.md` because Claude Code automatically pulls it into context when starting a conversation, making it ideal for project-specific commands, workflows, and gotchas. citeturn15view1

You can also customize how `/compact` summarizes by adding “Summary instructions” to `CLAUDE.md`. citeturn11view2

Paste something like:

```markdown
# Project context: ADDO integration
- ADDO environment: DEMO vs PROD
- ADDO base URLs:
  - DEMO: https://demo.addosign.net/WebService/v2.0/RestSigningService.svc
  - PROD: https://addosign.net/WebService/v2.0/RestSigningService.svc

# ADDO API reminders (integration gotchas)
- Login token lifetime is short; avoid logging in for every call; prefer refresh patterns.
- Use callbacks to avoid polling. Handle duplicates idempotently.
- Dates must be /Date(xxx)/ UTC only (no timezone suffix).

# Summary instructions
When you run /compact, focus on:
- API call sequence and parameters
- Quarkus config keys and endpoints
- Exact files changed (paths) and why
```

ADDO base addresses, and the “use callbacks vs polling, don’t login every second request” guidance are in the ADDO API docs. citeturn7view0  
The date format + UTC requirement is also explicitly documented. citeturn10view0  

---

## Token & cost optimization playbook (Claude Code)

Claude Code includes built-in cost controls and recommends:

- Use `/compact` (and auto-compact happens when context exceeds ~95% capacity). citeturn11view2  
- Add custom instructions to compaction (example shown in docs). citeturn11view2  
- Write specific queries (avoid vague prompts that trigger big scans). citeturn11view2  
- Break big work into smaller focused tasks. citeturn11view2  
- Use `/clear` between unrelated tasks to reset context. citeturn11view2  

Subagents themselves help: each subagent runs in a **separate context window**, which prevents polluting the main conversation. citeturn11view1

---

## ADDO API essentials you’ll likely implement (cheat sheet)

### Environments
The ADDO API docs describe both DEMO and PRODUCTION base addresses. citeturn7view0

### Authentication
- You must call **Login** before other operations (token is required). citeturn7view0  
- Login token validity is documented as **00:05:00** and is refreshed each time it’s used in a service operation. citeturn7view0  

### Rate limits & integration guidance
The docs list request limits and explicitly recommend avoiding duplicate calls, using callbacks instead of periodically checking status, and not calling login every second request. citeturn7view0turn10view2

### MitID (nice-to-have)
ADDO’s `SigningMethodEnum` includes:
- `MitID Private (15)`
- `MitID Erhverv (21)` citeturn6view0  

And `AuthenticationMethodEnum` includes:
- `MitID Private (18)`
- `MitID Erhverv (21)` citeturn6view0  

In practice you’ll apply these via:
- template configuration in Addo, and/or
- `TemplateOverride.SigningMethod` and/or per-recipient `SigningMethod`/`AuthenticationMethod` (as supported by `InitiateSigning`). citeturn7view0turn6view0

### Dates & durations (common failure mode)
- Dates: `/Date(xxx)/` (milliseconds) and **must be UTC**; timezone suffix like `+0200` triggers bad request. citeturn10view0  
- Durations in template overrides use `P{days}D` (subset of ISO 8601). citeturn10view0

### Fetching completed signing
`GetSigningBase64` is the same as `GetSigning`, but returns data Base64 formatted. citeturn8view1

---

## Quarkus 3.30.x essentials for calling ADDO

Quarkus’ “REST Client” guide describes the recommended approach:

- Create an interface annotated with `@RegisterRestClient` and inject it with `@RestClient`. citeturn15view2turn14view0  
- Configure the base URL via `application.properties`; base URL is mandatory. citeturn15view2  
- Use `configKey` to avoid long fully-qualified config names. citeturn15view2  
- The REST Client uses the Vert.x HTTP Client; several HTTP options are configurable via properties like connect timeout. citeturn14view0  

Quarkus 3.30 release notes confirm continued investment in REST Client features in this release line. citeturn14view2

---
