---
title: Contributing
description: How to report bugs, suggest features and get a pull request merged.
---

Bug reports, ideas and pull requests are all welcome. Everyone taking part is expected to follow the
[Code of Conduct](https://github.com/duongductrong/TableGrip/blob/master/CODE_OF_CONDUCT.md).

## Report a bug

Search the [existing issues](https://github.com/duongductrong/TableGrip/issues) first. If it isn't there, open a new
issue with:

- the TableGrip version and your OS;
- the database engine and version you were connected to;
- the steps that lead to the problem, what you expected and what happened instead;
- relevant lines from `~/.tablegrip/logs/`, with passwords, host names and data removed.

## Report a security issue

Please **don't open a public issue** for a vulnerability. Report it privately through
[GitHub's security advisories](https://github.com/duongductrong/TableGrip/security/advisories/new).

## Suggest a feature

Open an issue describing the problem you want solved before writing a large change. Agreeing on the approach first saves
both sides a rewrite.

## Send a pull request

1. Fork the repository and create a branch from `master`.
2. Keep each pull request to **one change**. Refactors and behaviour changes go in separate PRs.
3. Add or update tests in `core` for any logic change.
4. Update the docs (`www/src/content/docs/`) when a feature's behaviour changes.
5. Run `./gradlew check` before pushing. Besides the tests, it checks driver bundling and third-party licenses; see
   [Driver catalog & licenses](/docs/driver-catalog#when-check-fails) when it fails.
6. Open the pull request and describe what changed and why. Screenshots help for UI changes.

Setting up the project is covered in [Build from source](/docs/build-from-source).

## Commit messages

Commits follow [Conventional Commits](https://www.conventionalcommits.org/), because the release notes are generated
from them:

```text
feat: add snippets to the SQL editor
fix(grid): keep the selection after a reload
docs: describe scheduled backups
```

Common types: `feat`, `fix`, `refactor`, `perf`, `test`, `docs`, `build`, `ci`, `chore`.

## Improve these docs

Every page has an **Edit this page on GitHub** link at the bottom. Pages are plain Markdown in
`www/src/content/docs/`, with a little front matter for the title and description. A few extras are available:

```markdown
![Caption shown under the screenshot](shot:editor)

> [!TIP]
> A callout. Also [!NOTE], [!WARNING] and [!IMPORTANT].

Press <kbd>⌘</kbd> <kbd>P</kbd>.
```

New pages also need an entry in `www/src/content/docs-nav.ts`.

## License

By contributing, you agree that your contributions are licensed under the
[Apache License 2.0](https://github.com/duongductrong/TableGrip/blob/master/LICENSE).
