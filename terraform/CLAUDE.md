# terraform/

The API runs on one EC2 instance and the React app on S3 + CloudFront; the README's deployment section describes what is built. These are the things that are easy to get wrong.

**Before blaming the code:** the AWS account has a budget action (`Monthly-50-Max-Budget`) that attaches the deny policy `MyActualBudgetKillSwitchPayload` (`ec2:RunInstances`, `ec2:StartInstances`, `ecs:*`) to the `admin-worker` user at 100% of $50. If `apply` fails with `UnauthorizedOperation ... explicit deny ... MyActualBudgetKillSwitchPayload`, check the budget action's status first. Its automatic reset has failed before (`RESET_FAILURE`), leaving the block on with no spend.

**Secrets**
- The boot script (`user_data.sh.tftpl`) must never `set -x`: it would print the database password and the origin secret into `/var/log/cloud-init-output.log`.
- The DB password goes in through `value_wo` from an `ephemeral = true` variable, so it never enters the plan or state (hence Terraform >= 1.11). Supply it as `TF_VAR_db_password`, never in `terraform.tfvars`, which outranks the environment variable and would silently override it.
- Write-only values can't be diffed: Terraform re-sends the password only when `db_password_version` changes. Raise it with every password change.
- A password change only updates SSM. The container keeps the one written to `/opt/wants/.env` at first boot, so the app needs the fetch-and-write steps rerun, or the instance replaced.
- The instance role has `ssm:GetParameter` on the two parameters and no `kms:Decrypt`. That is not an omission: the AWS-managed `aws/ssm` key allows decryption through SSM for principals in the account.
- The origin secret, unlike the password, *is* in `terraform.tfstate`, because the distribution's config holds it. Rotate with `terraform apply -replace=random_password.origin_secret`.

**Things that replace or break resources**
- Any change to the user data (image tag, certificate, URL, user, Clerk settings) replaces the instance (`user_data_replace_on_change`).
- The instance is replaced destroy-first (no `create_before_destroy`), so old and new never overlap on Aiven's ~17 connection slots. Switching to create-before-destroy means budgeting two pools.
- The security group's description still says "public app port". It is stale on purpose: changing a description forces the group's replacement.
- Port 8080 admits only the managed prefix list `com.amazonaws.global.cloudfront.origin-facing`, which counts against the group's rule quota by its maximum size (~55 of 60). There is little room for more rules on that group.
- The Elastic IP is associated separately (`aws_eip_association`) so it survives instance replacement and can stay on Aiven's IP allowlist.
- The distribution must depend only on `aws_eip.app`, never on the instance, or the Clerk `authorized-parties` value in the user data makes a cycle.

**CloudFront**
- `functions/strip-api-prefix.js` mirrors the Vite dev proxy's rewrite in `frontend/vite.config.ts`. Keep the two in step.
- SPA fallback is `functions/spa-fallback.js`, deliberately **not** custom error responses: those apply to every behaviour and would turn the API's real 404s into HTML.
- `/api/*` uses `Managed-AllViewerExceptHostHeader`: Host must be the origin's own name or the request misses the instance. The origin must be a DNS name, hence `aws_eip.app.public_dns`.

**Instance setup**
- `ca.pem` is read from `terraform/` (git-ignored) and mounted at `/certs/ca.pem`; the validation on `db_url` requires `sslrootcert=/certs/ca.pem`, so a laptop path is rejected.
- `.env` quotes every value in single quotes, so Compose doesn't interpret the `&` in the JDBC URL.
- `var.clerk_issuer` must be `https://` with no trailing slash: the API matches the issuer exactly.
- After `AmazonSSMManagedInstanceCore` is first attached to a running instance, the SSM agent may take a while to re-register (`aws ssm describe-instance-information`; `sudo systemctl restart amazon-ssm-agent` speeds it up).

**GitHub OIDC (`github.tf`)**
- This repository uses GitHub's immutable OIDC subject format, `repo:danrsles@13107885/testwClaude@1380283664:...`, not the classic `repo:danrsles/testwClaude:...`. A trust policy in the classic form fails with "Not authorized to perform sts:AssumeRoleWithWebIdentity". Read the real prefix with `gh api repos/<owner>/<repo>/actions/oidc/customization/sub`.
- Both roles trust only `refs/heads/master`. The API role's `ssm:SendCommand` matches instances by the tag `Name = wants-api`, not an ID, so it survives replacement.
