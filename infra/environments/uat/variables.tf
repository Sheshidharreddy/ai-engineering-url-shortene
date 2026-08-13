variable "aws_region" { type = string }
variable "base_domain" { type = string }
variable "ecr_repository_url" { type = string }
variable "image_tag" { type = string }
variable "alarm_email" {
  type    = string
  default = ""
}
