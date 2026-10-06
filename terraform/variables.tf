variable "region" {
  description = "AWS region to deploy into."
  type        = string
  default     = "us-east-1"
}

variable "instance_type" {
  description = "EC2 instance type. t3.micro is free-tier eligible."
  type        = string
  default     = "t3.micro"
}

variable "image" {
  description = "Docker Hub image to run."
  type        = string
  default     = "danrsles/wants-api:latest"
}

variable "ssh_cidr" {
  description = "CIDR allowed to reach port 22. Your own IP, as x.x.x.x/32."
  type        = string
}

variable "app_cidr" {
  description = "CIDR allowed to reach the API port. Use 0.0.0.0/0 to make it public; the API has no authentication, so a single /32 is safer while testing."
  type        = string
  default     = "0.0.0.0/0"
}

variable "key_name" {
  description = "Name of an existing EC2 key pair for SSH."
  type        = string
}

variable "app_port" {
  description = "Host port the API listens on."
  type        = number
  default     = 8080
}

variable "mysql_database" {
  description = "Database the app connects to."
  type        = string
  default     = "demo"
}

variable "mysql_user" {
  description = "Application database user."
  type        = string
}

variable "mysql_password" {
  description = "Application database password."
  type        = string
  sensitive   = true
}

variable "mysql_root_password" {
  description = "MySQL root password."
  type        = string
  sensitive   = true
}
