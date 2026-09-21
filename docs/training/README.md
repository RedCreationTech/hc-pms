# Training Slides (Archived)

> **Archived**: These 5 sessions were produced in June 2026, when only the
> system-management modules existed. The codebase has since gained the
> BPM / office suite (Flowable 8.0, `controllers/business/`, `pages/business/`,
> `/office/*` routes), and some referenced files were restructured.

## Contents

| Session | Topic |
|---------|-------|
| session-1-backend-architecture | Backend layering, Integrant, Kit |
| session-2-frontend-state | re-frame state management |
| session-3-frontend-ui-routing | antd components, bidi routing |
| session-4-backend-request-flow | HTTP -> middleware -> controller flow |
| session-5-backend-infra | Database, config, Integrant internals |

## How to read them today

- The *concepts* (Kit, Integrant, re-frame, middleware chain) are still
  accurate and remain a good onboarding read.
- The *file references* predate the BPM restructuring; always check the
  current paths in [../design/design.org](../design/design.org) and
  [../guides/add-new-module.md](../guides/add-new-module.md).
- The `coverage-report/` subdirectory is a stale Clojure coverage snapshot
  (2026-06-13); see `test/coverage-report.md` (also archived) and run
  `bb coverage` for current numbers.
- `assets/` holds performance experiment data (type-hint benchmarks,
  flamegraph) that is still valid.
