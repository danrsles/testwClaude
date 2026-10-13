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

# --- Origin secret -----------------------------------------------------------

# CloudFront adds this value as the X-Origin-Secret header on every request it
# forwards to the API, and the API rejects requests without it. The security
# group already admits only CloudFront's address ranges, but those ranges are
# shared by every CloudFront customer; the header proves the request came
# through *this* distribution.
#
# Unlike the DB password this one is generated here and also sits in the
# CloudFront distribution's config, so it is in terraform.tfstate. To rotate
# it, taint random_password.origin_secret and apply.
resource "random_password" "origin_secret" {
  length  = 48
  special = false
}

resource "aws_ssm_parameter" "origin_secret" {
  name        = var.origin_secret_parameter
  description = "Header value CloudFront sends to prove a request came through the distribution"
  type        = "SecureString"
  value       = random_password.origin_secret.result

  tags = {
    Name = "wants-api"
  }
}

# --- Instance identity: may read those two parameters and nothing else -------

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
    actions = ["ssm:GetParameter"]
    resources = [
      aws_ssm_parameter.db_password.arn,
      aws_ssm_parameter.origin_secret.arn,
    ]
  }
}

resource "aws_iam_role_policy" "read_db_password" {
  name   = "read-db-password"
  role   = aws_iam_role.api.id
  policy = data.aws_iam_policy_document.read_db_password.json
}

# Lets the SSM agent (preinstalled on Amazon Linux 2023) register the instance
# and receive Run Command requests, which is how CI redeploys the API without
# SSH. It also enables Session Manager shells. The agent reaches SSM over the
# instance's outbound access; no inbound port is opened.
resource "aws_iam_role_policy_attachment" "api_ssm_core" {
  role       = aws_iam_role.api.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_instance_profile" "api" {
  name = "wants-api"
  role = aws_iam_role.api.name
}

# --- Network -----------------------------------------------------------------

# The description is out of date: the app port now admits only CloudFront (rule
# below). It is left alone because changing a security group's description
# makes AWS replace the group, which fails while an instance uses it.
resource "aws_security_group" "app" {
  name        = "wants-api"
  description = "Wants API: public app port, SSH from one address only"
  vpc_id      = data.aws_vpc.default.id

  tags = {
    Name = "wants-api"
  }
}

# Only CloudFront may call the API. AWS maintains this prefix list of the
# addresses CloudFront uses to reach origins, so the rule stays correct as they
# change. A prefix list counts against the security group's rule quota by its
# maximum size (about 55 entries of the default 60), so leave room when adding
# rules here.
data "aws_ec2_managed_prefix_list" "cloudfront" {
  name = "com.amazonaws.global.cloudfront.origin-facing"
}

resource "aws_vpc_security_group_ingress_rule" "app" {
  security_group_id = aws_security_group.app.id
  description       = "Wants API, from CloudFront only"
  prefix_list_id    = data.aws_ec2_managed_prefix_list.cloudfront.id
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
  # password and origin secret do not, only the names of their parameters.
  user_data = templatefile("${path.module}/user_data.sh.tftpl", {
    image                   = var.image
    app_port                = var.app_port
    region                  = var.region
    db_url                  = var.db_url
    db_user                 = var.db_user
    db_password_parameter   = aws_ssm_parameter.db_password.name
    origin_secret_parameter = aws_ssm_parameter.origin_secret.name
    db_ca_cert              = trimspace(file("${path.module}/${var.db_ca_cert_path}"))
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
