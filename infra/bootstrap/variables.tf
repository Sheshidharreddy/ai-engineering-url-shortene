variable "project_name" {
  type    = string
  default = "sheshidhar-url-shortener"
}

variable "aws_region" {
  description = "Primary deployment region."
  type        = string
  default     = "us-east-2"
}

variable "github_repository" {
  description = "GitHub repository in owner/name form."
  type        = string
}

variable "state_bucket_name" {
  description = "Globally unique S3 bucket name for Terraform state."
  type        = string
}
