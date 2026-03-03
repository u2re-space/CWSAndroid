# AGENTS.md

## Pantry — persistent notes

You have access to a persistent note storage system via the `pantry` MCP tools.

**Session start — MANDATORY**: Before doing any work, retrieve notes from previous sessions:

- Call `pantry_context` to get recent notes for this project
- If the request relates to a specific topic, also call `pantry_search` with relevant terms

**Session end — MANDATORY**: After any task that involved changes, decisions, bugs, or learnings, call `pantry_store` with:

- `title`: short descriptive title
- `what`: what happened or was decided
- `why`: reasoning behind it
- `impact`: what changed
- `category`: one of `decision`, `pattern`, `bug`, `context`, `learning`
- `details`: full context for a future agent with no prior knowledge

Do not skip either step. Notes are how context survives across sessions.

## Token Usage Optimization

To keep context windows clean and save tokens:
- Use **Grep** to find specific functions/variables instead of reading entire large files.
- Restrict file reading bounds if the file is massive.
- Do not proactively load generated code (`build/`, `.gradle/`, minified scripts) into context.
- Be concise in your thoughts and responses.
