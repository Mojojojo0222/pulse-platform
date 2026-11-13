# Pulse Platform API

Pulse is a small stateless Java service for demonstrating a production-style delivery path:
local development -> Docker -> Amazon ECR -> Amazon EKS -> Argo CD.

## What it does

Pulse is the evidence plane of the platform: it answers *is it healthy*
(Prometheus) and *did the deployment cause it* (Kubernetes) for registered
applications, from first GET to an honest snapshot.

- `GET /` — service overview and endpoint catalogue
- `GET /api/v1/pulse` — application status, version, instance hostname and timestamp
- `GET /api/v1/apps` — config-driven application registry (no Prometheus needed)
- `GET /api/v1/apps/{name}/metrics` — snapshot: target, requestRate, errorRatio, avgLatency, p95 (404 unknown app · 503 Prometheus down · 502 query failed)
- `GET /api/v1/deployments` — deployments in `pulse.kubernetes.namespace` (403/502/503)
- `GET /api/v1/deployments/{name}` — ClusterSnapshot: state, revision, pods, events, notes (404/503/504; partial evidence returns 200 with `complete: false`)
- `GET /health/live`, `GET /health/ready` — Kubernetes probes
- `GET /actuator/health`, `/actuator/prometheus` — Spring Boot health + metrics

The service is stateless: no database, cache or credentials of its own. It reads
Prometheus and the Kubernetes API server with **read-only** credentials
(`k8s/rbac.yaml`: get/list on deployments, pods, events — no watch).

## Requirements

- Java 21 and Maven 3.9+ for running/tests directly, OR
- Docker Desktop for container-based local testing

## Option A: Run with Maven

```bash
mvn test            # 77 tests
mvn spring-boot:run
```

Open `http://localhost:8080/api/v1/pulse`.

Configuration lives under `pulse:` in `src/main/resources/application.yml`
(registry + Prometheus URL + `pulse.kubernetes.*`). Environment overrides:
`PULSE_PROMETHEUS_URL`, `SERVER_PORT`, and for the observer `PULSE_KUBERNETES_ENABLED/AUTH_MODE/URL/NAMESPACE/TOKEN_FILE/CA_CERT_FILE/KUBECONFIG/DEADLINE_MS`. The Kubernetes observer is **disabled by default**; enabling
it requires explicit `AUTH_MODE=in-cluster|kubeconfig` (fail-fast, no fallback).

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
kubectl apply -f k8s/rbac.yaml
kubectl apply -f k8s/deployment.yaml
kubectl apply -f k8s/service.yaml
kubectl apply -f k8s/pdb.yaml
kubectl -n pulse-system rollout status deployment/pulse-api
kubectl -n pulse-system get pods,svc -o wide
```

Test through port-forward:

```bash
kubectl -n pulse-system port-forward svc/pulse-api 8080:80
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
application/
├── Dockerfile
├── docker-compose.yml
├── pom.xml
├── src/
│   ├── main/java/com/pulse/
│   │   ├── api/          PulseController, AppMetricsController, KubernetesController
│   │   ├── config/       PulseProperties (pulse.apps / prometheus / kubernetes)
│   │   ├── kubernetes/   Deadline, client interface, HTTP client, auth
│   │   ├── prometheus/   PrometheusClient
│   │   └── service/      AppMetricsService, ClusterObservationService
│   ├── main/resources/application.yml
│   └── test/java/com/pulse/   77 tests
└── k8s/
    ├── namespace.yaml     pulse-system
    ├── rbac.yaml          pulse-observer SA + Role (get/list) + RoleBinding
    ├── deployment.yaml
    ├── service.yaml
    └── pdb.yaml
```
