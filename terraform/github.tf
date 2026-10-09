# Lets GitHub Actions deploy the React app without storing AWS keys in GitHub.
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
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${var.github_repository}:ref:refs/heads/master"]
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
