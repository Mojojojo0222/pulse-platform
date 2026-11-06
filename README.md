# Pulse Platform API

Pulse is a small stateless Java service for demonstrating a production-style delivery path:
local development -> Docker -> Amazon ECR -> Amazon EKS -> Argo CD.

## What it does

- `GET /` — service overview and endpoint catalogue
- `GET /api/v1/pulse` — application status, version, instance hostname and timestamp
- `GET /health/live` — Kubernetes liveness endpoint
- `GET /health/ready` — Kubernetes readiness endpoint
- `GET /actuator/health` — Spring Boot health endpoint

The first release is intentionally stateless. It has no database, cache, credentials or external service dependency.

## Requirements

- Java 21 and Maven 3.9+ for running/tests directly, OR
- Docker Desktop for container-based local testing

## Option A: Run with Maven

```bash
mvn test
mvn spring-boot:run
```

Open `http://localhost:8080/api/v1/pulse`.

## Option B: Build and run with Docker

```bash
docker build -t pulse-api:1.0.0 .
docker run --rm -p 8080:8080 pulse-api:1.0.0
```

Then test:

```bash
curl http://localhost:8080/
curl http://localhost:8080/api/v1/pulse
curl http://localhost:8080/health/live
curl http://localhost:8080/health/ready
curl http://localhost:8080/actuator/health
```

Or use Docker Compose:

```bash
docker compose up --build
```

Stop it with `Ctrl+C`, then `docker compose down`.

## Build and push to ECR

Create the repository once:

```bash
aws ecr create-repository --repository-name pulse-api --region ap-south-1
```

Set your account ID and login:

```bash
AWS_ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
aws ecr get-login-password --region ap-south-1 | docker login --username AWS --password-stdin ${AWS_ACCOUNT_ID}.dkr.ecr.ap-south-1.amazonaws.com
```

Build, tag and push:

```bash
docker build -t pulse-api:1.0.0 .
docker tag pulse-api:1.0.0 ${AWS_ACCOUNT_ID}.dkr.ecr.ap-south-1.amazonaws.com/pulse-api:1.0.0
docker push ${AWS_ACCOUNT_ID}.dkr.ecr.ap-south-1.amazonaws.com/pulse-api:1.0.0
```

## Deploy to EKS (initial imperative deployment)

Update the `image:` value in `k8s/deployment.yaml` with the ECR URI, then:

```bash
kubectl apply -f k8s/namespace.yaml
kubectl apply -f k8s/deployment.yaml
kubectl apply -f k8s/service.yaml
kubectl -n pulse rollout status deployment/pulse-api
kubectl -n pulse get pods,svc -o wide
```

Test through port-forward:

```bash
kubectl -n pulse port-forward svc/pulse-api 8080:80
```

In another terminal:

```bash
curl http://localhost:8080/api/v1/pulse
```

## Design notes

- Two replicas spread naturally across the two worker nodes when scheduler capacity permits; explicit topology spread/anti-affinity can be added as a follow-up.
- ClusterIP keeps the API internal initially. We will choose ingress/load-balancer exposure after the app is proven.
- Probes separate process liveness from readiness.
- Rolling update uses `maxUnavailable: 0` to avoid intentionally reducing available replicas during an update.
- Container runs as a non-root user, drops Linux capabilities and uses a read-only root filesystem.
- Requests/limits are starting lab values, not production sizing claims.

## Repository layout

```text
pulse-application/
├── Dockerfile
├── docker-compose.yml
├── pom.xml
├── src/
│   ├── main/java/com/pulse/
│   ├── main/resources/application.yml
│   └── test/java/com/pulse/
└── k8s/
    ├── namespace.yaml
    ├── deployment.yaml
    └── service.yaml
```
