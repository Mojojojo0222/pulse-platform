# CONTEXT — Pulse project session-resume file

> **Purpose:** give the assistant a complete cold-start context in one read.
> Paste this file (or point to it) at the start of any session instead of
> re-exploring the repos. Companion docs: `ARCHITECTURE.md` (as-built design),
> `REQUEST_TRACE.md` (request → snapshot layer by layer),
> `phase-2-prometheus-integration.md`, `phase-3-kubernetes-observer.md`.

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
| 3 | Kubernetes observer (read-only RBAC, Deployment/Pod/Event reconciliation) | 🟡 **M1–M3 + docs done 2026-11-14** (77/77 tests, live failure smokes, phase doc + REQUEST_TRACE) — **M4** RBAC manifest + full-repo namespace-rename survey pending (files only, user applies); **M6** live EKS session pending (~$5.50/day, confirm first) |
| 4+ | Shipmate change events → correlation (5) → RDS (6) → rollback → Notes_Buddy onboard → UI → E2E → wrap | pending |

**Phase 3 current state (2026-11-14):** mock-first complete — config + HTTP
client (pagination/deadline/auth), reconciliation service, 2 endpoints with the
locked status map, 59 new tests, docs. **Next session = M4:** (1) search the
entire infra repo for every `pulse` namespace occurrence → classify active /
historical / unrelated → user approves → edit; (2) `k8s/rbac.yaml` (SA
`pulse-observer` + Role get/list on deployments/pods/events + RoleBinding) and
`deployment.yaml` `serviceAccountName`. Then M6 = one EKS session for live
verification (real rollout, CrashLoopBackOff, Warning events, RBAC denial).

## 5. As-built summary (details → `ARCHITECTURE.md`, traces → `REQUEST_TRACE.md`)

- **Endpoints:** `GET /`, `/actuator/health|ready|live|prometheus`,
  `/api/v1/pulse`, `/api/v1/apps`, `/api/v1/apps/{name}/metrics`,
  `GET /api/v1/deployments`, `GET /api/v1/deployments/{name}`
- **Modules:** `api/` (3 controllers), `config/PulseProperties`
  (`pulse.apps.*` + `pulse.prometheus.*` + `pulse.kubernetes.*`),
  `service/AppMetricsService` (5 PromQL → Snapshot),
  `service/ClusterObservationService` (Deadline + state machine → ClusterSnapshot),
  `prometheus/` client, `kubernetes/` (interface + HTTP client + auth + 9-kind exception + Deadline)
- **Prometheus failure contract:** 404 unknown app · 503 `prometheus_unavailable`
  · 502 `prometheus_query_failed` · core endpoints answer when Prometheus is down
- **Kubernetes failure contract:** 404 `unknown_deployment`/`namespace_not_found`
  · 403 `kubernetes_forbidden` · 502 `kubernetes_unauthenticated`/`api_failed`
  · 503 `kubernetes_unavailable`/`throttled`/`disabled` · 504 `kubernetes_deadline_exceeded`
  · events-only failure → **200** `complete:false` + note (partial ≠ fake health)
- **ClusterSnapshot states:** `READY` (zero notes) / `DEGRADED` (replicas or
  crash-like pod — pod evidence overrides status) / `PROGRESSING`
  (generation lag or partial rollout) / `SUSPENDED` (desired 0)
- **Prometheus Snapshot semantics:** UP/DOWN/UNKNOWN; `errorRatio=null` only
  with no traffic, absent 5xx series = 0.0; p95 null + note (no histogram
  buckets in todoapp); NaN/Inf → null; 5m window (`PULSE_PROMETHEUS_QUERY_WINDOW`)
- **Config:** `application.yml` `pulse:` block; env overrides
  `PULSE_PROMETHEUS_URL`, `PULSE_KUBERNETES_ENABLED/AUTH_MODE/URL/NAMESPACE/
  TOKEN_FILE/CA_CERT_FILE/KUBECONFIG/DEADLINE_MS/PAGE_SIZE`, `SERVER_PORT`
- **Tests:** 77 total — phase 2: client 6, service 5, controllers 7;
  phase 3: client 20, auth 10, observation 12, controller 14, deadline 3

## 6. Verification & running state (as of 2026-11-14)

- `mvn test` → **77/77 BUILD SUCCESS** (Phase 3: +59 tests, all acceptance rows green)
- Live smokes (2026-11-14): default boot → `/api/v1/deployments` **503
  kubernetes_disabled** while `/api/v1/apps` **200**; enabled + dead endpoint →
  **503 kubernetes_unavailable in 263 ms** (no token leaked in body); enabled
  without SA files → **exit 1** naming auth-mode + property + path
- Phase 2 (2026-11-13): snapshot rate == direct Prometheus query
  (`0.17896683498906898`); dead Prometheus → 503 → recovery without restart
- Prior session left stack (8080/9090/3306) + Pulse :8081 running —
  stop with `kill $(cat /tmp/pulse-8081.pid)` and compose `down`
  (logs `/tmp/pulse-8081.log`; ad-hoc smokes used 8082/8083 + `/tmp/pulse-m3*.log`)

## 7. Git state

- **Approved for commit 2026-11-14 (final batch of the day):** Phase 3
  (kubernetes observer M1–M3) + M4 namespace rename (`pulse`→`pulse-system`,
  tiers A+B) + `k8s/rbac.yaml` + README refresh → one `feat:` commit, pushed
- `.gitignore` keeps `docs/` ignored **by user decision — local-only docs**;
  already-tracked `ARCHITECTURE.md`/`CONTEXT.md` still commit when modified,
  but NEW docs (phase-3, REQUEST_TRACE) stay local
- `.gitignore` also covers `target/` + IDE/log noise; `terraform.tfstate*` kept as safety net
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
10. Phase 3 client: **raw `RestClient`** (no fabric8), **on-demand pull** (no
    watch/poll cache), interface = exactly 3 read ops (not an SDK)
11. Phase 3 HTTP map: 401→502 `kubernetes_unauthenticated`, 403→403
    `kubernetes_forbidden`, 429→503 `kubernetes_throttled`, deadline→504,
    disabled→503; events-only RBAC denial → **200** `complete:false`
12. Auth: **explicit mode, no fallback** (`in-cluster` | `kubeconfig`); `exec`
    / client-cert / insecure-skip-tls rejected with remedy text; token re-read
    per request; future EKS IAM = a new explicit mode, never a fallback
13. Deadline 8s shared per observation, per-call read timeout clamped;
    pagination page-size 500, 410 Gone → restart once → second 410 = fail
14. `pulse.kubernetes.enabled` **defaults false** (EKS deleted — Pulse boots clean)
15. Namespace rename `pulse`→`pulse-system` approved for Phase 3 **but only
    after a full-repo occurrence survey** (classify active/historical/unrelated,
    user approves the table before edits)
16. Phase 3 scope = list + detail endpoints only (no `/api/v1/pulse`
    self-identity enrichment — revisit in Phase 4/5 if correlation needs it)
17. Multi-cluster: stays future/single-cluster now; design reviewed 2026-11-14
    (centralized pull → remote cluster via base-url+static-token works today;
    collector model and `eks-iam` auth are later stages)

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
  8080/8081/9090/3306 are the reserved local set (8082/8083 used for smokes)
- C: disk was full once → Docker data on E:; check `df -h` before big builds
- Spring test request matchers live at `org.springframework.test.web.client.match.MockRestRequestMatchers`
  (package is `match`, **not** `matchers`; Boot 3.5 = spring-test 6.2)
- SnakeYAML ships with `spring-boot-starter` — kubeconfig parsing needed **no** new dependency
- Adding a component to a Java `record` breaks every positional constructor —
  update prod mapper + any test fixtures in the same change
- `MockRestServiceServer` cannot intercept a client that builds a fresh
  `RestClient` per call → client takes a `RestClientProvider` seam (tests hand
  it a pre-bound mock; prod hands it a timeout-clamped factory)
- `mvn -q dependency:tree -Dincludes=…` prints nothing when quiet + no match —
  drop `-q` when verifying classpath questions

## 10. Pending / watch for

1. **Weekend: LinkedIn/Hasnode article** — full architecture write-up to
   showcase + demo the system for interviewers; user provides detailed notes
   first. Feed it from `ARCHITECTURE.md`, `REQUEST_TRACE.md`, phase-2/3 docs.
2. **Phase 3 M4 (next session):** full-repo namespace-occurrence survey →
   classification table (active/historical/unrelated) → user-approved edits →
   `k8s/rbac.yaml` + `serviceAccountName`. Files only; user applies.
3. **Phase 3 M6:** EKS on-demand session for live verification (costs
   $5.50/day — confirm before running `platform-up.sh`): real RBAC, rollout
   demo, CrashLoopBackOff pods, Warning events, RBAC-denied partial view
4. Phase 4: Shipmate change events (correlation source)
5. Optional later: enable histogram buckets in todoapp (makes p95 real);
   `eks-iam` auth mode + multi-cluster named targets; collector model
6. todoapp repo also has `target/` tracked — same `git rm -r --cached` fix
   when user approves (it was flagged, not yet done there)

## 11. Interview-notes index

- Phase 1: metrics exposition, Prometheus scrape model, `application` tag,
  stale-artifact debugging story
- Phase 2: error taxonomy (503/502/404), empty≠zero≠error semantics, timeout
  budgets, no-arbitrary-PromQL security stance, stateless recovery demo —
  full version in `docs/phase-2-prometheus-integration.md`
- Phase 3: pull-vs-watch, deadline clamping (`min(read, remaining)`), pagination
  `continue`/410 restart-once, auth-mode-no-fallback + fail-fast, partial
  evidence (`complete=false`), pod-evidence-over-status, centralized-pull vs
  collector multi-cluster discussion — `docs/phase-3-kubernetes-observer.md`
- End-to-end: every layer from first GET to snapshot — `docs/REQUEST_TRACE.md`
- Cross-cutting: three-plane architecture, loose coupling at K8s boundary,
  evidence-vs-change plane separation (see `ARCHITECTURE.md` §1)
