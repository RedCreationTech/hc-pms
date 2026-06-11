# Agent Instructions

This project uses **bd** (beads) for issue tracking. Run `bd prime` for full workflow context.

## Quick Reference

```bash
bd ready              # Find available work
bd show <id>          # View issue details
bd update <id> --claim  # Claim work atomically
bd close <id>         # Complete work
bd dolt push          # Push beads data to remote
```

## Non-Interactive Shell Commands

**ALWAYS use non-interactive flags** with file operations to avoid hanging on confirmation prompts.

Shell commands like `cp`, `mv`, and `rm` may be aliased to include `-i` (interactive) mode on some systems, causing the agent to hang indefinitely waiting for y/n input.

**Use these forms instead:**
```bash
# Force overwrite without prompting
cp -f source dest           # NOT: cp source dest
mv -f source dest           # NOT: mv source dest
rm -f file                  # NOT: rm file

# For recursive operations
rm -rf directory            # NOT: rm -r directory
cp -rf source dest          # NOT: cp -r source dest
```

**Other commands that may prompt:**
- `scp` - use `-o BatchMode=yes` for non-interactive
- `ssh` - use `-o BatchMode=yes` to fail instead of prompting
- `apt-get` - use `-y` flag
- `brew` - use `HOMEBREW_NO_AUTO_UPDATE=1` env var

<!-- BEGIN BEADS INTEGRATION v:1 profile:minimal hash:ca08a54f -->
## Beads Issue Tracker

This project uses **bd (beads)** for issue tracking. Run `bd prime` to see full workflow context and commands.

### Quick Reference

```bash
bd ready              # Find available work
bd show <id>          # View issue details
bd update <id> --claim  # Claim work
bd close <id>         # Complete work
```

### Rules

- Use `bd` for ALL task tracking — do NOT use TodoWrite, TaskCreate, or markdown TODO lists
- Run `bd prime` for detailed command reference and session close protocol
- Use `bd remember` for persistent knowledge — do NOT use MEMORY.md files

## Session Completion

**When ending a work session**, you MUST complete ALL steps below. Work is NOT complete until `git push` succeeds.

**MANDATORY WORKFLOW:**

1. **File issues for remaining work** - Create issues for anything that needs follow-up
2. **Run quality gates** (if code changed) - Tests, linters, builds
3. **Update issue status** - Close finished work, update in-progress items
4. **PUSH TO REMOTE** - This is MANDATORY:
   ```bash
   git pull --rebase
   bd dolt push
   git push
   git status  # MUST show "up to date with origin"
   ```
5. **Clean up** - Clear stashes, prune remote branches
6. **Verify** - All changes committed AND pushed
7. **Hand off** - Provide context for next session

**CRITICAL RULES:**
- Work is NOT complete until `git push` succeeds
- NEVER stop before pushing - that leaves work stranded locally
- NEVER say "ready to push when you are" - YOU must push
- If push fails, resolve and retry until it succeeds
<!-- END BEADS INTEGRATION -->

## Development Workflow

### Architecture

```
Backend  (Clojure, port 3000)    — API + serves static frontend
Frontend (ClojureScript)          — SPA via shadow-cljs, compiled to resources/public/js/
Database (SQLite)                 — rouyi.db, auto-migrated on startup
```

Both frontend and backend share port **3000**. The backend serves both API and static files.

### Starting Dev Environment

```bash
# 1. Start backend (port 3000, nREPL port 7000)
cd /home/kevin/gt/rouyi_clojure/mayor/rig
rm -f rouyi.db && clojure -M:dev -m com.ruoyi.rouyi.core &

# 2. Start frontend watch (auto-recompiles on .cljs changes)
setsid bash -c 'cd /home/kevin/gt/rouyi_clojure/mayor/rig && npx shadow-cljs watch app' &
# First compilation takes ~2min, subsequent changes compile in seconds

# 3. Access
open http://localhost:3000
```

### Hot-Reload Workflow

#### Backend (Clojure) — nREPL hot-reload, no restart needed

```bash
# After editing .clj files:
clj-nrepl-eval -p 7000 '(user/rd)'          # Reload domain services (fastest)
clj-nrepl-eval -p 7000 '(user/rroutes)'     # Reload routes (needs system reset to apply)
clj-nrepl-eval -p 7000 '(user/ra)'          # Reload all namespaces
clj-nrepl-eval -p 7000 '(user/rr)'          # Full Integrant reset (halt + go)
```

Available helpers (defined in `env/dev/clj/user.clj`):

| Helper | Short | What it reloads |
|--------|-------|-----------------|
| `reload-domain` | `rd` | Domain services (user, role, menu, dept, dict, config, log, gen) |
| `reload-middleware` | `rm` | Ring middleware (auth, exception, operlog, core) |
| `reload-routes` | `rroutes` | Route definitions (needs `rr` to apply) |
| `reload-controllers` | — | Web controllers |
| `reload-infra` | — | Security, online, data-perm |
| `reload-all` | `ra` | All of the above |
| `reload-system` | `rr` | Full system reset (halt → prep → go) |

**Note**: Route changes require a full system reset (`rr`) because routes are compiled once at startup.

#### Frontend (ClojureScript) — shadow-cljs auto-compiles

```bash
# shadow-cljs watch is running in background
# Edit .cljs files → watch auto-detects → incremental compile (~5s)
# Just refresh browser to see changes
```

### When to Restart (not just reload)

- HugSQL `.sql` file changes (queries are cached at startup)
- `resources/system.edn` config changes
- Integrant component structure changes
- After these, run `clj-nrepl-eval -p 7000 '(user/rr)'` or restart the process

### Build Uberjar

```bash
npx shadow-cljs release app    # Compile frontend for production
clojure -T:build all            # Build standalone jar (includes frontend)
java -jar target/rouyi-standalone.jar  # Run (port 3000, SQLite)
```

### API Access

- Swagger UI: `http://localhost:3000/api`
- Health: `GET /api/health`
- Login: `POST /api/auth/login` with `{"username":"admin","password":"admin123"}`
- Most endpoints require `Authorization: Bearer <token>` header
