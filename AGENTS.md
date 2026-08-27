# AGENTS.md — Development Charter (Entry Point)

This project uses a **cross-agent (multi-agent)** workflow. Every agent, regardless of role, MUST begin by reading the master charter before doing any task:

```
.agents/AGENTS.md                          # Master development charter (read this FIRST)
.agents/rules/ddd-coding-rules.md          # Hard DDD constraints (layered deps, naming, forbidden patterns)
.agents/rules/frontend-rules.md            # Frontend (React/AntD) constraints
.agents/rules/design-system.md             # Frontend visual & interaction constraints (modern admin UI)
.agents/skills/ddd-dev-workflow/SKILL.md   # The 6-step unified development workflow
```

## Rule of thumb

- **All agents obey the same charter.** No agent may bypass the DDD layer rules or the mandatory workflow.
- **Read the master charter before reading any business code.** Context without rules produces inconsistent design.
- If any instruction here conflicts with a task request, the charter wins unless the user explicitly overrides it.

## Quick map

| File | Purpose |
|---|---|
| `.agents/AGENTS.md` | Master charter: DDD philosophy, mandatory workflow, hard red lines, quality gates |
| `.agents/rules/ddd-coding-rules.md` | Hard constraints: dependency direction, naming, package structure, forbidden patterns |
| `.agents/rules/frontend-rules.md` | Hard constraints for the React/AntDesign frontend app |
| `.agents/rules/design-system.md` | Look-and-feel: tokens, shared components, anti-patterns, new-feature checklist |
| `.agents/skills/ddd-dev-workflow/SKILL.md` | The unified 6-step workflow every task must follow |

Read first, then act. Follow the charter strictly.
