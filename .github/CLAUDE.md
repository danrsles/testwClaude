# .github/

`workflows/docker-publish.yml` tests every push and pull request, and on `master` publishes the API image and deploys both halves. These are the things that are easy to get wrong.

**Nothing unmerged deploys.** `publish-api`, `deploy-api` and `deploy-web` carry `if: github.ref == 'refs/heads/master'`. Keep that guard on any new job that publishes or deploys. A PR runs the workflow file from its own branch, so a change here is tested by its own PR.

**Configuration lives outside the file**
- Repository *secrets*: `DOCKERHUB_USERNAME`, and `DOCKERHUB_TOKEN`, a Docker Hub access token, not the password.
- Repository *variables*: `CLERK_PUBLISHABLE_KEY` (the build fails if it is empty, since the bundle would be a blank page), and `AWS_DEPLOY_ROLE_ARN`, `AWS_DEPLOY_API_ROLE_ARN`, `SITE_BUCKET`, `CLOUDFRONT_DISTRIBUTION_ID` from `terraform output`. `deploy-web` is skipped until `SITE_BUCKET` exists.
- AWS access is GitHub OIDC, never stored keys; a job that assumes a role needs `permissions: id-token: write`. The trust policy's subject format is a known trap: see `terraform/CLAUDE.md`.

**`test-web` checks formatting.** It runs `npm run format:check` after lint, so a frontend file Prettier would change fails the PR. The Claude hook formats what Claude writes, so this mostly catches hand edits; run `npm run format` in `frontend/` to fix one.

**`deploy-web`**
- It deploys the `dist/` that `test-web` built and uploaded, so the tested build is the deployed build. Don't rebuild it.
- The upload order is deliberate: new `assets/` first (immutable), then everything else with `--delete` and `no-cache`, and only then old assets are deleted. A visitor never gets an `index.html` whose scripts are missing.

**`deploy-api`**
- It sends `deploy/redeploy-api.sh` through SSM Run Command, **base64-encoded**, because quoting would not survive the CLI, the JSON and SSM's own wrapping shell.
- That script must never `set -x`: it sources `/opt/wants/.env`, which holds secrets.
- It only swaps the image and never rewrites `.env`. So anything the app newly requires from `.env` needs `terraform apply` first, then the merge; otherwise the new image fails to start and the health check fails the job.
- There is no rollback; re-running an older commit's workflow republishes its image as `latest`.

`modernize/java-upgrade/` belongs to the GitHub Copilot app modernization extension, not the application, and ignores its own contents. Leave it alone unless the task is about that extension.
