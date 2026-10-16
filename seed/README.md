# seed/ — deterministic mock data (MASTER_PLAN 11.1, ADR-0008)

Dev/test only. It is never a dependency of a service.

```bash
./scripts/seed.sh small            # 2 demo accounts × 500 expenses (+ admin), reference data; loads into core Postgres
./scripts/seed.sh medium           # 10,000 accounts, 1,200,000 expenses, 30,000 user cards (PlanAssert, P07+)
./scripts/seed.sh large            # 100,000 accounts, 5,000,000 expenses, 300,000 user cards (P19 load)
./scripts/seed.sh medium --seed 7 --regen --no-load
./scripts/seed.sh check medium     # determinism: second generation must give the same checksum
./scripts/seed.sh fixtures         # regenerate test-ui/fixtures/seed/*.json (commit them)
```

Output lands in `target/seed/<size>-<seed>/`:

- `manifest.tsv` (what the loader reads)
- `manifest.json` (row counts, SHA-256 per file, dataset `checksum`)
- one COPY text file per table: `<service>/<table>.tsv`

## What gets loaded where

| Service DB | Tables (phase that creates the real table) |
|---|---|
| identity | `accounts` (P04), `admins` (P05) |
| catalog | `issuers`, `card_products`, `card_product_versions`, `reward_rules`, `milestones` (P09) |
| portfolio | `user_cards` (P11) |
| ledger | `categories`, `expenses`, `expense_daily_totals` (P07); `merchants`, `merchant_aliases` (P08) |

The loader connects as `<svc>_owner`.

- **Real table exists:** it COPYs into it, with FORCE RLS off only inside the load transaction.
- **No real table yet:** it COPYs into `seed_stage.<table>`, a schema only the owner can reach.

When a phase's migration differs from the planned columns in `Tables.java`, the load fails, naming the column. That
phase updates `Tables` and `Generator` (ADR-0008).

## Data model

- **Demo personas:**
  - `asha@demo.in` (PREMIUM, 3 cards)
  - `ravi@demo.in` (FREE, 2 cards)
  - `admin@demo.in` (`identity.admins`, SUPER_ADMIN)

  Passwords come from `.env` (`DEMO_PASSWORD_*`). The two users have exactly 500 expenses each, and they are
  identical in every size.
- **Bulk accounts:** `u0000001@seed.test` …, all with `SEED_BULK_PASSWORD`. There is one Argon2id hash for all of
  them, because hashing is slow on purpose.
- **Spending window:** 2025-10-01 … 2026-09-30, Asia/Kolkata. It includes:
  - a salary-week bump (days 1–7);
  - the Navratri/Diwali 2025 festive spike (Oct 1–24, up to ×3 for shopping);
  - weekend shifts per category root;
  - UPI vs card mix per category;
  - price points for mobile/OTT/gas/broadband;
  - a fixed monthly rent per account;
  - "₹500 of petrol" rounding.
- **Expenses:**
  - ~15 % are `source=import` with a unique `client_ref`;
  - ~1 % are soft-deleted;
  - ~3 % have been edited (`version` > 0).
- **Daily totals:** `expense_daily_totals` equals `SUM` over the live expenses per (account, IST day, category
  root). This is tested, and checked with SQL on the medium seed.
- **Reference data:** `seed/data/v1/*.csv`. It contains:
  - 48 ltree categories;
  - 357 Indian merchant brands and 635 normalised aliases;
  - 10 fictional `MOCK_` issuers and 40 `MOCK_` card products;
  - 103 reward rules (one base rule per card plus category accelerators with caps);
  - 10 milestones.

  Reference ids depend only on the data version, never on `--seed`.

### Reward rule columns

- `rate_bps` is the reward **value** in bps of spend.
- `point_value_milli_paise` converts it to points/miles (25,000 = 25 paise per point) for cards that earn points.
- `cap_paise` + `cap_period` bound the accelerator.
- `excluded_paths` lists categories that earn nothing on the base rule.
