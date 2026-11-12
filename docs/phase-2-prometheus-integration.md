# Phase 2 — Pulse → Prometheus integration

## Goal

Pulse queries Prometheus for real application metrics: target availability, request
rate, error ratio, average latency, p95 (when histogram data exists). Handles
unreachable Prometheus, query failures, and missing data without crashing.

## Architecture

```text
                     GET /api/v1/apps/todoapp/metrics
  client ──────────────────────────────► AppMetricsController
                                              │ registry lookup (404 if unknown)
                                              ▼
                                        AppMetricsService
                                              │ builds PromQL (config-driven job label)
                                              ▼
                                        PrometheusClient ──── HTTP GET /api/v1/query ────► Prometheus
                                              │ instant query, timeouts, error taxonomy
                                              ▼
                                        Snapshot record (nulls + notes for missing data)
```

## What changed and why

| File | Change | Why |
|---|---|---|
| `config/PulseProperties.java` | `@ConfigurationProperties("pulse")`: prometheus (base-url, timeouts, query-window) + `apps` map | Configuration-driven application registry — adding an app = YAML line, no code |
| `prometheus/PrometheusClient.java` | Typed instant-query client over Spring `RestClient`; 2s connect / 5s read timeouts; `PrometheusException(UNAVAILABLE, QUERY_FAILED)` | One place that knows the Prometheus HTTP API; error taxonomy drives HTTP status mapping |
| `prometheus/PrometheusException.java` | Two-kind exception | Distinguish "dependency down" (503) from "query bad" (502) — different operator actions |
| `service/AppMetricsService.java` | Builds 5 PromQL queries per snapshot; NaN/Inf → null; honest `notes` list | Deterministic, testable snapshot assembly; never invents numbers |
| `api/AppMetricsController.java` | `GET /api/v1/apps`, `GET /api/v1/apps/{name}/metrics`; 404/502/503 mapping | Thin HTTP layer; registry check before any outbound call |
| `application.yml` | `pulse.*` block, env overrides `PULSE_PROMETHEUS_URL` etc. | Local default localhost:9090; cluster wiring later = env var only |
| `api/PulseController.java` | Endpoint catalogue lists the two new routes | Keep `/` self-describing |

Rejected: WebFlux/WebClient (sync is right for a request-scoped query), generic
"run any PromQL" API (injection surface; named queries only), new dependencies
(`RestClient` ships with spring-web).

## Endpoints

```bash
GET /api/v1/apps
# {"apps":[{"job":"todoapp","name":"todoapp"}]}

GET /api/v1/apps/todoapp/metrics
# {"application":"todoapp","observedAt":"...","target":"UP",
#  "requestRatePerSecond":0.17896683498906898,"errorRatio":0.0,
#  "avgLatencySeconds":0.0609,"p95LatencySeconds":null,
#  "queryWindow":"5m","prometheusUrl":"http://localhost:9090",
#  "notes":["p95 unavailable: application does not expose histogram buckets"]}

# Failure contract:
# 404 {"error":"unknown_application","application":"ghost"}
# 503 {"error":"prometheus_unavailable","detail":"...Connection refused..."}
# 502 {"error":"prometheus_query_failed","detail":"..."}
```

## Semantics decisions

- `target`: `up >= 0.5` → UP; `0` → DOWN; **no series ever** → UNKNOWN (+ note)
- `errorRatio = null` only when there is no traffic; **absent 5xx series with traffic = 0.0**
  (empty `sum()` is "zero errors", not "unknown")
- `p95 = null` when the app exposes no histogram buckets (todoapp doesn't) — reported
  in `notes`, never faked
- NaN/±Inf (div-by-zero in PromQL) → null
- Queries use `rate(...[5m])` — window configurable via `PULSE_PROMETHEUS_QUERY_WINDOW`

## Verification executed (2026-11-13)

| Test | Result |
|---|---|
| `mvn test` | **18/18 pass** (client 6, service 5, controllers 7) |
| Live snapshot vs direct Prometheus query | **exact match** `0.17896683498906898` |
| `up{job="todoapp"}` | `1` → target UP |
| Unknown app | 404 `unknown_application` |
| Pulse pointed at dead Prometheus (port 9) | 503 `prometheus_unavailable`; `/api/v1/apps` + `/health/live` still 200 |
| `docker stop prometheus` → request | 503; `docker start` → next request 200, no Pulse restart needed |
| Empty 5xx series with traffic | `errorRatio: 0.0` (regression test added) |

## Run it

```bash
cd E:/Platform-Lab/application
mvn test                                  # full suite
mvn -DskipTests package
PULSE_PROMETHEUS_URL=http://localhost:9090 SERVER_PORT=8081 java -jar target/pulse-api.jar
curl -s localhost:8081/api/v1/apps/todoapp/metrics
```

Stack assumed running: `docker compose -f docker-compose.observability.yml up -d`
in `E:/tmp/todoapp-repo` (Phase 1).

## Interview talking points

1. **Error taxonomy** — 503 vs 502 vs 404 tell the operator *whose* problem it is.
2. **Empty ≠ zero ≠ error** — three states (UP/DOWN/UNKNOWN, ratio null vs 0.0) is the
   difference between a platform that lies and one that reports evidence.
3. **No arbitrary PromQL endpoint** — fixed queries from a config registry prevent
   injection and keep queries versionable.
4. **Timeouts on every outbound call** — a slow Prometheus must never hang Pulse's API
   thread beyond 5s read timeout.
5. **Stateless recovery** — no connection pool/session; if Prometheus blips, the *next*
   request succeeds. Verified with container stop/start.
