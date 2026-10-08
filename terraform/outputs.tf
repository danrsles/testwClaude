output "public_ip" {
  description = "Fixed public IP of the API (an Elastic IP). Add it to the hosted database's allowed IP addresses."
  value       = aws_eip.app.public_ip
}

output "api_url" {
  description = "Base URL of the API."
  value       = "http://${aws_eip.app.public_ip}:${var.app_port}/wants"
}

output "ssh" {
  description = "SSH command for the instance."
  value       = "ssh -i <path-to-key>.pem ec2-user@${aws_eip.app.public_ip}"
}

output "db_password_parameter" {
  description = "SSM parameter holding the database password."
  value       = aws_ssm_parameter.db_password.name
}
