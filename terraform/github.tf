# Lets GitHub Actions deploy the React app and the API without storing AWS keys
# in GitHub.
#
# The workflow asks GitHub for a short-lived OIDC token that says "this is repo
# X, running on branch Y", and trades it with AWS STS for temporary credentials
# for the role below. Nothing long-lived exists to leak.

# One per AWS account. If the account ever gets a second Terraform setup that
# needs GitHub, it should import this rather than create another.
resource "aws_iam_openid_connect_provider" "github" {
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
}

data "aws_iam_policy_document" "github_assume" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    # Only workflows running on master of this one repository. A pull request
    # branch, a fork or any other repository gets a token AWS will refuse.
    # The repository uses GitHub's immutable subject format,
    #   repo:<owner>@<owner id>/<repo>@<repo id>:ref:refs/heads/master
    # not the classic repo:<owner>/<repo>:..., so the classic form never matches
    # and the role assumption fails with "Not authorized to perform
    # sts:AssumeRoleWithWebIdentity".
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["${var.github_oidc_subject_prefix}:ref:refs/heads/master"]
    }
  }
}

resource "aws_iam_role" "github_deploy_site" {
  name               = "wants-github-deploy-site"
  assume_role_policy = data.aws_iam_policy_document.github_assume.json
}

# Exactly what `aws s3 sync --delete` and one invalidation need, on this bucket
# and this distribution only.
data "aws_iam_policy_document" "deploy_site" {
  statement {
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.site.arn]
  }

  statement {
    actions   = ["s3:PutObject", "s3:DeleteObject"]
    resources = ["${aws_s3_bucket.site.arn}/*"]
  }

  statement {
    actions   = ["cloudfront:CreateInvalidation"]
    resources = [aws_cloudfront_distribution.site.arn]
  }
}

resource "aws_iam_role_policy" "deploy_site" {
  name   = "deploy-site"
  role   = aws_iam_role.github_deploy_site.id
  policy = data.aws_iam_policy_document.deploy_site.json
}

# --- Backend deploy -------------------------------------------------------------

# A second role for redeploying the API, separate from the site role so each job
# holds only what it needs. Same trust: this repository's master branch only.
resource "aws_iam_role" "github_deploy_api" {
  name               = "wants-github-deploy-api"
  assume_role_policy = data.aws_iam_policy_document.github_assume.json
}

# Run one shell command on the API instance through SSM Run Command, and read
# back how it went. Nothing else: no SSH, no other instances, no other
# documents.
data "aws_iam_policy_document" "deploy_api" {
  # The AWS-owned document that runs a shell script. AWS documents have no
  # account id in their ARN.
  statement {
    actions   = ["ssm:SendCommand"]
    resources = ["arn:aws:ssm:${var.region}::document/AWS-RunShellScript"]
  }

  # Only instances tagged Name=wants-api. Matching on the tag rather than the
  # instance id keeps the permission valid when the instance is replaced.
  statement {
    actions   = ["ssm:SendCommand"]
    resources = ["arn:aws:ec2:${var.region}:${data.aws_caller_identity.current.account_id}:instance/*"]

    condition {
      test     = "StringEquals"
      variable = "ssm:resourceTag/Name"
      values   = ["wants-api"]
    }
  }

  # Find the instance id by tag, and poll the command's result. Neither action
  # supports resource-level permissions, and both are read-only.
  statement {
    actions   = ["ec2:DescribeInstances", "ssm:GetCommandInvocation"]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "deploy_api" {
  name   = "deploy-api"
  role   = aws_iam_role.github_deploy_api.id
  policy = data.aws_iam_policy_document.deploy_api.json
}
