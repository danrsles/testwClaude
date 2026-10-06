data "aws_vpc" "default" {
  default = true
}

data "aws_subnets" "default" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
}

# Amazon Linux 2023, x86_64 - matches the linux/amd64 image being run.
data "aws_ami" "al2023" {
  most_recent = true
  owners      = ["amazon"]

  filter {
    name   = "name"
    values = ["al2023-ami-2023.*-kernel-6.1-x86_64"]
  }
}

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

# MySQL is deliberately absent: it listens only on the container network,
# never on the instance, so nothing outside can reach port 3306.

resource "aws_vpc_security_group_egress_rule" "all" {
  security_group_id = aws_security_group.app.id
  description       = "Outbound for Docker Hub pulls and package updates"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

resource "aws_instance" "app" {
  ami                         = data.aws_ami.al2023.id
  instance_type               = var.instance_type
  subnet_id                   = data.aws_subnets.default.ids[0]
  vpc_security_group_ids      = [aws_security_group.app.id]
  key_name                    = var.key_name
  associate_public_ip_address = true

  user_data = templatefile("${path.module}/user_data.sh.tftpl", {
    image               = var.image
    app_port            = var.app_port
    mysql_database      = var.mysql_database
    mysql_user          = var.mysql_user
    mysql_password      = var.mysql_password
    mysql_root_password = var.mysql_root_password
  })

  # Replace the instance when the bootstrap script changes, so the running
  # stack always matches this configuration.
  user_data_replace_on_change = true

  root_block_device {
    volume_size = 20
    volume_type = "gp3"
    encrypted   = true
  }

  tags = {
    Name = "wants-api"
  }
}
