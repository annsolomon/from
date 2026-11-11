INSERT INTO reco.spend_month (account_id, category_root, month, total_paise, txn_count) VALUES ('${account}', CAST('food' AS public.ltree), DATE '2026-01-01', 100000, 1);
INSERT INTO reco.spend_profile (account_id, category_root, window_code, monthly_avg_paise, total_paise, txn_count) VALUES ('${account}', CAST('food' AS public.ltree), '3m', 100000, 300000, 3);
INSERT INTO reco.held_cards_proj (account_id, user_card_id, card_product_id, projected_version) VALUES ('${account}', gen_random_uuid(), gen_random_uuid(), 1);
INSERT INTO reco.rec_state (account_id, dirty, dirty_since) VALUES ('${account}', true, now());
INSERT INTO reco.recommendations (account_id, state_hash, payload) VALUES ('${account}', 'hash', '{}'::jsonb);
INSERT INTO reco.card_period_spend (account_id, user_card_id, category_root, month, total_paise, txn_count) VALUES ('${account}', gen_random_uuid(), CAST('food' AS public.ltree), DATE '2026-11-01', 100000, 1);
INSERT INTO reco.recommendation_history (account_id, computed_at, state_hash, payload) VALUES ('${account}', now(), 'hash', '{}'::jsonb);
INSERT INTO reco.idempotency_keys (account_id, idem_key, request_method, request_path, request_hash, state) VALUES ('${account}', gen_random_uuid(), 'POST', '/x', '\x00', 'in_progress');
