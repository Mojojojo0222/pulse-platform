# Pulse — Architecture (as-built)

> Deployment-aware application reliability platform.
> This document describes what **exists today** (Phases 0–2) and where it is going.

## 1. Vision

Pulse answers, with evidence: *is this application healthy, did a deployment cause the
change, and should we roll back?* It is deliberately decoupled from the change plane:

- **Shipmate** = change plane (build/deploy/gitops) — never merged with Pulse
- **Pulse** = evidence plane (observe/analyze/decide) — this repo
- **Notes_Buddy** = onboarded workload #2 (proves the registry generalizes)

Loose coupling happens at the **Kubernetes boundary** (Pulse reads cluster state
read-only); repos, databases, and release processes are never shared.

## 2. System context (as-built)

```text
┌────────────────────────── local dev stack (Phase 1) ──────────────────────────┐
│  mysql:3306 ◄── todoapp:8080 (instrumented: /actuator/prometheus)            │
│                       ▲ scrape                                                │
│               prometheus:9090 ◄───────────────┐                               │
└───────────────────────────────────────────────┼───────────────────────────────┘
                                                │ instant queries (Phase 2)
┌─────────────────────── Pulse :8081 ───────────┴───────────────────────────────┐
│  api/        PulseController          AppMetricsController                    │
│              GET /  GET /api/v1/pulse   GET /api/v1/apps                      │
│                                        GET /api/v1/apps/{name}/metrics        │
│  config/     PulseProperties  (@ConfigurationProperties "pulse")             │
│              pulse.apps.<name>.job   pulse.prometheus.{base-url,timeouts,…}  │
│  service/    AppMetricsService  (5 PromQL queries → Snapshot; honest nulls)   │
│  prometheus/ PrometheusClient  (RestClient, 2s/5s timeouts, error taxonomy)   │
│              PrometheusException { UNAVAILABLE→503 | QUERY_FAILED→502 }       │
│  actuator/   health, readiness, liveness, prometheus (own metrics)            │
└───────────────────────────────────────────────────────────────────────────────┘
```

## 3. Module layout

```text
E:\Platform-Lab\application            (repo: pulse-platform, Maven, Spring Boot 3.5/Java 21)
├── docs/
│   ├── ARCHITECTURE.md                ← this file
│   ├── CONTEXT.md                     ← session-resume file (read this first)
│   ├── REQUEST_TRACE.md               ← request → snapshot, layer by layer
│   ├── phase-2-prometheus-integration.md
│   └── phase-3-kubernetes-observer.md
├── src/main/java/com/pulse/
│   ├── api/        PulseController, AppMetricsController, KubernetesController
│   ├── config/     PulseProperties  (@ConfigurationProperties "pulse")
│   ├── kubernetes/ Deadline, KubernetesClient, HttpKubernetesClient,
│   │               KubernetesAuth, KubernetesException, KubernetesConfigurationException
│   ├── prometheus/ PrometheusClient, PrometheusException
│   └── service/    AppMetricsService, ClusterObservationService, PulseService
├── src/main/resources/application.yml (server.port, management, pulse.*)
└── src/test/java/com/pulse/           77 tests: phase-2 = 18, phase-3 = 59
```

## 4. HTTP surface

| Endpoint | Purpose | Failure contract |
|---|---|---|
| `GET /` | Self-describing catalogue | — |
| `GET /actuator/health` (+ ready/live) | Liveness/readiness probes | 503 when DOWN |
| `GET /api/v1/pulse` | Deployment identity (Phase 4+) | — |
| `GET /api/v1/apps` | Config-driven app registry | — (no Prometheus needed) |
| `GET /api/v1/apps/{name}/metrics` | Snapshot: target, requestRate, errorRatio, avgLatency, p95 | 404 unknown app · 503 Prometheus down · 502 query failed |
| `GET /api/v1/deployments` | Deployments in `pulse.kubernetes.namespace` (list) | 403 forbidden · 502 upstream · 503 unavailable/disabled/throttled |
| `GET /api/v1/deployments/{name}` | ClusterSnapshot: state, revision, pods, events, notes | +404 unknown/namespace · 504 deadline · 200 `complete:false` on partial evidence |

Snapshot semantics: `errorRatio=null` only when no traffic; absent 5xx series with
traffic = `0.0`; `p95=null` + note when the app exposes no histogram buckets;
NaN/±Inf → null; `target` = UP / DOWN / UNKNOWN (never scraped).
ClusterSnapshot semantics: `READY/DEGRADED/PROGRESSING/SUSPENDED`; pod crash
evidence overrides optimistic status; events-only RBAC denial stays 200 with
`complete=false` + note — partial evidence is never rendered as full health.

## 5. Design decisions (locked)

| Decision | Rationale |
|---|---|
| Config-driven app registry (`pulse.apps`) | Onboarding = YAML lines, no code |
| Fixed named queries, no "run any PromQL" API | Injection/DoS surface; queries reviewable |
| Sync Spring `RestClient` | Request-scoped query; no reactive complexity |
| Error taxonomy 503/502/404 | Tells the operator *whose* problem it is |
| Timeouts 2s connect / 5s read | Slow Prometheus must never hang Pulse |
| Namespace `pulse-system` deferred to Phase 3 | Avoid churn before cluster work |
| Phase 3: raw `RestClient`, pull-per-request, explicit auth mode, `enabled=false` default | No new deps; stateless/fresh; fail-fast credentials; Pulse boots with EKS deleted |
| RDS for Pulse Postgres in Phase 6 | ~$12/mo — not yet needed locally |
| EKS on demand (~$5.50/day) via `platform-up.sh` | Hybrid: local dev daily, cluster for verification sessions |

## 6. Roadmap

| Phase | Scope | Status |
|---|---|---|
| 0 | Discovery / environment decisions | ✅ done |
| 1 | Instrument todoapp → Prometheus | ✅ done (stack: mysql+todo+prom, ports 8080/9090/3306) |
| 2 | Pulse queries Prometheus | ✅ done (18/18 tests, live-verified) |
| 3 | Kubernetes observer — read-only RBAC, Deployment/Pod/Event reconciliation | 🟡 **M1–M3 done 2026-11-14** (77/77 tests, live failure smokes) — M4 RBAC manifest + `pulse-system` rename pending (user applies), M6 live EKS pending |
| 4 | Shipmate change events (correlation source) | pending |
| 5 | Deployment↔health correlation + honest gaps | pending |
| 6 | RDS Postgres for Pulse | pending (~$12/mo) |
| 7–11 | Rollback decision, Notes_Buddy onboard, UI polish, E2E, wrap | pending |

## 7. Verification state

**Phase 3 (2026-11-14):** `mvn test` → **77/77 pass** (59 new). Live smokes:
disabled observer → 503 `kubernetes_disabled` while `/api/v1/apps` stays 200;
enabled + dead endpoint → 503 `kubernetes_unavailable` in 263 ms; enabled
without credentials → exit 1 naming mode + missing file.

**Phase 2 (2026-11-13):** `mvn test` → 18/18 pass. Live snapshot vs direct
Prometheus query → exact match (`0.17896683498906898`); failure modes proven:
dead Prometheus → 503 (core endpoints stay 200); unknown app → 404;
`docker stop prometheus` → 503 → `start` → 200 without Pulse restart.

## 8. Operational quick-reference

```bash
# tests
mvn test

# run Pulse (stack must be up)
PULSE_PROMETHEUS_URL=http://localhost:9090 SERVER_PORT=8081 java -jar target/pulse-api.jar

# Phase 1 stack (from E:/tmp/todoapp-repo)
docker compose -f docker-compose.observability.yml up -d

# observer smoke (Phase 3)
curl -s localhost:8081/api/v1/deployments          # 503 kubernetes_disabled (default)
PULSE_KUBERNETES_ENABLED=true PULSE_KUBERNETES_AUTH_MODE=kubeconfig \
PULSE_KUBECONFIG=~/.kube/config java -jar target/pulse-api.jar   # needs real cluster

# stop everything
kill $(cat /tmp/pulse-8081.pid)
docker compose -f docker-compose.observability.yml down   # in E:/tmp/todoapp-repo
```

See `CONTEXT.md` for environment details, the full decisions log, and gotchas.
