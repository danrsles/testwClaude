output "site_url" {
  description = "The site, over HTTPS. /api/* on it reaches the API."
  value       = "https://${aws_cloudfront_distribution.site.domain_name}/"
}

output "api_public_ip" {
  description = "Fixed public IP of the API server (an Elastic IP). Its app port admits only CloudFront; this is the address it connects out from, so add it to the hosted database's allowed IP addresses."
  value       = aws_eip.app.public_ip
}

output "ssh_api" {
  description = "SSH command for the API server."
  value       = "ssh -i <path-to-key>.pem ec2-user@${aws_eip.app.public_ip}"
}

output "db_password_parameter" {
  description = "SSM parameter holding the database password."
  value       = aws_ssm_parameter.db_password.name
}

# --- For the GitHub Actions deploy job (repository variables) -----------------

output "site_bucket" {
  description = "Set as the SITE_BUCKET repository variable in GitHub."
  value       = aws_s3_bucket.site.bucket
}

output "cloudfront_distribution_id" {
  description = "Set as the CLOUDFRONT_DISTRIBUTION_ID repository variable in GitHub."
  value       = aws_cloudfront_distribution.site.id
}

output "github_deploy_role_arn" {
  description = "Set as the AWS_DEPLOY_ROLE_ARN repository variable in GitHub."
  value       = aws_iam_role.github_deploy_site.arn
}
