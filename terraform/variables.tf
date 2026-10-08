variable "region" {
  description = "AWS region to deploy into. us-east-1 is closest to the Aiven database in DigitalOcean NYC."
  type        = string
  default     = "us-east-1"
}

variable "instance_type" {
  description = "EC2 instance type. t3.micro is free-tier eligible; the database is hosted, so 1 GB is enough for the API alone."
  type        = string
  default     = "t3.micro"
}

variable "image" {
  description = "Docker Hub image to run. The image itself selects the prod profile."
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

# --- Database (Aiven PostgreSQL) ---------------------------------------------

variable "db_url" {
  description = "Full JDBC URL of the hosted database. Its sslrootcert must be /certs/ca.pem, where the certificate is mounted in the container, e.g. jdbc:postgresql://<host>:<port>/defaultdb?sslmode=verify-full&sslrootcert=/certs/ca.pem"
  type        = string

  validation {
    condition     = startswith(var.db_url, "jdbc:postgresql://") && strcontains(var.db_url, "sslrootcert=/certs/ca.pem")
    error_message = "db_url must be a jdbc:postgresql:// URL with sslrootcert=/certs/ca.pem (the path inside the container, not on your machine)."
  }
}

variable "db_user" {
  description = "Database user, e.g. avnadmin."
  type        = string
}

variable "db_password" {
  description = "Database password. Written to SSM Parameter Store as a SecureString and never stored in Terraform's plan or state. Supply it as TF_VAR_db_password in your shell rather than in terraform.tfvars."
  type        = string
  sensitive   = true
  # Ephemeral values exist only while Terraform runs. They can feed write-only
  # arguments such as value_wo, and Terraform refuses to put them anywhere that
  # would be saved.
  ephemeral = true
}

variable "db_password_version" {
  description = "Bump this (1 -> 2 -> ...) whenever db_password changes. The password is write-only, so Terraform cannot detect a change by comparing values; a new version number is what makes it send the new one."
  type        = number
  default     = 1
}

variable "db_ca_cert_path" {
  description = "Path to the database provider's CA certificate (Aiven's ca.pem), relative to this directory. Git-ignored."
  type        = string
  default     = "ca.pem"
}

variable "db_password_parameter" {
  description = "SSM parameter name for the database password."
  type        = string
  default     = "/wants/db-password"
}
