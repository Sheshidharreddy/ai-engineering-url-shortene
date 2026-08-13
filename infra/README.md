# AWS ECS Infrastructure

Terraform provisions isolated `dev`, `uat`, and `prod` environments on AWS ECS Fargate. The default DNS convention is:

| Environment | Suggested DNS |
| --- | --- |
| Development | `dev.go.<owned-domain>` |
| UAT | `uat.go.<owned-domain>` |
| Production | `go.<owned-domain>` |

`<owned-domain>` must be an existing public Route53 hosted zone controlled by the deployment account. The repository does not invent or register a domain.

## Provisioned Platform

- Multi-AZ VPC with public ALB subnets, private Fargate subnets, and isolated data subnets.
- Two application tasks in dev/UAT and three in production, with zone rebalancing and CPU/request autoscaling.
- HTTPS-only public ALB, ACM certificate, Route53 alias, AWS WAF managed protections, and rate limits.
- RDS PostgreSQL 17 with TLS, encryption, managed password, automatic backups, Performance Insights, and Multi-AZ in UAT/production.
- ElastiCache Redis with primary/replicas, automatic failover, TLS, at-rest encryption, authentication, and snapshots.
- Secrets Manager for Redis and management API credentials; RDS manages its master credential in Secrets Manager.
- CloudWatch structured logs, WAF logs, VPC rejected-flow logs, dashboard, SNS alarms, X-Ray traces, and Amazon Managed Service for Prometheus metrics.
- Rolling ECS deployments with 100% minimum healthy capacity, circuit-breaker rollback, alarm rollback, and emergency workflow rollback.
- ALB readiness checks, 45-second target deregistration, Spring readiness draining, and a 60-second ECS termination window.
- Explicit PostgreSQL pool size per replica with a plan-time application connection budget at maximum autoscaling capacity.

## Database Connection Budgets

| Environment | Pool per replica | Maximum replicas | Application budget | Reserved headroom |
| --- | ---: | ---: | ---: | ---: |
| Development | 5 | 4 | 24 | 4 |
| UAT | 8 | 6 | 56 | 8 |
| Production | 12 | 12 | 160 | 16 |

Terraform rejects a task-definition plan when `db_pool_size * maximum_count` exceeds `db_application_connection_budget`. The budget covers application pools only; RDS monitoring, administration, migrations, and failover headroom must remain outside it.

## Account Prerequisites

1. An AWS account and deployment region with capacity for the selected RDS/Redis instance families.
2. A public Route53 hosted zone for the owned domain.
3. Terraform `>= 1.10`, AWS CLI, Docker, Java 21, and Maven 3.9+.
4. AWS credentials authorized to create the bootstrap resources.

Use separate AWS accounts for production and non-production when possible. Run `infra/bootstrap` once in each account. If environments share an account, run it once and reuse its ECR repository, state bucket, and GitHub deploy role.

## Bootstrap

The bootstrap creates the encrypted/versioned Terraform state bucket, immutable ECR repository, GitHub OIDC provider, and deploy role.

```bash
cd infra/bootstrap
terraform init
terraform plan \
  -var='github_repository=OWNER/REPOSITORY' \
  -var='state_bucket_name=GLOBALLY-UNIQUE-STATE-BUCKET'
terraform apply \
  -var='github_repository=OWNER/REPOSITORY' \
  -var='state_bucket_name=GLOBALLY-UNIQUE-STATE-BUCKET'
```

Bootstrap state is sensitive operational state. Keep it on an encrypted administrative workstation or migrate it to an existing organization-managed remote backend; never commit it.

Record the outputs:

```bash
terraform output
```

The deploy role intentionally has broad infrastructure mutation privileges because Terraform creates IAM, networking, database, cache, and compute resources. Its trust policy is restricted to this GitHub repository and its protected environments. Apply an organization permission boundary and deploy into dedicated environment accounts to further constrain blast radius.

## Initialize an Environment

Copy only examples; populated `.tfvars` and backend files remain local and ignored.

```bash
cd infra/environments/dev
cp backend.hcl.example backend.hcl
cp terraform.tfvars.example terraform.tfvars
terraform init -backend-config=backend.hcl
terraform plan
```

Review the plan, expected cost, Route53 zone, instance sizes, backup retention, and deletion protection before `terraform apply`. Repeat for `uat` and `prod`; each environment has an isolated state key and VPC.

## GitHub Environments

Create GitHub environments named `dev`, `uat`, and `prod`. Configure these environment variables:

| Variable | Purpose |
| --- | --- |
| `AWS_REGION` | Deployment region, such as `us-east-2`. |
| `AWS_DEPLOY_ROLE_ARN` | Bootstrap `github_deploy_role_arn` output. |
| `TF_STATE_BUCKET` | Bootstrap `state_bucket_name` output. |
| `BASE_DOMAIN` | Existing Route53 hosted-zone name. |
| `ALARM_EMAIL` | Email for alarm notifications; subscription confirmation is required. |

Require reviewers and restrict deployment branches to `main` for the `uat` and `prod` GitHub environments. The deployment workflow is manual, tests again, pushes an immutable Git SHA image, blocks critical/high ECR findings, signs the image through Sigstore, applies Terraform, waits for ECS stability, and executes create/metadata/redirect/delete smoke tests.

## Cost and Safety

Production creates three NAT gateways, Multi-AZ RDS, three Redis nodes, Fargate tasks, WAF, AMP, CloudWatch logs, backups, and data transfer charges. Dev uses one NAT gateway and smaller instances but still maintains two application and Redis nodes. Obtain an AWS Pricing Calculator estimate and budget approval before applying.

Terraform validation does not prove AWS quotas, account policy compatibility, DNS ownership, runtime health, backup restorability, or remote workflow success. Those checks are listed in `docs/OPERATIONS.md`.
