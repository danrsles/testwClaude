# The React app: static files in a private S3 bucket, served by CloudFront.
# CloudFront is also the only way to the API: /api/* is forwarded to the API
# instance, everything else comes from the bucket.
#
#   https://<id>.cloudfront.net/            -> S3 (index.html)
#   https://<id>.cloudfront.net/profile     -> S3 (index.html; React Router picks the screen)
#   https://<id>.cloudfront.net/api/wants   -> http://<api>:8080/wants

data "aws_caller_identity" "current" {}

# --- Bucket --------------------------------------------------------------------

# Bucket names are global across every AWS account, so the account id makes
# this one unique.
resource "aws_s3_bucket" "site" {
  bucket = "wants-site-${data.aws_caller_identity.current.account_id}"

  # The bucket holds only build output that CI re-uploads on every deploy, so
  # destroy may empty it rather than fail on a non-empty bucket.
  force_destroy = true

  tags = {
    Name = "wants-site"
  }
}

# No public access in any form: visitors reach the files only through
# CloudFront, which signs its requests to the bucket (Origin Access Control).
resource "aws_s3_bucket_public_access_block" "site" {
  bucket                  = aws_s3_bucket.site.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_cloudfront_origin_access_control" "site" {
  name                              = "wants-site"
  description                       = "CloudFront reads the wants-site bucket"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

# Only this distribution may read objects. A bucket policy naming the CloudFront
# service is not public, so the public access block above does not reject it.
data "aws_iam_policy_document" "site_bucket" {
  statement {
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.site.arn}/*"]

    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [aws_cloudfront_distribution.site.arn]
    }
  }
}

resource "aws_s3_bucket_policy" "site" {
  bucket = aws_s3_bucket.site.id
  policy = data.aws_iam_policy_document.site_bucket.json

  depends_on = [aws_s3_bucket_public_access_block.site]
}

# --- Edge functions (the two jobs nginx used to do) ---------------------------

# /api/wants -> /wants, the same rewrite as the Vite dev proxy.
resource "aws_cloudfront_function" "strip_api_prefix" {
  name    = "wants-strip-api-prefix"
  runtime = "cloudfront-js-2.0"
  comment = "Remove the /api prefix before forwarding to the API"
  publish = true
  code    = file("${path.module}/functions/strip-api-prefix.js")
}

# /profile -> /index.html, so client-side routes survive a refresh. Done here
# rather than with CloudFront custom error responses, which would apply to every
# behaviour and turn the API's real 404s into the app's HTML.
resource "aws_cloudfront_function" "spa_fallback" {
  name    = "wants-spa-fallback"
  runtime = "cloudfront-js-2.0"
  comment = "Serve index.html for client-side routes"
  publish = true
  code    = file("${path.module}/functions/spa-fallback.js")
}

# --- Managed policies (looked up by name, not created) -------------------------

data "aws_cloudfront_cache_policy" "optimized" {
  name = "Managed-CachingOptimized"
}

data "aws_cloudfront_cache_policy" "disabled" {
  name = "Managed-CachingDisabled"
}

# Forwards every viewer header except Host, plus all query strings and cookies.
# Host must be the origin's own name for the request to reach the instance.
data "aws_cloudfront_origin_request_policy" "all_viewer_except_host" {
  name = "Managed-AllViewerExceptHostHeader"
}

# --- Distribution --------------------------------------------------------------

resource "aws_cloudfront_distribution" "site" {
  enabled             = true
  comment             = "Wants: React app and API"
  default_root_object = "index.html"
  is_ipv6_enabled     = true

  # North America and Europe edge locations only: the cheapest class, and where
  # the users are.
  price_class = "PriceClass_100"

  origin {
    origin_id                = "site"
    domain_name              = aws_s3_bucket.site.bucket_regional_domain_name
    origin_access_control_id = aws_cloudfront_origin_access_control.site.id
  }

  # The API instance, by the Elastic IP's DNS name (CloudFront origins must be
  # names, not IPs). Plain HTTP to port 8080: the instance has no certificate.
  # The leg from the edge to the instance is therefore unencrypted; the leg from
  # the browser to CloudFront is HTTPS.
  origin {
    origin_id   = "api"
    domain_name = aws_eip.app.public_dns

    custom_origin_config {
      http_port              = var.app_port
      https_port             = 443
      origin_protocol_policy = "http-only"
      origin_ssl_protocols   = ["TLSv1.2"]
    }

    custom_header {
      name  = "X-Origin-Secret"
      value = random_password.origin_secret.result
    }
  }

  # Everything that is not /api: the React app from the bucket, cached.
  default_cache_behavior {
    target_origin_id       = "site"
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["GET", "HEAD"]
    cached_methods         = ["GET", "HEAD"]
    compress               = true
    cache_policy_id        = data.aws_cloudfront_cache_policy.optimized.id

    function_association {
      event_type   = "viewer-request"
      function_arn = aws_cloudfront_function.spa_fallback.arn
    }
  }

  # The API: every method, nothing cached, headers and query strings passed on.
  ordered_cache_behavior {
    path_pattern             = "/api/*"
    target_origin_id         = "api"
    viewer_protocol_policy   = "redirect-to-https"
    allowed_methods          = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
    cached_methods           = ["GET", "HEAD"]
    compress                 = true
    cache_policy_id          = data.aws_cloudfront_cache_policy.disabled.id
    origin_request_policy_id = data.aws_cloudfront_origin_request_policy.all_viewer_except_host.id

    function_association {
      event_type   = "viewer-request"
      function_arn = aws_cloudfront_function.strip_api_prefix.arn
    }
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  # The free *.cloudfront.net certificate. A custom domain would need an ACM
  # certificate in us-east-1 and an alias here.
  viewer_certificate {
    cloudfront_default_certificate = true
  }

  tags = {
    Name = "wants-site"
  }
}
