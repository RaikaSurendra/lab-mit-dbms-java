---
description: Create a feature branch, commit changes, push, and raise a PR to main
---

## Git Push Workflow

Always follow this workflow when pushing code changes:

1. Create a feature branch from main:
```bash
git checkout main && git pull origin main
git checkout -b <branch-name>
```
Branch naming convention: `feat/lab<N>-<short-description>` (e.g., `feat/lab3-query-optimization`)

// turbo
2. Stage and commit changes with a descriptive message:
```bash
git add -A
git commit -m "<type>: <description>"
```
Commit types: `feat`, `fix`, `docs`, `refactor`, `test`

// turbo
3. Push the branch to origin:
```bash
git push -u origin <branch-name>
```

4. Create a PR using GitHub CLI (or provide the URL):
```bash
gh pr create --base main --title "<PR title>" --body "<PR body with what's covered>"
```

5. After PR is merged, clean up:
```bash
git checkout main && git pull origin main
git branch -d <branch-name>
```
