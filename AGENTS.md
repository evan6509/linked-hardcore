# Linked Hardcore repository instructions

## Branch and review workflow

- Use `codex/development` (lowercase `codex`) as the primary working branch for routine changes and commits.
- Keep `main` as the reviewed integration and release branch.
- Propose integration with a pull request from `codex/development` into `main`.
- Do not commit or push implementation changes directly to `main`. Do not merge a pull request or publish a manual release without explicit user approval.
- After an approved merge, continue work on `codex/development` and synchronize it with reviewed `main` history without rewriting shared commits.
- Before branch operations, inspect the current branch and working tree. Preserve unrelated edits; do not reset, force-push, or discard shared work.

## Reset and world safety

- Test reset behavior only with disposable fixtures or deployments. Never test reset logic against existing player worlds.
- Do not archive or reset a backend until all transfers have succeeded, the source is empty, and the backend has acknowledged the exact completed world and run IDs.
- Ordinary crashes must preserve worlds. Retain operator configuration and proxy history when resetting a world.

## Validation and evidence

- Follow the build and validation instructions in `README.md` and `docs/`.
- Keep build, automated test, connected-client, deployment, and release evidence distinct; do not claim a check passed unless it was actually run.
