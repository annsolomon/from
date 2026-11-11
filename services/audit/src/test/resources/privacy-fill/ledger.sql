INSERT INTO ledger.expenses (id, account_id, occurred_at, amount_paise, payment_method) VALUES (gen_random_uuid(), '${account}', now(), 100, 'cash');
INSERT INTO ledger.expense_daily_totals (account_id, day, category_root, total_paise, txn_count) VALUES ('${account}', current_date, 'food', 100, 1);
INSERT INTO ledger.categorization_rules (id, account_id, match_type, pattern, category_path) VALUES (gen_random_uuid(), '${account}', 'contains', 'swiggy', 'food.delivery');
WITH j AS (INSERT INTO ledger.import_jobs (id, account_id, status, file_key) VALUES (gen_random_uuid(), '${account}', 'running', 'k') RETURNING id, account_id) INSERT INTO ledger.import_errors (job_id, account_id, row_no, code) SELECT id, account_id, 1, 'X' FROM j;
INSERT INTO ledger.categories (id, account_id, path, name, is_system) VALUES (gen_random_uuid(), '${account}', CAST('food.p18_' || substr(md5(random()::text), 1, 8) AS ltree), 'x', false);
INSERT INTO ledger.idempotency_keys (account_id, idem_key, request_method, request_path, request_hash, state) VALUES ('${account}', gen_random_uuid(), 'POST', '/x', '\x00', 'in_progress');
