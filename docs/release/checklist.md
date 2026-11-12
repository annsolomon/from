# Release checklist and the v2.0.0 tag (P19)

Releases are cut from a `release/<version>` branch: the branch is `main` minus the trail (P19 step 7), and its CI is
the release gate.

## 1. Cut the branch
```bash
git switch main && git pull
git switch -c release/v2.0
```
The trail is removed on this branch in one commit (see "Trail removal" below); `main` keeps it for development.

## 2. Gates (all must hold on the release branch HEAD)
- [ ] `verify-full` green on the branch: `verify.sh full` (static incl. `static.release-guard`, unit, ITs, smoke,
      vault-check, k6 smoke, obs-check, ZAP baseline, chaos, restore drill, image scan). Read it from
      `verify-reports` (`run/RUN.txt` → `sha` = branch HEAD, `verify_full=success`).
- [ ] `static.release-guard` PASS: no `test-ui/`, no trail compose profile, no trail nginx config, no
      `trail-check.sh`, no `@Profile("dev")` controller or lab, no dev CORS origins, no `dev-routes: true`.
- [ ] Scale proof recorded for this commit or the one before it with no change on the hot paths
      (`docs/perf/results.md`; targets met or the gap has a plan).
- [ ] No open `security` issue High or above (docs/security/pentest-checklist.md).
- [ ] Every ADR in BUILD_README §5 is Accepted or explicitly Deferred.
- [ ] Migrations since the last release are expand-only (docs/runbooks/deploy.md).
- [ ] `docs/api/routes.md` and `docs/api/errors.md` match the code (`RouteDocsTest`, error catalogue tests).

## 3. Tag
```bash
git switch release/v2.0
git tag -a v2.0.0 -m "Dexter v2.0.0" <green HEAD sha>
git push origin v2.0.0
```
Images: build from the tag (`scripts/image-scan.sh` builds and scans them; push with
`docker tag dexter/<svc>:<sha> registry/dexter/<svc>:2.0.0`), record the digests in the release notes.

## 4. Release notes
What changed since the last tag (phase list from BUILD_README §1), migrations, new configuration and secrets
(Vault paths), known gaps (BUILD_README §7), the scale numbers, and the rollback version.

## 5. Deploy
docs/runbooks/deploy.md. Fixes for the release go to `main` first and are cherry-picked to `release/v2.0`
(`v2.0.1`, ...).

## Trail removal (done once, on the release branch)
Deleted: `test-ui/`; the `trail` compose service and `infra/nginx/` trail config; `scripts/trail-check.sh`; the trail
steps of `verify.sh` (`static.trail-pager`, the trail-isolation rule stays as a guard) and of `verify-full.yml`;
the dev labs and dev endpoints (`P01LabController`, `libs/dexter-lab`, `DevKeysController`, the gateway's dev routes
and dev CORS origins); `seed.sh fixtures`. `static.release-guard` keeps them out.
