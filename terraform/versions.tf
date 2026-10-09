terraform {
  required_version = ">= 1.11" # write-only arguments and ephemeral variables

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
    # Generates the CloudFront origin secret.
    random = {
      source  = "hashicorp/random"
      version = "~> 3.7"
    }
  }
}

provider "aws" {
  region = var.region
}
