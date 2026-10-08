data "aws_vpc" "default" {
  default = true
}

data "aws_subnets" "default" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
}

# Amazon Linux 2023, x86_64 - matches the linux/amd64 image being run. The AWS
# CLI is preinstalled, which the bootstrap script uses to read SSM.
data "aws_ami" "al2023" {
  most_recent = true
  owners      = ["amazon"]

  filter {
    name   = "name"
    values = ["al2023-ami-2023.*-kernel-6.1-x86_64"]
  }
}

# --- Database password -------------------------------------------------------

# Encrypted with the AWS-managed aws/ssm key, which costs nothing and whose key
# policy already lets principals in this account decrypt through SSM.
#
# value_wo is write-only: Terraform sends it to AWS but never records it in the
# plan or in terraform.tfstate. Because nothing is stored, Terraform cannot see
# when the password changes, so it re-sends the value only when
# db_password_version changes.
resource "aws_ssm_parameter" "db_password" {
  name             = var.db_password_parameter
  description      = "Wants API database password"
  type             = "SecureString"
  value_wo         = var.db_password
  value_wo_version = var.db_password_version

  tags = {
    Name = "wants-api"
  }
}

# --- Instance identity: may read that one parameter and nothing else ---------

data "aws_iam_policy_document" "ec2_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "api" {
  name               = "wants-api"
  assume_role_policy = data.aws_iam_policy_document.ec2_assume.json
}

data "aws_iam_policy_document" "read_db_password" {
  statement {
    actions   = ["ssm:GetParameter"]
    resources = [aws_ssm_parameter.db_password.arn]
  }
}

resource "aws_iam_role_policy" "read_db_password" {
  name   = "read-db-password"
  role   = aws_iam_role.api.id
  policy = data.aws_iam_policy_document.read_db_password.json
}

resource "aws_iam_instance_profile" "api" {
  name = "wants-api"
  role = aws_iam_role.api.name
}

# --- Network -----------------------------------------------------------------

resource "aws_security_group" "app" {
  name        = "wants-api"
  description = "Wants API: public app port, SSH from one address only"
  vpc_id      = data.aws_vpc.default.id

  tags = {
    Name = "wants-api"
  }
}

resource "aws_vpc_security_group_ingress_rule" "app" {
  security_group_id = aws_security_group.app.id
  description       = "Wants API"
  cidr_ipv4         = var.app_cidr
  from_port         = var.app_port
  to_port           = var.app_port
  ip_protocol       = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "ssh" {
  security_group_id = aws_security_group.app.id
  description       = "SSH from a single address"
  cidr_ipv4         = var.ssh_cidr
  from_port         = 22
  to_port           = 22
  ip_protocol       = "tcp"
}

resource "aws_vpc_security_group_egress_rule" "all" {
  security_group_id = aws_security_group.app.id
  description       = "Outbound for Docker Hub, SSM, package updates and the hosted database"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

# --- Instance ----------------------------------------------------------------

resource "aws_instance" "app" {
  ami                    = data.aws_ami.al2023.id
  instance_type          = var.instance_type
  subnet_id              = data.aws_subnets.default.ids[0]
  vpc_security_group_ids = [aws_security_group.app.id]
  key_name               = var.key_name
  iam_instance_profile   = aws_iam_instance_profile.api.name

  # Still needed for outbound traffic before the Elastic IP attaches; the
  # Elastic IP then replaces it as the instance's public address.
  associate_public_ip_address = true

  # The certificate travels in user data because it is not secret; the
  # password does not, only the name of the parameter holding it.
  user_data = templatefile("${path.module}/user_data.sh.tftpl", {
    image                 = var.image
    app_port              = var.app_port
    region                = var.region
    db_url                = var.db_url
    db_user               = var.db_user
    db_password_parameter = aws_ssm_parameter.db_password.name
    db_ca_cert            = trimspace(file("${path.module}/${var.db_ca_cert_path}"))
  })

  # Replace the instance when the bootstrap script changes, so the running
  # stack always matches this configuration. The data lives in the hosted
  # database, so replacing the instance loses nothing.
  user_data_replace_on_change = true

  # Require IMDSv2 tokens for instance metadata, which the AWS CLI uses to get
  # the role's credentials.
  metadata_options {
    http_tokens = "required"
  }

  root_block_device {
    volume_size = 20
    volume_type = "gp3"
    encrypted   = true
  }

  tags = {
    Name = "wants-api"
  }
}

# A fixed public address, so the hosted database's IP allowlist can name it and
# survive the instance being replaced.
resource "aws_eip" "app" {
  domain = "vpc"

  tags = {
    Name = "wants-api"
  }
}

resource "aws_eip_association" "app" {
  instance_id   = aws_instance.app.id
  allocation_id = aws_eip.app.id
}
