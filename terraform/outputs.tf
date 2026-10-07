output "public_ip" {
  description = "Public IP of the instance."
  value       = aws_instance.app.public_ip
}

output "api_url" {
  description = "Base URL of the API."
  value       = "http://${aws_instance.app.public_ip}:${var.app_port}/wants"
}

output "ssh" {
  description = "SSH command for the instance."
  value       = "ssh -i <path-to-key>.pem ec2-user@${aws_instance.app.public_ip}"
}
