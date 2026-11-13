# Dexter v2 — full frontend (`test-ui/app/`)

One self-contained page (`index.html`) covering every route of the 10 services: 194 controller endpoints + the
gateway's own health + documented routes with no handler. It has two modes, switched at the top right:

| Mode | What answers | When to use |
|---|---|---|
| **Mock data** (default) | An in-browser backend (`src/handlers*.js`, `src/db.js`) seeded from `test-ui/fixtures/seed` — RLS-style per-account data, keyset cursors bound to filters, Idempotency-Key replay/422, ETag/If-Match 412/428, ProblemDetail codes, rate-limit headers, outbox → consumer events | Seeing every feature without the stack; the LTREE recalculation lab, the event log and run-all are mock-only (they instrument the mock) |
| **Live backend** | The real stack through the trail nginx: `/api/*` → gateway `:8080` | Working with the backend |

## Run it against the backend

```bash
./scripts/run.sh all          # infra + services (dev profile)
./scripts/seed.sh small       # demo accounts asha / ravi / admin
./scripts/run.sh trail        # nginx on http://localhost:8099 serving test-ui/ with /api → gateway
open http://localhost:8099/app/        # then switch "Mock data" → "Live backend (/api)"
```

- Users sign in automatically with the dev passwords from `.env.example` (`DEMO_PASSWORD_*`, set in `src/live.js` —
  change them there if your `.env` differs). An MFA-enrolled account finishes on **Sign-in & security**.
- Admin: **Admin console → Admins & accounts → Sign in as admin (live)**. The first console sign-in sets up TOTP; the
  page keeps that base32 secret in `localStorage` (dev only) and computes the codes. If the admin was set up elsewhere
  (e.g. on `p05-mfa-admin.html`), paste the secret there.
- Every call — mock or live — is in the **API inspector** (top right) with request headers, body, response headers and
  body. Live calls use synchronous XHR so the same screen code drives both modes.
- Live mode adds an `Idempotency-Key` to POST/PUT/PATCH calls that need one (not to calc, auth, webhooks, `/r/`).

### Live mode: what has and hasn't been proved

The mock's request/response shapes were written from the controllers' and services' records, and the
calculators are exact ports (below), but **this page has not been run against a live stack yet** (the build sandbox
has no Docker). If a screen shows "Render error" in Live mode, the API inspector shows the raw answer; that endpoint's
shape differs from the mock and the screen needs adjusting. Known live limits:

- `/r/{token}` opens in a new tab (the browser follows the 302 cross-origin, so XHR can't show it).
- The provider/affiliate webhook buttons send dummy signatures → 400 live (the HMAC secret is server-side).
- The CSV import's presigned PUT goes straight to the object store from the browser; it needs the store's CORS.
- The LTREE recalculation lab, the event log and run-all are mock-only.

## Screens

Home · Answers to the three design questions · LTREE recalculation lab · Expenses (50/25/24 keyset window, resolver,
idempotency, If-Match, search, bulk, daily totals) · Calendar / summary / categories / rules / CSV import · Budgets
(status, **typing → offers planner**, history, liabilities, scenarios) · My cards (transactions, statements,
auto-match, rewards, report) · Statement upload · **EMI center** (tracker, convert, least-loss planner, every situation)
· Calculators (all 12 `/v1/calc/*`) · Card marketplace · Deals & offers marketplace · Recommendations (ranking,
explain, wallet, best card now, SSE) · Sign-in & security · Billing · Notifications & privacy · Admin: admins &
accounts, catalog, ingestion & review, commerce, audit & activity · Platform (gateway edge, events, run-all) · Endpoint
index.

## What is proposed, not in the backend (labelled on screen)

- **Least-loss EMI planner and the EMI situation simulator** (`src/emi.js`): built on the exact calc ports; adds
  foreclosure pricing, no-cost-EMI subvention, grace-period loss, missed-instalment / cancellation paths. Offer rates,
  the 3 % foreclosure fee and the late-fee table are illustrative inputs, not repo data.
- **Budget → offers mapping**: composed on the client from five existing endpoints, because
  `GET /v1/budgets/category-potential` is still unbuilt (A-75).

## Findings this work surfaced (in the backend)

1. `MinimumPaymentSimulator` returns `cyclesToPayoff = 360` with `infiniteDebtWarning = false` when the 360-cycle cap is
   hit while principal is still owed (₹1,00,000 at 42.84 % with a 5 % / ₹200 minimum leaves ₹4,710.79), and
   `ExitPathCalculator.continueMinimum` repeats it as "takes 360 cycles".
2. `ExitPathCalculator.recommendedPath` always picks the cheapest *targeted* path, even when paying in full costs ₹0
   extra; there is no affordability input.
3. `emi_schedule.status` allows `paid`, but nothing ever sets it; foreclosure only cancels rows, nothing prices it.
4. The gateway routes `GET /v1/admin/accounts/{id}` (routes.md, P18) but identity has no handler for it → 404.
5. Issuer offers (`catalog.offers`) and merchant deals (`commerce.deals`) are two unconnected offer models.

## Verify

```bash
test-ui/app/verify/run.sh
```

1. Compiles `libs/portfolio-calc` with plain `javac` plus `verify/RefGrid.java`, prints 1,276 reference answers from the
   real Java engines (EMI ×1,008, flat-payoff search, flat payment, minimum due, minimum-only, no-payment, ledger,
   repayment path, custom fees, full and partial payment) and checks the JavaScript ports against them **to the
   paisa** (`PASS 1276 FAIL 0`).
2. Runs every endpoint against the mock backend in node (`mock run-all: 209 calls, 202/202 routes, 0 unexpected`).

## Edit

Sources are in `src/` (plain JS, no bundler). After editing: `python3 test-ui/app/build.py`.
`python3 test-ui/app/make_seed.py` regenerates `src/seed.js` from the trail fixtures (`./scripts/seed.sh fixtures`).

Like the rest of `test-ui/`, this folder is a development aid: the release branch (`release/v2.0`) carries no
`test-ui/`, and the release guard keeps it that way.
