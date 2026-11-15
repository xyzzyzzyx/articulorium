# OpenCode

The project's agent skills live in [`.claude/skills/`](../.claude/skills), which both Claude Code
and OpenCode load, so both assistants use the same skills:

| Skill | Use |
|---|---|
| `articulorium` | architecture: which module owns what, how services talk |
| `code-quality` | the quality bar, tests, verification and review format |
| `spring-boot-best-practices` | designing Java changes in the services and starters |
| `angular-best-practices` | designing changes in `ui` and `discovery-ui` |

Do not add copies under `.opencode/skills/`: OpenCode gives a skill there precedence over the one
with the same name in `.claude/skills/`, and the two would drift apart.
