# CONTEXT — Pulse project session-resume file

> **Purpose:** give the assistant a complete cold-start context in one read.
> Paste this file (or point to it) at the start of any session instead of
> re-exploring the repos. Companion docs: `ARCHITECTURE.md` (as-built design),
> `phase-2-prometheus-integration.md` (latest phase detail).

---

## 1. What this project is

**Pulse** — a deployment-aware application reliability platform
(Spring Boot 21 modular monolith, repo `pulse-platform`).
Goal: answer *"is the app healthy, did the deployment cause it, roll back or not?"*
with **evidence**, not vibes.

**User's meta-goal:** interview/portfolio showcase — they want deep
understanding + teaching notes per capability, a demo-able system, and
**a LinkedIn/Hasnode article on the weekend** covering the complete
architecture (they will provide detailed notes for that later — watch for it).

**Three-plane strategy (locked):** Shipmate = change plane, Pulse = evidence
plane, Notes_Buddy = workload #2. Loose coupling only at the Kubernetes
boundary. Never merge repos/DBs.

## 2. Repos, paths, environment

| Thing | Value |
|---|---|
| Pulse repo | `E:\Platform-Lab\application` (nested inside infra repo, gitignored there; own git remote-less repo) |
| Infra repo | `E:\Platform-Lab` (`platform-up.sh` recreates EKS; account `047090857529`) |
| todoapp | `E:\tmp\todoapp-repo` — **git remote contains a token: NEVER touch/push/reset it** |
| notes_buddy | `E:\Notes_Buddy` (future workload #2) |
| Platform | win32, bash shell, ~0.8 GB free Windows RAM — keep total workload ≤3–4 GB |
| Docker | Desktop running, engine 29.8.1, data on `E:\Docker\wsl` (71 GB; C: freed), WSL memory 2 GB |
| EKS | **deleted** — recreate on demand with `platform-up.sh` (~$5.50/day), tear down after sessions |
| AWS | account `047090857529` only |
| Phase 1 stack | mysql 3306 (todo/todo, db `todo`), todoapp 8080, prometheus 9090 — `docker compose -f docker-compose.observability.yml up -d` in `E:/tmp/todoapp-repo` |
| Pulse | jar `target/pulse-api.jar`, run on **8081** with `PULSE_PROMETHEUS_URL=http://localhost:9090` (left running at session end via `/tmp/pulse-8081.pid`) |

## 3. How to work with this user (workflow — non-negotiable)

1. **One capability at a time.** Explain problem → concepts → design →
   trade-offs → known failure modes **first, in detail**; then smallest scope;
   then implement; explain each file; give commands for normal/failure/recovery
   testing; test live; document; **only mark complete after user acceptance**.
2. Heavy **teaching + interview-notes** emphasis every phase.
3. User runs acceptance commands themselves (local dev ops by assistant is OK;
   **no terraform/k8s/helm/argocd apply** by assistant).
4. **No commits unless explicitly approved** (Phase 2 batch was approved — see §7).
5. Prefer questions with recommended options when a decision costs money/scope.
6. Answer style: structured, concrete values (actual numbers/URLs/status codes),
   tables for comparisons.

## 4. Phase status

| Phase | Scope | Status |
|---|---|---|
| 0 | Discovery (repos, hybrid strategy, decisions) | ✅ |
| 1 | todoapp instrumented → Prometheus (`application` tag, exposure, compose file) | ✅ |
| 2 | Pulse → Prometheus queries (registry, client, snapshot, 2 endpoints) | ✅ **accepted 2026-11-13** — 18/18 tests, live-verified, docs written |
| 3 | Kubernetes observer (read-only RBAC, Deployment/Pod/Event reconciliation) | ⏭ **next — APPROVED: mock-first, EKS later** — code + unit tests against mocked K8s API now (zero cost); one live session on a fresh EKS later |
| 4+ | Shipmate change events → correlation (5) → RDS (6) → rollback → Notes_Buddy onboard → UI → E2E → wrap | pending |

**Phase 3 kickoff plan (next session):** start with the full design explanation
(per workflow) — candidates to evaluate: fabric8 kubernetes-client vs raw
`RestClient` against the API server; watch/informer vs periodic poll (note:
`KUBECONFIG`/in-cluster config differ mock vs live); RBAC manifest scoped to
get/list/watch on deployments/pods/events in one namespace; Snapshot-style
honest states for API-unreachable / RBAC-denied / not-found. Mock strategy:
wrap the K8s call behind a thin interface so tests inject fake responses.
Live verification deferred until cluster session.

## 5. As-built summary (details → `ARCHITECTURE.md`)

- **Endpoints:** `GET /`, `/actuator/health|ready|live|prometheus`,
  `/api/v1/pulse`, `/api/v1/apps`, `/api/v1/apps/{name}/metrics`
- **Modules:** `api/` (2 controllers), `config/PulseProperties`
  (`pulse.apps.*` registry + `pulse.prometheus.*`), `service/AppMetricsService`
  (5 PromQL queries → Snapshot), `prometheus/PrometheusClient` + `PrometheusException`
- **Failure contract:** 404 unknown app · 503 UNAVAILABLE (Prometheus down)
  · 502 QUERY_FAILED · core endpoints keep answering when Prometheus is down
- **Snapshot semantics:** UP/DOWN/UNKNOWN; `errorRatio=null` only with no
  traffic, absent 5xx series = 0.0; p95 null + note (no histogram buckets in
  todoapp); NaN/Inf → null; 5m window (`PULSE_PROMETHEUS_QUERY_WINDOW`)
- **Config:** `application.yml` `pulse:` block, env overrides
  `PULSE_PROMETHEUS_URL`, `SERVER_PORT`, etc.
- **Tests:** 18 total — client 6 (mock HTTP + real connection-refused),
  service 5 (mock client), controllers 7 (MockMvc)

## 6. Verification & running state (as of last session)

- `mvn test` → **18/18 BUILD SUCCESS**
- Live cross-check: Pulse snapshot rate == direct Prometheus query
  (`0.17896683498906898` both sides); `up{job="todoapp"}`=1
- Failure/recovery proven: dead Prometheus → 503 (apps list + liveness 200);
  `docker stop prometheus` → 503 → `start` → next request 200, no Pulse restart
- At session end: stack (8080/9090/3306) + Pulse **:8081** were left running;
  stop with `kill $(cat /tmp/pulse-8081.pid)` and compose `down`
- Stop Pulse: pid file `/tmp/pulse-8081.pid`; logs `/tmp/pulse-8081.log`

## 7. Git state

- Phase 2 batch **approved and committed** (commit message:
  `feat: pulse-prometheus integration with registry-driven metrics queries`)
- `.gitignore` rewritten: `target/` untracked (root cause of the earlier
  stale-jar bug in todoapp — same issue here), plus IDE/log noise;
  `terraform.tfstate*` kept as safety net
- Never commit: todoapp remote token (different repo), secrets, `target/`

## 8. Full decisions log

1. Namespace rename → `pulse-system`: **deferred to Phase 3**
2. Pulse DB: **RDS Postgres in Phase 6** (~$12/mo), local compose until then
3. todoapp git remote token: **never touch**
4. UI: minimal server-rendered only (no SPA)
5. Combination strategy: loose coupling at K8s boundary (see §1)
6. Hybrid execution: local dev daily + **EKS on demand ~$5.50/day**
7. Phase 3 verification: **mock-first, EKS later** (approved 2026-11-13)
8. Phase 2 tech: sync `RestClient` (no WebFlux), no new dependencies,
   named queries only (no arbitrary PromQL endpoint)
9. Docker data migrated to `E:\Docker\wsl`; WSL memory 2 GB (overrides
   Docker Desktop's 2048 MiB setting)

## 9. Gotchas (cost us real debugging — don't re-learn)

- **PromQL `{}` in URLs:** RestClient/`UriComponentsBuilder` treat `{...}` as
  URI templates → always pre-encode query values with `URLEncoder` into a
  concrete `URI`
- **`target/` was git-tracked** in both repos → stale-jar bug (500s from old
  `${todo.title}` template). Fixed in todoapp earlier; untracked here now.
- Two constructors on a `@Component` → add `@Autowired` on the config ctor
- `MockRestServiceServer.expect(RequestMatcher)` returns `ResponseActions`
  (use `.andRespond(...)`)
- PromQL `sum()` over no matching series returns **empty, not 0** — Pulse must
  translate "empty + traffic" → `errorRatio 0.0` (regression test exists)
- Windows RAM tight: don't run extra containers/instances casually; ports
  8080/8081/9090/3306 are the reserved local set
- C: disk was full once → Docker data on E:; check `df -h` before big builds

## 10. Pending / watch for

1. **Weekend: LinkedIn/Hasnode article** — full architecture write-up to
   showcase + demo the system for interviewers; user provides detailed notes
   first. Feed it from `ARCHITECTURE.md` + per-phase docs.
2. Phase 3 implementation (mock-first) — start with design explanation
3. EKS on-demand session for Phase 3 live verification (costs $5.50/day —
   confirm before running `platform-up.sh`)
4. Optional later: enable histogram buckets in todoapp (makes p95 real)
5. todoapp repo also has `target/` tracked — same `git rm -r --cached` fix
   when user approves (it was flagged, not yet done there)

## 11. Interview-notes index

- Phase 1: metrics exposition, Prometheus scrape model, `application` tag,
  stale-artifact debugging story
- Phase 2: error taxonomy (503/502/404), empty≠zero≠error semantics, timeout
  budgets, no-arbitrary-PromQL security stance, stateless recovery demo —
  full version in `docs/phase-2-prometheus-integration.md`
- Cross-cutting: three-plane architecture, loose coupling at K8s boundary,
  evidence-vs-change plane separation (see `ARCHITECTURE.md` §1)
