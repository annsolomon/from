-- P07 Ledger A (MASTER_PLAN P07, 3.1-3.5): categories, expenses, daily totals, categorisation rules (table only;
-- rules arrive in P08). Runs as ledger_owner after V202610171200__platform.
--
-- Query -> index (BUILD_README §4, P07)
--   GET /v1/expenses (keyset, newest first)   (account_id, occurred_at DESC, id DESC) WHERE deleted_at IS NULL  expenses_list
--     + filters from/to/amount/method        same index, range on occurred_at, residual filters
--     + category subtree (path <@ :cat)       GIST (account_id, category_path) WHERE deleted_at IS NULL           expenses_cat
--     + merchantId                             (account_id, merchant_id, occurred_at DESC, id DESC) WHERE ...      expenses_merchant
--     + cardId                                 (account_id, user_card_id, occurred_at DESC, id DESC) WHERE ...     expenses_card
--     (id DESC is the keyset tie-breaker: without it the planner adds an Incremental Sort)
--   GET/PATCH/DELETE/restore one expense       pkey + account_id = app_account()                                   expenses_pkey
--   create with clientRef (dedupe)             UNIQUE (account_id, client_ref) WHERE client_ref IS NOT NULL         expenses_client_ref
--   daily totals delta upsert / range read     PRIMARY KEY (account_id, day, category_root)                        expense_daily_totals_pkey
--   GET /v1/categories (system + own)          (account_id, path) unique; tiny table                              categories_account_path_key
--   category exists? (path, system or own)     same                                                               categories_account_path_key
--   subtree lookups                            GIST (path)                                                        categories_path_gist

-- ---------------------------------------------------------------- categories (ltree, system + per-account)
-- System rows (account_id NULL, is_system) are visible to every tenant; custom rows only to their owner. Without a
-- tenant context nothing is visible (fail closed), so app_account() must be set even to read system rows.
CREATE TABLE ledger.categories (
  id         uuid        PRIMARY KEY,
  account_id uuid,
  path       public.ltree NOT NULL CHECK (public.nlevel(path) BETWEEN 1 AND 4),
  name       text        NOT NULL CHECK (length(name) BETWEEN 1 AND 60),
  icon       text        CHECK (icon IS NULL OR length(icon) BETWEEN 1 AND 40),
  is_system  boolean     NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT categories_account_path_key UNIQUE NULLS NOT DISTINCT (account_id, path),
  CONSTRAINT categories_system_shape CHECK (is_system = (account_id IS NULL))
);
CREATE INDEX categories_path_gist ON ledger.categories USING gist (path);
ALTER TABLE ledger.categories ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger.categories FORCE  ROW LEVEL SECURITY;
CREATE POLICY categories_tenant ON ledger.categories FOR ALL TO ledger_app
  USING      ((SELECT ledger.app_account()) IS NOT NULL
              AND (account_id IS NULL OR account_id = (SELECT ledger.app_account())))
  WITH CHECK (account_id = (SELECT ledger.app_account()) AND NOT is_system);
CREATE POLICY categories_owner ON ledger.categories FOR ALL TO ledger_owner USING (true) WITH CHECK (true);
GRANT SELECT, INSERT ON ledger.categories TO ledger_app;

-- The system tree (same paths as seed/data/v1/categories.csv; the seed replaces these rows with its own ids).
INSERT INTO ledger.categories (id, account_id, path, name, icon, is_system)
SELECT md5('ledger.category:' || p)::uuid, NULL, CAST(p AS public.ltree), n, i, true
FROM (VALUES
  ('food', 'Food', 'utensils'), ('food.dining', 'Dining out', 'utensils'), ('food.delivery', 'Food delivery', 'bike'),
  ('food.groceries', 'Groceries', 'basket'), ('food.cafe', 'Cafes', 'coffee'),
  ('travel', 'Travel', 'plane'), ('travel.rail', 'Rail', 'train'), ('travel.air', 'Flights', 'plane'),
  ('travel.cab', 'Cabs', 'car'), ('travel.hotel', 'Hotels', 'bed'), ('travel.bus', 'Bus', 'bus'),
  ('fuel', 'Fuel', 'fuel'), ('fuel.petrol', 'Petrol and diesel', 'fuel'), ('fuel.ev', 'EV charging', 'plug'),
  ('shopping', 'Shopping', 'bag'), ('shopping.online', 'Online shopping', 'cart'),
  ('shopping.fashion', 'Fashion', 'shirt'), ('shopping.electronics', 'Electronics', 'laptop'),
  ('shopping.home', 'Home', 'home'),
  ('utilities', 'Utilities', 'bolt'), ('utilities.electricity', 'Electricity', 'bolt'),
  ('utilities.mobile', 'Mobile', 'phone'), ('utilities.broadband', 'Broadband', 'wifi'),
  ('utilities.gas', 'Gas', 'flame'), ('utilities.water', 'Water', 'droplet'), ('utilities.dth', 'DTH', 'tv'),
  ('entertainment', 'Entertainment', 'film'), ('entertainment.movies', 'Movies', 'film'),
  ('entertainment.ott', 'Streaming', 'tv'), ('entertainment.events', 'Events', 'ticket'),
  ('health', 'Health', 'heart'), ('health.pharmacy', 'Pharmacy', 'pill'), ('health.doctor', 'Doctor', 'stethoscope'),
  ('health.fitness', 'Fitness', 'dumbbell'),
  ('education', 'Education', 'book'), ('education.fees', 'Fees', 'school'), ('education.courses', 'Courses', 'book'),
  ('finance', 'Finance', 'bank'), ('finance.insurance', 'Insurance', 'shield'),
  ('finance.investments', 'Investments', 'chart'),
  ('housing', 'Housing', 'home'), ('housing.rent', 'Rent', 'key'), ('housing.maintenance', 'Maintenance', 'wrench'),
  ('personal', 'Personal care', 'user'), ('personal.salon', 'Salon', 'scissors'),
  ('gifts', 'Gifts', 'gift'), ('gifts.donations', 'Donations', 'heart'),
  ('general', 'General', 'circle')) AS t(p, n, i);

-- ---------------------------------------------------------------- expenses
CREATE TABLE ledger.expenses (
  id                    uuid        PRIMARY KEY,
  account_id            uuid        NOT NULL,
  occurred_at           timestamptz NOT NULL,
  amount_paise          bigint      NOT NULL CHECK (amount_paise > 0 AND amount_paise <= 100000000000),
  currency              char(3)     NOT NULL DEFAULT 'INR' CHECK (currency = 'INR'),
  merchant_raw          text        CHECK (merchant_raw IS NULL OR length(merchant_raw) BETWEEN 1 AND 200),
  merchant_display      text        CHECK (merchant_display IS NULL OR length(merchant_display) BETWEEN 1 AND 120),
  merchant_id           uuid,
  category_path         public.ltree NOT NULL DEFAULT 'general',
  category_source       text        NOT NULL DEFAULT 'user'
                                    CHECK (category_source IN ('user', 'resolver', 'import', 'rule')),
  resolution_confidence smallint    CHECK (resolution_confidence BETWEEN 0 AND 1000),
  payment_method        text        NOT NULL
                                    CHECK (payment_method IN ('card', 'upi', 'cash', 'netbanking', 'wallet', 'other')),
  user_card_id          uuid,
  note                  text        CHECK (note IS NULL OR length(note) <= 500),
  tags                  text[]      CHECK (tags IS NULL OR cardinality(tags) <= 10),
  client_ref            text        CHECK (client_ref IS NULL OR length(client_ref) BETWEEN 1 AND 100),
  source                text        NOT NULL DEFAULT 'manual' CHECK (source IN ('manual', 'import', 'statement', 'sync')),
  created_at            timestamptz NOT NULL DEFAULT now(),
  updated_at            timestamptz NOT NULL DEFAULT now(),
  version               integer     NOT NULL DEFAULT 0,
  deleted_at            timestamptz,
  CONSTRAINT expenses_card_only_for_card CHECK (user_card_id IS NULL OR payment_method = 'card')
);
CREATE INDEX expenses_list ON ledger.expenses (account_id, occurred_at DESC, id DESC) WHERE deleted_at IS NULL;
CREATE INDEX expenses_cat ON ledger.expenses USING gist (account_id, category_path) WHERE deleted_at IS NULL;
CREATE INDEX expenses_merchant ON ledger.expenses (account_id, merchant_id, occurred_at DESC, id DESC)
  WHERE deleted_at IS NULL AND merchant_id IS NOT NULL;
CREATE UNIQUE INDEX expenses_client_ref ON ledger.expenses (account_id, client_ref) WHERE client_ref IS NOT NULL;
CREATE INDEX expenses_card ON ledger.expenses (account_id, user_card_id, occurred_at DESC, id DESC)
  WHERE user_card_id IS NOT NULL AND deleted_at IS NULL;
ALTER TABLE ledger.expenses ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger.expenses FORCE  ROW LEVEL SECURITY;
CREATE POLICY expenses_tenant ON ledger.expenses FOR ALL TO ledger_app
  USING      (account_id = (SELECT ledger.app_account()))
  WITH CHECK (account_id = (SELECT ledger.app_account()));
CREATE POLICY expenses_owner ON ledger.expenses FOR ALL TO ledger_owner USING (true) WITH CHECK (true);
-- no DELETE: expenses are soft-deleted (P18's privacy saga deletes through its own path)
GRANT SELECT, INSERT, UPDATE ON ledger.expenses TO ledger_app;

-- ---------------------------------------------------------------- daily totals (same transaction as every write)
-- day = the local date in Asia/Kolkata (A-45); category_root = first label of the expense's category.
CREATE TABLE ledger.expense_daily_totals (
  account_id    uuid         NOT NULL,
  day           date         NOT NULL,
  category_root public.ltree NOT NULL CHECK (public.nlevel(category_root) = 1),
  total_paise   bigint       NOT NULL CHECK (total_paise >= 0),
  txn_count     integer      NOT NULL CHECK (txn_count >= 0),
  PRIMARY KEY (account_id, day, category_root)
);
ALTER TABLE ledger.expense_daily_totals ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger.expense_daily_totals FORCE  ROW LEVEL SECURITY;
CREATE POLICY expense_daily_totals_tenant ON ledger.expense_daily_totals FOR ALL TO ledger_app
  USING      (account_id = (SELECT ledger.app_account()))
  WITH CHECK (account_id = (SELECT ledger.app_account()));
CREATE POLICY expense_daily_totals_owner ON ledger.expense_daily_totals FOR ALL TO ledger_owner
  USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE, DELETE ON ledger.expense_daily_totals TO ledger_app;

-- ---------------------------------------------------------------- categorisation rules (used from P08)
CREATE TABLE ledger.categorization_rules (
  id            uuid         PRIMARY KEY,
  account_id    uuid         NOT NULL,
  match_type    text         NOT NULL CHECK (match_type IN ('merchant_id', 'contains')),
  pattern       text         NOT NULL CHECK (length(pattern) BETWEEN 1 AND 120),
  category_path public.ltree NOT NULL,
  priority      integer      NOT NULL DEFAULT 100 CHECK (priority BETWEEN 0 AND 10000),
  created_at    timestamptz  NOT NULL DEFAULT now(),
  version       integer      NOT NULL DEFAULT 0
);
CREATE INDEX categorization_rules_account ON ledger.categorization_rules (account_id, priority, id);
ALTER TABLE ledger.categorization_rules ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger.categorization_rules FORCE  ROW LEVEL SECURITY;
CREATE POLICY categorization_rules_tenant ON ledger.categorization_rules FOR ALL TO ledger_app
  USING      (account_id = (SELECT ledger.app_account()))
  WITH CHECK (account_id = (SELECT ledger.app_account()));
CREATE POLICY categorization_rules_owner ON ledger.categorization_rules FOR ALL TO ledger_owner
  USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE, DELETE ON ledger.categorization_rules TO ledger_app;
