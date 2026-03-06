# Project Context

## About project

**Name:** CWS (CW Service for Android)
**Purpose**: Share&sync data, clipboard, AI assistance, present API

## Stack

**Language:** Kotlin (main app, android), TypeScript (for `endpoint`)
**Platform:** Android (main app), any other (for `endpoint`)

## Structure

Clean Architecture: TODO

- Base dir: `IOClientAndroid/`
- Full dir: `/home/u2re-dev/IOClientAndroid/`

```
U2RE.space
    apps/
        CrossWord/
            src/
                endpoint/ # where was linked
                frontend/ # another application (PWA)
IOClientAndroid/ # current dir, later will be renamed/aliased/reformed to `CWSAndroid/`
    .cursor/
    .specify/
    .vscode/
    app/
        src/
            main/
                kotlin/
                    space/
                        u2re/
                            android/
                                service/
                                ui/
                    com/
    endpoint/       # symbolic link to ../U2RE.space/apps/CrossWord/src/endpoint
    *.py            # scripts, planned to move into scripts/*.py
    scripts/*.py    # TODO
    package.json    # NPM control, earlier was for NativeScript
    AGENTS.md
```

## Conventions

- Git: Conventional Commits (feat/fix/refactor/docs/test/chore)
