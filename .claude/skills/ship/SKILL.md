---
description: Commit the current work, push it, open a PR, wait for CI and merge it into master
argument-hint: "[squash|merge] [what the change is, if not obvious]"
disable-model-invocation: true
---

Take the work in the current checkout all the way to master: commit, push, pull request, CI, merge. Only the user starts this, so being invoked is approval to push and merge. It is not approval to commit something they haven't seen, so step 2 still stops once.

Anything typed after `/ship` may name the merge method (`squash` or `merge`) and describe the change: $ARGUMENTS

## 1. Look before touching anything

- `git status --short`, `git branch --show-current`, `git diff --stat`, plus `git log --oneline master..HEAD` for commits already on the branch.
- **Nothing to ship** (clean tree, no commits ahead of master): say so and stop.
- **On `master`:** create a branch first. Name it by kind: `feature/`, `fix/`, `chore/` or `docs/`, plus a short slug. master is protected; a PR is the only way in.
- **Never stage these**, even if they show up: `config/application-prod.properties`, `frontend/.env.local` or any `*.local`, `.env`, `*.pem`, `terraform/terraform.tfvars`, `*.tfstate`, `.playwright-mcp/`. If one is modified or untracked, leave it out and mention it.
- Stage explicit paths, never `git add -A` or `git add .`.
- If the change spans unrelated things (say, a feature and an unrelated config change), suggest splitting it into separate PRs before going on.

## 2. Show it and stop once

Show the user, in one message:
- the files to be committed, grouped by what they are (code, tests, docs);
- the commit message you propose;
- the checks you will run (step 3).

Wait for a yes. A correction to the message or the file list is applied, then go on without asking again.

## 3. Run the checks that match what changed

CI runs everything anyway; these catch a failure before a round trip. Run only what the diff touches:

| Changed | Run |
|---|---|
| `frontend/**` | in `frontend/`: `npm test`, `npm run lint`, `npm run format:check`, `npx tsc -b` |
| `src/**`, `pom.xml` | `mvn test`; add `mvn verify` (needs Docker) if repositories, migrations, entities or `@Transactional` code changed |
| only docs, `.claude/`, workflows | nothing to run |

Any failure: stop and report it with the output. Don't commit around it, and never skip a hook (`--no-verify`).

Feature changes should come with a README update (CLAUDE.md asks for it). If the diff adds or changes behaviour and README.md isn't in it, say so before committing.

## 4. Commit and push

- Message: a short imperative title, then a body that says **why**, wrapped at about 72 columns, in the style of `git log`. End it with the attribution lines this session's instructions give for commits.
- One commit per meaningful step. A mechanical change (a reformat, a rename) gets its own commit.
- `git push -u origin <branch>`. Never force-push a branch someone else may have, and never push to master.

## 5. Open the pull request

`gh pr create --base master` with:
- a title that says what changed;
- a body: what changes and why, the notable decisions, and **how it was checked** (the commands from step 3 and their results, any manual check);
- issue links (`Closes #N`) when the change finishes an issue;
- the attribution line this session's instructions give for PRs.

If merging needs a specific method (see step 6), say so at the top of the body.

## 6. Wait for CI, then merge

- `gh pr checks <n> --watch --interval 20`. "Test API" and "Test and build web" are required; publish and deploy jobs show as skipped on a PR, which is normal.
- **A check fails:** stop. Show the failing job's log (`gh run view <id> --log-failed`), don't merge, and don't retry blindly.
- **Merge method:**
  - `squash` by default: one tidy commit on master.
  - `merge` (a merge commit) when the commits must survive with their ids: anything named in `.git-blame-ignore-revs`, or separate commits that are meant to be read one by one. Squash and rebase both rewrite ids.
  - An argument from the user wins over this choice.
- `gh pr merge <n> --squash|--merge --delete-branch`. Never `--admin`; never bypass the ruleset.

## 7. After the merge

- `git checkout master && git pull --ff-only`, so local master matches.
- A merge to master deploys: `gh run list --branch master --limit 1` and report whether the deploy run passed. If it touched the frontend or the API, remind the user to check the live site; don't claim it works there.
- Report: PR link, merge commit, deploy status, and anything left out in step 1.
