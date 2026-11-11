INSERT INTO commerce.card_applications (id, account_id, card_product_id, status, created_at, updated_at) VALUES (gen_random_uuid(), '${account}', gen_random_uuid(), 'submitted', now(), now());
INSERT INTO commerce.held_cards_proj (account_id, user_card_id, card_product_id, projected_version) VALUES ('${account}', gen_random_uuid(), gen_random_uuid(), 1);
INSERT INTO commerce.ad_free_proj (account_id, tier, ad_free, projected_version) VALUES ('${account}', 'PREMIUM', true, 1);
INSERT INTO commerce.deal_clicks (id, created_at, account_id, deal_id, click_token_hash, ip_hash, ua_hash) VALUES (gen_random_uuid(), now(), '${account}', gen_random_uuid(), sha256('t'), sha256('i'), sha256('u'));
INSERT INTO commerce.conversions (id, network, provider_ref, account_id, amount_paise, commission_paise, status, occurred_at) VALUES (gen_random_uuid(), 'mock', 'p18-' || gen_random_uuid(), '${account}', 100, 4, 'pending', now());
INSERT INTO commerce.ad_impressions_raw (id, created_at, creative_id, campaign_id, placement, account_id, visitor_hash) VALUES (gen_random_uuid(), now(), gen_random_uuid(), gen_random_uuid(), 'home', '${account}', sha256('v'));
INSERT INTO commerce.idempotency_keys (account_id, idem_key, request_method, request_path, request_hash, state) VALUES ('${account}', gen_random_uuid(), 'POST', '/x', '\x00', 'in_progress');
