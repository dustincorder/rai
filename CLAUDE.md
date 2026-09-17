@AGENTS.md

Claude-specific notes (canonical rules live in AGENTS.md above):

- Treat `docs/agent/*.md` as on-demand imports, not auto-loaded context.
- Use `/memory` to confirm loaded project memory if behavior looks stale.
- Do not add nested `CLAUDE.md` files unless a subtree genuinely needs
  distinct rules; root guidance covers this single-module app.
