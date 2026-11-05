# Contributing to TableGrip

Thanks for taking the time to contribute. This guide covers how to report problems, propose changes and get a pull
request merged.

By taking part in this project you agree to follow its [Code of Conduct](CODE_OF_CONDUCT.md).

## Reporting bugs

Search the [existing issues](https://github.com/duongductrong/TableGrip/issues) first. If the bug isn't there, open a
new issue with:

- the TableGrip version and your OS;
- the database engine and version you were connected to;
- the steps that lead to the problem, what you expected and what happened instead;
- relevant lines from `~/.tablegrip/logs/`, with passwords, hostnames and data removed.

## Security issues

Please don't open a public issue for a vulnerability. Report it privately through
[GitHub's security advisories](https://github.com/duongductrong/TableGrip/security/advisories/new).

## Suggesting features

Open an issue describing the problem you want solved before writing a large change. Agreeing on the approach first
saves both sides a rewrite.

## Development setup

Everything needed to build, run and test the project is in the [development guide](docs/development.md).

## Pull requests

1. Fork the repository and create a branch from `master`.
2. Keep each pull request to one change. Refactors and behaviour changes go in separate PRs.
3. Add or update tests in `core` for any logic change.
4. Update the [user guide](docs/guide.md) when a feature's behaviour changes.
5. Run `./gradlew check` before pushing. Besides the tests it checks driver bundling and third-party licenses; see
   [Drivers and licenses](docs/development.md#drivers-and-licenses) when it fails.
6. Open the pull request and describe what changed and why. Screenshots help for UI changes.

## Commit messages

Commits follow [Conventional Commits](https://www.conventionalcommits.org/); the release notes are generated from them.

```
feat: add snippets to the SQL editor
fix(grid): keep the selection after a reload
docs: describe scheduled backups
```

Common types: `feat`, `fix`, `refactor`, `perf`, `test`, `docs`, `build`, `ci`, `chore`.

## License

By contributing, you agree that your contributions are licensed under the [Apache License 2.0](LICENSE).
