/* ---------- endpoint registry: every route the 10 services expose (from the controllers, P04–P18) ---------- */
const R = (m, p, svc, ph, acc, scr, d, extra = {}) => ({ m, p, svc, ph, acc, scr, d, ...extra });
const REGISTRY = [
  // identity — user auth
  R('POST', '/v1/auth/register', 'identity', 'P04', 'public', 'auth', 'Create an account (Idempotency-Key)'),
  R('POST', '/v1/auth/verify-email', 'identity', 'P04', 'public', 'auth', 'Verify the e-mail token'),
  R('POST', '/v1/auth/login', 'identity', 'P04', 'public', 'auth', 'Password sign-in → tokens or MFA challenge'),
  R('POST', '/v1/auth/mfa/verify', 'identity', 'P05', 'public', 'auth', 'Answer the MFA challenge (TOTP or recovery code)'),
  R('POST', '/v1/auth/step-up', 'identity', 'P05', 'token', 'auth', 'Re-prove identity for sensitive actions'),
  R('POST', '/v1/auth/token/refresh', 'identity', 'P04', 'public', 'auth', 'Rotate the refresh token'),
  R('POST', '/v1/auth/logout', 'identity', 'P04', 'token', 'auth', 'End this session'),
  R('POST', '/v1/auth/logout-all', 'identity', 'P04', 'token', 'auth', 'End every session'),
  R('POST', '/v1/auth/password/forgot', 'identity', 'P04', 'public', 'auth', 'Send a reset link (always 202)'),
  R('POST', '/v1/auth/password/reset', 'identity', 'P04', 'public', 'auth', 'Set a new password with the token'),
  R('POST', '/v1/auth/password/change', 'identity', 'P04', 'token', 'auth', 'Change password (signed in)'),
  R('GET', '/v1/me', 'identity', 'P04', 'token', 'auth', 'Profile'),
  R('PATCH', '/v1/me', 'identity', 'P04', 'token', 'auth', 'Merge-patch profile (If-Match)'),
  R('GET', '/v1/me/sessions', 'identity', 'P04', 'token', 'auth', 'Active sessions'),
  R('DELETE', '/v1/me/sessions/{id}', 'identity', 'P04', 'token', 'auth', 'Revoke one session'),
  R('GET', '/v1/me/mfa', 'identity', 'P05', 'token', 'auth', 'MFA status'),
  R('POST', '/v1/me/mfa/totp/enroll', 'identity', 'P05', 'token', 'auth', 'Start TOTP enrolment (secret + otpauth URI)'),
  R('POST', '/v1/me/mfa/totp/confirm', 'identity', 'P05', 'token', 'auth', 'Confirm TOTP → recovery codes'),
  R('DELETE', '/v1/me/mfa/totp', 'identity', 'P05', 'token', 'auth', 'Disable TOTP (step-up)'),
  R('POST', '/v1/me/mfa/recovery-codes', 'identity', 'P05', 'token', 'auth', 'Regenerate recovery codes'),
  R('GET', '/.well-known/jwks.json', 'identity', 'P04', 'public', 'auth', 'Public signing keys'),
  R('POST', '/oauth2/token', 'identity', 'P04', 'internal', 'platform', 'Service client-credentials token (never routed at the edge)'),
  R('GET', '/_dev/p04/keys', 'identity', 'P04', 'dev', 'platform', 'Dev: key ring snapshot'),
  R('POST', '/_dev/p04/keys/rotate', 'identity', 'P04', 'dev', 'platform', 'Dev: rotate the signing key'),
  // identity — admin
  R('POST', '/v1/admin/auth/login', 'identity', 'P05', 'public', 'admin-identity', 'Admin sign-in (MFA always)'),
  R('POST', '/v1/admin/auth/mfa/setup', 'identity', 'P05', 'public', 'admin-identity', 'First-login TOTP setup'),
  R('POST', '/v1/admin/auth/mfa/verify', 'identity', 'P05', 'public', 'admin-identity', 'Admin MFA answer'),
  R('POST', '/v1/admin/auth/token/refresh', 'identity', 'P05', 'public', 'admin-identity', 'Admin refresh'),
  R('POST', '/v1/admin/auth/logout', 'identity', 'P05', 'admin', 'admin-identity', 'Admin logout'),
  R('POST', '/v1/admin/auth/invite/accept', 'identity', 'P05', 'public', 'admin-identity', 'Accept an admin invite'),
  R('GET', '/v1/admin/me', 'identity', 'P05', 'admin', 'admin-identity', 'Admin profile + permissions'),
  R('POST', '/v1/admin/elevations', 'identity', 'P05', 'admin', 'admin-identity', 'Break-glass elevation (reason + TOTP)'),
  R('DELETE', '/v1/admin/elevations/current', 'identity', 'P05', 'admin', 'admin-identity', 'End elevation'),
  R('GET', '/v1/admin/admins', 'identity', 'P05', 'admin', 'admin-identity', 'Admins (keyset)'),
  R('GET', '/v1/admin/admins/{id}', 'identity', 'P05', 'admin', 'admin-identity', 'One admin'),
  R('POST', '/v1/admin/admins', 'identity', 'P05', 'admin', 'admin-identity', 'Invite an admin'),
  R('PATCH', '/v1/admin/admins/{id}', 'identity', 'P05', 'admin', 'admin-identity', 'Roles / status (If-Match)'),
  R('GET', '/v1/admin/accounts', 'identity', 'P05', 'admin', 'admin-identity', 'Look up a user account by e-mail'),
  R('GET', '/v1/admin/accounts/{id}', 'identity', 'P18', 'admin', 'admin-identity', 'Routed by the gateway (routes.md) — NO identity controller handles it (finding)', { gap: true }),
  // ledger
  R('POST', '/v1/expenses', 'ledger', 'P07', 'token', 'expenses', 'Create (Idempotency-Key required)'),
  R('GET', '/v1/expenses', 'ledger', 'P07', 'token', 'expenses', 'Keyset list + filters (category subtree via ltree <@)'),
  R('GET', '/v1/expenses/search', 'ledger', 'P08', 'token', 'expenses', 'Trigram search (merchant + note), 12-month default'),
  R('GET', '/v1/expenses/daily-totals', 'ledger', 'P07', 'token', 'expenses', 'Per-day/root aggregates maintained in the write txn'),
  R('GET', '/v1/expenses/{id}', 'ledger', 'P07', 'token', 'expenses', 'One expense (ETag)'),
  R('PATCH', '/v1/expenses/{id}', 'ledger', 'P07', 'token', 'expenses', 'Merge-patch (If-Match)'),
  R('DELETE', '/v1/expenses/{id}', 'ledger', 'P07', 'token', 'expenses', 'Soft delete'),
  R('POST', '/v1/expenses/{id}:restore', 'ledger', 'P07', 'token', 'expenses', 'Undo a delete'),
  R('POST', '/v1/expenses:bulk', 'ledger', 'P08', 'token', 'expenses', '≤500 items, per-item result, clientRef idempotent'),
  R('GET', '/v1/categories', 'ledger', 'P07', 'token', 'insights', 'System + custom ltree categories'),
  R('POST', '/v1/categories', 'ledger', 'P07', 'token', 'insights', 'Custom subtree under a system root'),
  R('GET', '/v1/merchants/autocomplete', 'ledger', 'P08', 'token', 'expenses', 'Top 8 as you type (≥2 chars)'),
  R('POST', '/v1/merchants/resolve', 'ledger', 'P08', 'token', 'expenses', 'Raw text → merchant + category + confidence'),
  R('GET', '/v1/rules', 'ledger', 'P08', 'token', 'insights', 'Categorisation rules'),
  R('POST', '/v1/rules', 'ledger', 'P08', 'token', 'insights', 'Add a rule (applied before the resolver)'),
  R('DELETE', '/v1/rules/{id}', 'ledger', 'P08', 'token', 'insights', 'Remove a rule'),
  R('POST', '/v1/imports', 'ledger', 'P08', 'token', 'insights', 'Create a CSV import (presigned upload)'),
  R('POST', '/v1/imports/{id}:start', 'ledger', 'P08', 'token', 'insights', 'Start the async import job'),
  R('GET', '/v1/imports/{id}', 'ledger', 'P08', 'token', 'insights', 'Job progress + row errors'),
  R('GET', '/v1/calendar', 'ledger', 'P08', 'token', 'insights', 'Month grid: daily totals + upcoming liabilities'),
  R('GET', '/v1/summary', 'ledger', 'P08', 'token', 'insights', 'Group by category|merchant|day|paymentMethod'),
  // catalog
  R('GET', '/v1/catalog/cards', 'catalog', 'P09', 'public', 'catalog', 'Published cards (keyset; network, issuer, maxAnnualFee, category ≥ bps)'),
  R('GET', '/v1/catalog/cards/search', 'catalog', 'P09', 'public', 'catalog', 'Trigram name search'),
  R('GET', '/v1/catalog/cards/{slug}', 'catalog', 'P09', 'public', 'catalog', 'Card detail (versioned snapshot)'),
  R('GET', '/v1/catalog/offers', 'catalog', 'P09', 'public', 'catalog', 'Active issuer offers by merchant / category (ltree)'),
  R('GET', '/v1/catalog/categories/potential', 'catalog', 'P09', 'public', 'catalog', 'Best published rate for a category'),
  R('POST', '/v1/admin/catalog/issuers', 'catalog', 'P09', 'admin', 'admin-catalog', 'Add an issuer'),
  R('GET', '/v1/admin/catalog/cards', 'catalog', 'P09', 'admin', 'admin-catalog', 'All cards incl. drafts'),
  R('POST', '/v1/admin/catalog/cards', 'catalog', 'P09', 'admin', 'admin-catalog', 'New card (draft)'),
  R('GET', '/v1/admin/catalog/cards/{id}/draft', 'catalog', 'P09', 'admin', 'admin-catalog', 'Read the draft'),
  R('PUT', '/v1/admin/catalog/cards/{id}/draft', 'catalog', 'P09', 'admin', 'admin-catalog', 'Replace the draft (If-Match)'),
  R('DELETE', '/v1/admin/catalog/cards/{id}/draft', 'catalog', 'P09', 'admin', 'admin-catalog', 'Discard the draft'),
  R('GET', '/v1/admin/catalog/cards/{id}/versions', 'catalog', 'P09', 'admin', 'admin-catalog', 'Immutable published versions'),
  R('POST', '/v1/admin/catalog/cards/{id}:publish', 'catalog', 'P09', 'admin', 'admin-catalog', 'Publish → new version + catalog.card.published'),
  R('POST', '/v1/admin/catalog/cards/{id}:retire', 'catalog', 'P09', 'admin', 'admin-catalog', 'Retire a card'),
  R('POST', '/v1/admin/catalog/offers', 'catalog', 'P09', 'admin', 'admin-catalog', 'Create an issuer offer'),
  R('POST', '/v1/admin/catalog/offers/{id}:withdraw', 'catalog', 'P09', 'admin', 'admin-catalog', 'Withdraw an offer'),
  R('GET', '/v1/admin/ingestion/sources', 'catalog', 'P10', 'admin', 'admin-ingest', 'Crawl sources'),
  R('POST', '/v1/admin/ingestion/sources', 'catalog', 'P10', 'admin', 'admin-ingest', 'Add a source (SSRF-checked)'),
  R('GET', '/v1/admin/ingestion/sources/{id}', 'catalog', 'P10', 'admin', 'admin-ingest', 'One source'),
  R('PATCH', '/v1/admin/ingestion/sources/{id}', 'catalog', 'P10', 'admin', 'admin-ingest', 'Edit (If-Match)'),
  R('DELETE', '/v1/admin/ingestion/sources/{id}', 'catalog', 'P10', 'admin', 'admin-ingest', 'Remove'),
  R('POST', '/v1/admin/ingestion/sources/{id}/runs', 'catalog', 'P10', 'admin', 'admin-ingest', 'Run one source now'),
  R('POST', '/v1/admin/ingestion/runs', 'catalog', 'P10', 'admin', 'admin-ingest', 'Run several sources'),
  R('GET', '/v1/admin/ingestion/runs', 'catalog', 'P10', 'admin', 'admin-ingest', 'Runs (keyset)'),
  R('GET', '/v1/admin/ingestion/runs/{id}', 'catalog', 'P10', 'admin', 'admin-ingest', 'Run detail + checkpoint'),
  R('GET', '/v1/admin/review', 'catalog', 'P10', 'admin', 'admin-ingest', 'Review queue (keyset)'),
  R('GET', '/v1/admin/review/{id}', 'catalog', 'P10', 'admin', 'admin-ingest', 'Field-level diff'),
  R('POST', '/v1/admin/review/{id}:approve', 'catalog', 'P10', 'admin', 'admin-ingest', 'Promoter publishes a new version (idempotent)'),
  R('POST', '/v1/admin/review/{id}:reject', 'catalog', 'P10', 'admin', 'admin-ingest', 'Reject with reason'),
  // portfolio
  R('POST', '/v1/cards', 'portfolio', 'P11', 'token', 'cards', 'Add a held card (Idempotency-Key)'),
  R('GET', '/v1/cards', 'portfolio', 'P11', 'token', 'cards', 'Held cards + current cycle'),
  R('GET', '/v1/cards/{id}', 'portfolio', 'P11', 'token', 'cards', 'One card (ETag)'),
  R('PATCH', '/v1/cards/{id}', 'portfolio', 'P11', 'token', 'cards', 'Nickname, limit, statement day… (If-Match)'),
  R('DELETE', '/v1/cards/{id}', 'portfolio', 'P11', 'token', 'cards', 'Soft remove (history kept)'),
  R('GET', '/v1/cards/{id}/statements', 'portfolio', 'P11', 'token', 'cards', 'Statements (keyset)'),
  R('GET', '/v1/cards/{id}/transactions', 'portfolio', 'P11', 'token', 'cards', 'Transactions (keyset)'),
  R('POST', '/v1/cards/{id}/transactions', 'portfolio', 'P11', 'token', 'cards', 'Record a transaction → statement + reward'),
  R('GET', '/v1/cards/{id}/transactions/{txnId}', 'portfolio', 'P11', 'token', 'cards', 'One transaction'),
  R('POST', '/v1/emi-plans', 'portfolio', 'P11', 'token', 'emi', 'Convert to EMI: schedule computed once & stored'),
  R('GET', '/v1/emi-plans', 'portfolio', 'P11', 'token', 'emi', 'EMI plans (keyset)'),
  R('GET', '/v1/emi-plans/{id}', 'portfolio', 'P11', 'token', 'emi', 'One plan'),
  R('PATCH', '/v1/emi-plans/{id}', 'portfolio', 'P11', 'token', 'emi', 'description / status closed|foreclosed (If-Match)'),
  R('DELETE', '/v1/emi-plans/{id}', 'portfolio', 'P11', 'token', 'emi', 'Close'),
  R('GET', '/v1/emi-plans/{id}/schedule', 'portfolio', 'P11', 'token', 'emi', 'Instalments (keyset)'),
  R('GET', '/v1/links/suggestions', 'portfolio', 'P11', 'token', 'cards', 'Expense ↔ card-txn auto-match suggestions'),
  R('POST', '/v1/links/{id}:confirm', 'portfolio', 'P11', 'token', 'cards', 'Confirm a match'),
  R('POST', '/v1/links/{id}:reject', 'portfolio', 'P11', 'token', 'cards', 'Reject a match'),
  R('GET', '/v1/rewards/balance', 'portfolio', 'P11', 'token', 'cards', 'Points balance (FIFO by expiry)'),
  R('GET', '/v1/rewards/ledger', 'portfolio', 'P11', 'token', 'cards', 'Points ledger (keyset)'),
  R('POST', '/v1/rewards/ledger', 'portfolio', 'P11', 'token', 'cards', 'Redeem / adjust'),
  R('GET', '/v1/portfolio/report', 'portfolio', 'P11', 'token', 'cards', 'Rewards − fees − pro-rated annual fee'),
  R('POST', '/v1/statements/uploads', 'portfolio', 'P12', 'token', 'statements', 'Presigned PDF upload'),
  R('GET', '/v1/statements/uploads', 'portfolio', 'P12', 'token', 'statements', 'Uploads (keyset)'),
  R('GET', '/v1/statements/uploads/{id}', 'portfolio', 'P12', 'token', 'statements', 'Upload state'),
  R('GET', '/v1/statements/uploads/{id}/lines', 'portfolio', 'P12', 'token', 'statements', 'Parsed lines for review'),
  R('POST', '/v1/statements/uploads/{id}:submit', 'portfolio', 'P12', 'token', 'statements', 'Scan → ai-parser via Kafka'),
  R('POST', '/v1/statements/uploads/{id}:confirm', 'portfolio', 'P12', 'token', 'statements', 'Import lines → card transactions'),
  R('POST', '/v1/statements/uploads/{id}:discard', 'portfolio', 'P12', 'token', 'statements', 'Discard'),
  ...[['emi', 'EMI quote + schedule'], ['ledger', 'One cycle, lot by lot'], ['minimum-due', 'Four minimum-due formulas'], ['late-fee', 'Late-fee tiers + RBI SMA/NPA'],
    ['utilization', 'Utilisation + over-limit fee'], ['custom-fees', 'Configured fees + GST'], ['scenarios/full-payment', 'Pay in full'], ['scenarios/partial-payment', 'Pay part: interest + grace lost'],
    ['scenarios/no-payment', 'Ignore it: 30/60/90 days'], ['scenarios/minimum-payment', 'Minimum-only, cycle by cycle'], ['repayment-path', 'Avalanche vs snowball'], ['exit-paths', 'Pay full / targeted / minimum']]
    .map(([p, d]) => R('POST', '/v1/calc/' + p, 'portfolio', 'P12', 'token', 'calc', d, { cls: 'calc' })),
  // planning
  R('POST', '/v1/budgets', 'planning', 'P13', 'token', 'budgets', 'Create (Idempotency-Key)'),
  R('GET', '/v1/budgets', 'planning', 'P13', 'token', 'budgets', 'Budgets (keyset)'),
  R('GET', '/v1/budgets/status', 'planning', 'P13', 'token', 'budgets', 'Every current period: spent, projection, thresholds'),
  R('GET', '/v1/budgets/category-potential', 'planning', 'P13', 'token', 'budgets', 'PLANNED, NOT BUILT (A-75) — the budget→offer read-through', { gap: true }),
  R('GET', '/v1/budgets/{id}', 'planning', 'P13', 'token', 'budgets', 'One budget (ETag)'),
  R('GET', '/v1/budgets/{id}/history', 'planning', 'P13', 'token', 'budgets', 'Past periods (keyset)'),
  R('PATCH', '/v1/budgets/{id}', 'planning', 'P13', 'token', 'budgets', 'Edit (If-Match)'),
  R('DELETE', '/v1/budgets/{id}', 'planning', 'P13', 'token', 'budgets', 'End'),
  R('POST', '/v1/liabilities', 'planning', 'P13', 'token', 'budgets', 'Record a loan/EMI/BNPL (schedule stored)'),
  R('GET', '/v1/liabilities', 'planning', 'P13', 'token', 'budgets', 'Liabilities (keyset)'),
  R('GET', '/v1/liabilities/{id}', 'planning', 'P13', 'token', 'budgets', 'One liability'),
  R('GET', '/v1/liabilities/{id}/schedule', 'planning', 'P13', 'token', 'budgets', 'Instalments'),
  R('PATCH', '/v1/liabilities/{id}', 'planning', 'P13', 'token', 'budgets', 'lender / status (If-Match)'),
  R('DELETE', '/v1/liabilities/{id}', 'planning', 'P13', 'token', 'budgets', 'Close'),
  R('POST', '/v1/scenarios', 'planning', 'P13', 'token', 'budgets', 'Save a what-if (snapshot copied in)'),
  R('GET', '/v1/scenarios', 'planning', 'P13', 'token', 'budgets', 'Scenarios'),
  R('GET', '/v1/scenarios/{id}', 'planning', 'P13', 'token', 'budgets', 'One scenario'),
  R('PATCH', '/v1/scenarios/{id}', 'planning', 'P13', 'token', 'budgets', 'Rename / adjustments (If-Match)'),
  R('POST', '/v1/scenarios/{id}:evaluate', 'planning', 'P13', 'token', 'budgets', 'Apply adjustments to the snapshot'),
  // reco
  R('GET', '/v1/recommendations', 'reco', 'P14', 'token', 'reco', 'Ranked cards + not-recommended (cached, state-hash fresh)'),
  R('GET', '/v1/recommendations/explain/{cardProductId}', 'reco', 'P14', 'token', 'reco', 'The arithmetic, line by line'),
  R('GET', '/v1/recommendations/wallet', 'reco', 'P15', 'token', 'reco', 'Best 1/2/3-card wallet, exclusive routing, marginal value'),
  R('GET', '/v1/recommendations/best-card', 'reco', 'P15', 'token', 'reco', 'Which held card for this purchase (cap headroom, offers, milestone)'),
  R('POST', '/v1/recommendations/stream-ticket', 'reco', 'P15', 'token', 'reco', 'One-time 60 s SSE ticket'),
  R('GET', '/v1/recommendations/stream', 'reco', 'P15', 'public', 'reco', 'Server-Sent Events (ticket is the credential)'),
  // billing
  R('GET', '/v1/plans', 'billing', 'P16', 'public', 'billing', 'Plans with GST'),
  R('GET', '/v1/plans/{code}', 'billing', 'P16', 'public', 'billing', 'One plan'),
  R('POST', '/v1/subscriptions/checkout', 'billing', 'P16', 'token', 'billing', 'Mode B checkout (Idempotency-Key, step-up)'),
  R('GET', '/v1/subscriptions/me', 'billing', 'P16', 'token', 'billing', 'Live subscription'),
  R('POST', '/v1/subscriptions/me:cancel', 'billing', 'P16', 'token', 'billing', 'Cancel at period end'),
  R('GET', '/v1/entitlements/me', 'billing', 'P16', 'token', 'billing', 'Tier + feature map (60 s cache)'),
  R('GET', '/v1/invoices', 'billing', 'P16', 'token', 'billing', 'Invoices (keyset)'),
  R('GET', '/v1/invoices/{id}', 'billing', 'P16', 'token', 'billing', 'Invoice with CGST/SGST or IGST'),
  R('POST', '/v1/webhooks/{provider}', 'billing', 'P16', 'public', 'billing', 'Provider webhook (HMAC, replay-safe)'),
  // commerce
  R('GET', '/v1/deals', 'commerce', 'P17', 'public', 'deals', 'Ranked deals feed (0.8·(0.6·match+0.4·savings)+0.2·sponsor)'),
  R('GET', '/v1/deals/{id}', 'commerce', 'P17', 'public', 'deals', 'Deal + per-component saving at a spend'),
  R('GET', '/r/{token}', 'commerce', 'P17', 'public', 'deals', 'Signed click redirect → 302'),
  R('POST', '/v1/webhooks/affiliate/{network}', 'commerce', 'P17', 'public', 'deals', 'Affiliate postback (HMAC, idempotent)'),
  R('POST', '/v1/card-applications', 'commerce', 'P17', 'token', 'catalog', 'Apply for a card (Idempotency-Key)'),
  R('GET', '/v1/card-applications', 'commerce', 'P17', 'token', 'catalog', 'My applications'),
  R('GET', '/v1/card-applications/{id}', 'commerce', 'P17', 'token', 'catalog', 'One application + history'),
  R('POST', '/v1/card-applications/{id}:withdraw', 'commerce', 'P17', 'token', 'catalog', 'Withdraw an open application'),
  R('GET', '/v1/ads/serve', 'commerce', 'P17', 'public', 'deals', 'One ad or none (AD_FREE / NO_FILL / FREQUENCY_CAPPED)'),
  R('POST', '/v1/admin/commerce/deals', 'commerce', 'P17', 'admin', 'admin-commerce', 'Create a deal'),
  R('GET', '/v1/admin/commerce/deals', 'commerce', 'P17', 'admin', 'admin-commerce', 'All deals'),
  R('GET', '/v1/admin/commerce/deals/{id}', 'commerce', 'P17', 'admin', 'admin-commerce', 'One deal'),
  R('POST', '/v1/admin/commerce/deals/{id}:status', 'commerce', 'P17', 'admin', 'admin-commerce', 'draft→active⇄paused→ended (If-Match)'),
  R('GET', '/v1/admin/commerce/card-applications', 'commerce', 'P17', 'admin', 'admin-commerce', 'Open queue, oldest first'),
  R('GET', '/v1/admin/commerce/card-applications/{id}', 'commerce', 'P17', 'admin', 'admin-commerce', 'One application'),
  R('POST', '/v1/admin/commerce/card-applications/{id}:decide', 'commerce', 'P17', 'admin', 'admin-commerce', 'in_review | approved | rejected (If-Match)'),
  R('POST', '/v1/admin/commerce/campaigns', 'commerce', 'P17', 'admin', 'admin-commerce', 'New ad campaign'),
  R('GET', '/v1/admin/commerce/campaigns', 'commerce', 'P17', 'admin', 'admin-commerce', 'Campaigns'),
  R('GET', '/v1/admin/commerce/campaigns/{id}', 'commerce', 'P17', 'admin', 'admin-commerce', 'One campaign'),
  R('PUT', '/v1/admin/commerce/campaigns/{id}', 'commerce', 'P17', 'admin', 'admin-commerce', 'Edit (If-Match)'),
  R('DELETE', '/v1/admin/commerce/campaigns/{id}', 'commerce', 'P17', 'admin', 'admin-commerce', 'End campaign'),
  R('POST', '/v1/admin/commerce/campaigns/{id}/creatives', 'commerce', 'P17', 'admin', 'admin-commerce', 'Add a creative'),
  R('PUT', '/v1/admin/commerce/campaigns/{id}/creatives/{cid}', 'commerce', 'P17', 'admin', 'admin-commerce', 'Creative status/weight (If-Match)'),
  R('GET', '/v1/admin/commerce/stats', 'commerce', 'P17', 'admin', 'admin-commerce', 'Clicks, uniques, conversions, commission, impressions'),
  // audit
  R('GET', '/v1/notifications', 'audit', 'P18', 'token', 'inbox', 'In-app inbox (keyset) + unread'),
  R('POST', '/v1/notifications/{id}:read', 'audit', 'P18', 'token', 'inbox', 'Mark read'),
  R('GET', '/v1/notification-prefs', 'audit', 'P18', 'token', 'inbox', 'Channels + muted categories'),
  R('PATCH', '/v1/notification-prefs', 'audit', 'P18', 'token', 'inbox', 'Edit (If-Match, "0" before first save)'),
  R('POST', '/v1/privacy/export', 'audit', 'P18', 'token', 'inbox', 'DPDP export saga (step-up, Idempotency-Key)'),
  R('POST', '/v1/privacy/delete', 'audit', 'P18', 'token', 'inbox', 'DPDP delete saga + crypto-shred'),
  R('GET', '/v1/privacy/requests', 'audit', 'P18', 'token', 'inbox', 'My privacy requests'),
  R('GET', '/v1/privacy/requests/{id}', 'audit', 'P18', 'token', 'inbox', 'Phase, per-service answers, download URL'),
  R('GET', '/v1/admin/audit', 'audit', 'P18', 'admin', 'admin-audit', 'Hash-chained log (keyset on seq)'),
  R('POST', '/v1/admin/audit:verify-chain', 'audit', 'P18', 'admin', 'admin-audit', 'Walk + recompute the chain'),
  R('GET', '/v1/admin/audit/privacy-requests/{id}', 'audit', 'P18', 'admin', 'admin-audit', 'Full saga state'),
  R('POST', '/v1/admin/audit/privacy-requests/{id}:resume', 'audit', 'P18', 'admin', 'admin-audit', 'Resume a failed saga'),
  R('GET', '/v1/admin/activity/dashboard', 'audit', 'P18', 'admin', 'admin-audit', 'Per hour × service RED metrics'),
  // gateway own
  R('GET', '/actuator/health', 'gateway', 'P06', 'public', 'platform', 'Gateway health'),
];
const routeRx = (p) => new RegExp('^' + p.replace(/[.*+?^$()|[\]\\]/g, '\\$&').replace(/\\?\{(\w+)\\?\}/g, '(?<$1>[^/:?]+)') + '$');
REGISTRY.forEach((r) => { r.rx = routeRx(r.p.replace(/\{/g, '{').replace(/\}/g, '}')); r.key = r.m + ' ' + r.p; r.hits = 0; });

/* ---------- mock gateway + service router ---------- */
const HANDLERS = {};
const handle = (m, p, fn) => { HANDLERS[m + ' ' + p] = fn; };
const LOG = [];
const IDEM = new Map();
const RATE = { auth: 10, write: 120, read: 600, calc: 60 };
const rateLeft = {};
function problem(status, code, detail, errors) {
  const titles = { 400: 'Bad Request', 401: 'Unauthorized', 402: 'Payment Required', 403: 'Forbidden', 404: 'Not Found', 406: 'Not Acceptable', 409: 'Conflict', 410: 'Gone', 412: 'Precondition Failed', 413: 'Payload Too Large', 422: 'Unprocessable Content', 428: 'Precondition Required', 429: 'Too Many Requests' };
  const e = new Error(code); e.resp = { status, headers: { 'Content-Type': 'application/problem+json' }, body: { type: 'https://dexter.in/problems/' + code.toLowerCase().replace(/_/g, '-'), title: titles[status] || 'Error', status, code, detail, ...(errors ? { errors } : {}) } };
  return e;
}
const fail = (...a) => { throw problem(...a); };
const need = (cond, field, code, detail) => { if (!cond) fail(400, 'VALIDATION_FAILED', 'The request is invalid', [{ field, code, detail }]); };
function currentToken() { return state.tokens[state.persona] || null; }
function api(method, url, opt = {}) {
  if (state.live) return liveApi(method, url, opt);
  const t0 = performance.now();
  const [path, qs] = url.split('?');
  const query = Object.fromEntries(new URLSearchParams(qs || ''));
  const headers = { 'X-Request-Id': uuid7(), ...(opt.headers || {}) };
  const tok = opt.token === undefined ? currentToken() : opt.token;
  if (tok) headers.Authorization = 'Bearer ' + tok.access.slice(0, 18) + '…';
  const route = REGISTRY.find((r) => r.m === method && r.rx.test(path));
  let resp;
  try {
    if (!route) fail(404, 'NOT_FOUND', `No route for ${method} ${path}`);
    route.hits++;
    if (route.acc === 'internal') fail(404, 'NOT_FOUND', 'Not routed at the edge (service-to-service only)');
    if (opt.body && JSON.stringify(opt.body).length > 262144) fail(413, 'PAYLOAD_TOO_LARGE', 'Body over 256 KB');
    const cls = route.cls || (route.p.startsWith('/v1/auth') || route.p.startsWith('/v1/admin/auth') ? 'auth' : method === 'GET' ? 'read' : 'write');
    const minute = Math.floor(Date.now() / 60000); if (rateLeft._m !== minute) { for (const k in rateLeft) delete rateLeft[k]; rateLeft._m = minute; }
    const rk = (tok ? tok.sub : 'ip') + cls; rateLeft[rk] = (rateLeft[rk] ?? RATE[cls]) - 1;
    if (rateLeft[rk] < 0 && !opt.noRate) fail(429, 'RATE_LIMITED', `The ${cls} class allows ${RATE[cls]}/min`);
    if (route.acc === 'token' && (!tok || tok.kind !== 'user')) fail(401, 'UNAUTHENTICATED', tok && tok.kind === 'admin' ? 'An admin token cannot call a user route' : 'Sign in first');
    if (route.acc === 'admin' && (!tok || tok.kind !== 'admin')) fail(401, 'UNAUTHENTICATED', 'Admin token required');
    const h = HANDLERS[route.key];
    if (!h) fail(404, 'NOT_FOUND', route.gap ? route.d : 'No handler');
    const params = path.match(route.rx).groups || {};
    const req = { method, path, query, body: opt.body, headers, params, tok, acct: tok && tok.kind === 'user' ? tok.sub : null, admin: tok && tok.kind === 'admin' ? tok : null, route };
    // Idempotency-Key engine (MASTER_PLAN §5)
    const key = headers['Idempotency-Key'];
    if (key && method !== 'GET') {
      const fp = JSON.stringify([method, path, opt.body || null]);
      const k = (req.acct || 'anon') + '|' + key;
      const hit = IDEM.get(k);
      if (hit) {
        if (hit.fp !== fp) fail(422, 'IDEMPOTENCY_KEY_REUSED', 'Same key, different request');
        resp = { ...hit.resp, headers: { ...hit.resp.headers, 'Idempotent-Replayed': 'true' } };
      } else { resp = h(req); IDEM.set(k, { fp, resp }); }
    } else resp = h(req);
    resp = { status: 200, headers: {}, ...resp };
    resp.headers = { 'RateLimit': `"${cls}";r=${Math.max(0, rateLeft[rk])};t=60`, 'RateLimit-Policy': `"${cls}";q=${RATE[cls]};w=60`, 'X-Request-Id': headers['X-Request-Id'], ...resp.headers };
  } catch (e) {
    if (!e.resp) { console.error(e); resp = { status: 500, headers: {}, body: { status: 500, code: 'INTERNAL', detail: String(e.message) } }; } else resp = e.resp;
    resp.headers = { ...resp.headers, 'X-Request-Id': headers['X-Request-Id'] };
  }
  const entry = { at: nowIso(), method, url, svc: route ? route.svc : '—', reqHeaders: headers, body: opt.body, resp, ms: (performance.now() - t0).toFixed(1) };
  LOG.unshift(entry); if (LOG.length > 300) LOG.pop();
  renderLog();
  return resp;
}
const ok = (body, status = 200, headers = {}) => ({ status, body, headers });
const requireIdem = (req) => { if (!req.headers['Idempotency-Key']) fail(400, 'IDEMPOTENCY_KEY_REQUIRED', 'Send an Idempotency-Key header'); };
function checkIfMatch(req, entity) {
  const im = req.headers['If-Match'];
  if (im == null) fail(428, 'PRECONDITION_REQUIRED', 'Send If-Match with the ETag you read');
  if (im.replace(/"/g, '') !== String(entity.version)) fail(412, 'VERSION_CONFLICT', `Expected version ${entity.version}, got ${im}`);
}
const etag = (e) => ({ ETag: `"${e.version}"` });
/** keyset pager: list must be pre-sorted in the endpoint's order; key(row) is the sort key tuple */
function page(list, req, key = (r) => [r.id], filterSig = '') {
  const lim = req.query.limit == null ? 25 : Number(req.query.limit);
  if (!(lim >= 1 && lim <= 100)) fail(400, 'LIMIT_INVALID', 'limit must be 1..100');
  let start = 0;
  const f = btoa(filterSig || JSON.stringify(Object.fromEntries(Object.entries(req.query).filter(([k]) => k !== 'limit' && k !== 'cursor')))).slice(0, 12);
  if (req.query.cursor) {
    let c; try { const [p, sig] = req.query.cursor.split('.'); if (sig !== btoa(p).slice(3, 11)) throw 0; c = JSON.parse(atob(p)); } catch { fail(400, 'CURSOR_INVALID', 'The cursor is not one this server issued'); }
    if (c.f !== f) fail(400, 'CURSOR_FILTER_MISMATCH', 'Filters changed since this cursor was issued');
    const ks = JSON.stringify(c.k); start = list.findIndex((r) => JSON.stringify(key(r)) === ks) + 1;
    if (start === 0) { // row vanished: first row strictly after the key (keyset semantics)
      start = list.findIndex((r) => JSON.stringify(key(r)) > ks); if (start < 0) start = list.length; }
  }
  const slice = list.slice(start, start + lim + 1);
  const hasMore = slice.length > lim; const data = slice.slice(0, lim);
  let nextCursor = null;
  if (hasMore) { const p = btoa(JSON.stringify({ v: 1, k: key(data[data.length - 1]), f, kid: 'c1' })); nextCursor = p + '.' + btoa(p).slice(3, 11); }
  return { data, page: { limit: lim, hasMore, nextCursor } };
}

/* ---------- event bus: outbox → Kafka → inbox consumers ---------- */
const EVENTS = []; const CONSUMERS = {};
const consume = (prefix, name, fn) => (CONSUMERS[prefix] = CONSUMERS[prefix] || []).push({ name, fn });
function emit(topic, type, accountId, data) {
  const ev = { id: uuid7(), topic, type, accountId, time: nowIso(), data, consumed: [] };
  EVENTS.unshift(ev); if (EVENTS.length > 400) EVENTS.pop();
  for (const [pre, list] of Object.entries(CONSUMERS)) if (type.startsWith(pre)) for (const c of list) { const r = c.fn(ev); ev.consumed.push({ consumer: c.name, outcome: r || 'APPLIED' }); }
  if (window.onEvent) window.onEvent(ev);
  return ev;
}
