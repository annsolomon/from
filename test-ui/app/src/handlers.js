/* ================= identity ================= */
const acctView = (a) => ({ accountId: a.accountId, email: a.email, displayName: a.displayName, phone: a.phone, tz: a.tz, tier: a.tier, status: a.status, emailVerified: a.emailVerified, mfaEnrolled: a.mfa.enrolled, createdAt: a.createdAt, version: a.version });
const findAcctByEmail = (e) => Object.values(DB.identity.accounts).find((a) => a.email.toLowerCase() === String(e || '').toLowerCase());
const pendingMfa = {};
handle('POST', '/v1/auth/register', (q) => {
  requireIdem(q); const b = q.body || {};
  need(/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(b.email || ''), 'email', 'INVALID', 'An e-mail address');
  need((b.password || '').length >= 10, 'password', 'TOO_SHORT', 'At least 10 characters');
  if (findAcctByEmail(b.email)) return ok({ status: 'pending_verification' }, 202); // no enumeration
  const id = uuid7(); DB.identity.accounts[id] = { accountId: id, email: b.email, displayName: b.displayName || b.email.split('@')[0], phone: null, tz: b.tz || 'Asia/Kolkata', tier: 'FREE', status: 'pending_verification', emailVerified: false, mfa: { enrolled: false, recoveryLeft: 0 }, password: b.password, createdAt: nowIso(), version: 0, failed: 0 };
  const t = 'ev_' + uuid7().slice(0, 8); DB.identity.verifyTokens[t] = id;
  emit('dexter.identity.account.v1', 'identity.account.registered', id, { email: b.email });
  return ok({ status: 'pending_verification', _devVerifyToken: t }, 202);
});
handle('POST', '/v1/auth/verify-email', (q) => { const id = DB.identity.verifyTokens[(q.body || {}).token]; if (!id) fail(400, 'TOKEN_INVALID', 'Unknown or used token'); const a = DB.identity.accounts[id]; a.emailVerified = true; a.status = 'active'; a.version++; delete DB.identity.verifyTokens[q.body.token]; return ok(acctView(a)); });
handle('POST', '/v1/auth/login', (q) => {
  const b = q.body || {}; const a = findAcctByEmail(b.email);
  if (a && a.lockedUntil && a.lockedUntil > nowIso()) fail(401, 'ACCOUNT_LOCKED', 'Too many failed attempts; try again in 15 minutes');
  if (!a || a.password !== b.password) { if (a) { a.failed++; if (a.failed >= 5) a.lockedUntil = new Date(now().getTime() + 900000).toISOString(); } fail(401, 'INVALID_CREDENTIALS', 'E-mail or password is wrong'); }
  if (a.status !== 'active') fail(403, 'ACCOUNT_NOT_ACTIVE', a.status);
  a.failed = 0;
  if (a.mfa.enrolled) { const mt = 'mfa_' + uuid7().slice(0, 12); pendingMfa[mt] = { acct: a.accountId, device: b.deviceLabel }; return ok({ mfaRequired: true, mfaToken: mt, expiresIn: 300 }); }
  const s = newSession(a.accountId, b.deviceLabel || 'Web'); const t = issueTokens('user', a.accountId, { sessionId: s.id, tier: a.tier });
  const key = Object.entries({ asha: ASHA, ravi: RAVI }).find(([, v]) => v === a.accountId); if (key) state.tokens[key[0]] = t;
  audit('user', a.accountId, 'auth.login', 'session', s.id); emit('dexter.identity.security.v1', 'identity.security.new-login', a.accountId, { title: 'New sign-in', body: (b.deviceLabel || 'Web') + ' just signed in' });
  return ok({ accessToken: t.access, tokenType: 'Bearer', expiresIn: 900, refreshToken: t.refresh, refreshExpiresIn: 2592000 });
});
handle('POST', '/v1/auth/mfa/verify', (q) => {
  const b = q.body || {}; const p = pendingMfa[b.mfaToken]; if (!p) fail(401, 'MFA_TOKEN_INVALID', 'Expired or used challenge');
  need(/^\d{6}$/.test(b.code || '') || (b.recoveryCode || '').length >= 8, 'code', 'INVALID', '6 digits or a recovery code');
  delete pendingMfa[b.mfaToken]; const a = DB.identity.accounts[p.acct]; if (b.recoveryCode) a.mfa.recoveryLeft = Math.max(0, a.mfa.recoveryLeft - 1);
  const s = newSession(a.accountId, p.device || 'Web'); const t = issueTokens('user', a.accountId, { sessionId: s.id });
  const key = Object.entries({ asha: ASHA, ravi: RAVI }).find(([, v]) => v === a.accountId); if (key) state.tokens[key[0]] = t;
  return ok({ accessToken: t.access, tokenType: 'Bearer', expiresIn: 900, refreshToken: t.refresh, refreshExpiresIn: 2592000 });
});
handle('POST', '/v1/auth/step-up', (q) => { const a = DB.identity.accounts[q.acct]; const b = q.body || {}; if (b.password !== a.password && !(a.mfa.enrolled && /^\d{6}$/.test(b.code || ''))) fail(401, 'STEP_UP_FAILED', 'Password (or TOTP) did not match'); q.tok.stepUpAt = nowIso(); return ok({ accessToken: q.tok.access, expiresIn: 600, acr: 'step-up', authTime: q.tok.stepUpAt }); });
handle('POST', '/v1/auth/token/refresh', (q) => {
  const rt = (q.body || {}).refreshToken; const e = Object.entries(state.tokens).find(([, t]) => t.refresh === rt);
  if (!e) fail(401, 'REFRESH_TOKEN_INVALID', 'Unknown, rotated or revoked (reuse revokes the family)');
  const [k, old] = e; const t = issueTokens(old.kind, old.sub, { sessionId: old.sessionId, roles: old.roles, perms: old.perms }); state.tokens[k] = t;
  return ok({ accessToken: t.access, tokenType: 'Bearer', expiresIn: 900, refreshToken: t.refresh, refreshExpiresIn: 2592000, tier: (DB.identity.accounts[old.sub] || {}).tier });
});
handle('POST', '/v1/auth/logout', (q) => { const s = DB.identity.sessions.find((x) => x.id === q.tok.sessionId); if (s) s.revokedAt = nowIso(); return ok(null, 204); });
handle('POST', '/v1/auth/logout-all', (q) => { let n = 0; DB.identity.sessions.filter((s) => s.accountId === q.acct && !s.revokedAt).forEach((s) => { s.revokedAt = nowIso(); n++; }); return ok({ revoked: n }); });
handle('POST', '/v1/auth/password/forgot', (q) => { const a = findAcctByEmail((q.body || {}).email); let dev = null; if (a) { dev = 'pr_' + uuid7().slice(0, 10); DB.identity.resets[dev] = a.accountId; } return ok({ status: 'accepted', ...(dev ? { _devResetToken: dev } : {}) }, 202); });
handle('POST', '/v1/auth/password/reset', (q) => { const b = q.body || {}; const id = DB.identity.resets[b.token]; if (!id) fail(400, 'TOKEN_INVALID', 'Unknown or used reset token'); need((b.newPassword || '').length >= 10, 'newPassword', 'TOO_SHORT', 'At least 10 characters'); DB.identity.accounts[id].password = b.newPassword; delete DB.identity.resets[b.token]; DB.identity.sessions.filter((s) => s.accountId === id).forEach((s) => (s.revokedAt = s.revokedAt || nowIso())); return ok(null, 204); });
handle('POST', '/v1/auth/password/change', (q) => { const a = DB.identity.accounts[q.acct], b = q.body || {}; if (b.currentPassword !== a.password) fail(400, 'VALIDATION_FAILED', 'Current password is wrong', [{ field: 'currentPassword', code: 'MISMATCH' }]); need((b.newPassword || '').length >= 10, 'newPassword', 'TOO_SHORT', 'At least 10 characters'); a.password = b.newPassword; emit('dexter.identity.security.v1', 'identity.security.password-changed', q.acct, { title: 'Password changed', body: 'If this wasn\'t you, reset it now.' }); return ok(null, 204); });
handle('GET', '/v1/me', (q) => { const a = DB.identity.accounts[q.acct]; return ok(acctView(a), 200, etag(a)); });
handle('PATCH', '/v1/me', (q) => { const a = DB.identity.accounts[q.acct]; checkIfMatch(q, a); const b = q.body || {}; for (const k of Object.keys(b)) need(['displayName', 'tz', 'phone'].includes(k), k, 'UNKNOWN_FIELD', 'Not patchable'); Object.assign(a, b); a.version++; return ok(acctView(a), 200, etag(a)); });
handle('GET', '/v1/me/sessions', (q) => ok({ data: DB.identity.sessions.filter((s) => s.accountId === q.acct && !s.revokedAt).map((s) => ({ ...s, current: s.id === q.tok.sessionId })) }));
handle('DELETE', '/v1/me/sessions/{id}', (q) => { const s = DB.identity.sessions.find((x) => x.id === q.params.id && x.accountId === q.acct); if (!s) fail(404, 'NOT_FOUND', 'Session'); s.revokedAt = nowIso(); return ok(null, 204); });
handle('GET', '/v1/me/mfa', (q) => { const a = DB.identity.accounts[q.acct]; return ok({ totpEnrolled: a.mfa.enrolled, pending: !!a.mfa.pending, recoveryCodesLeft: a.mfa.recoveryLeft }); });
handle('POST', '/v1/me/mfa/totp/enroll', (q) => { const a = DB.identity.accounts[q.acct]; if (a.mfa.enrolled) fail(409, 'MFA_ALREADY_ENROLLED', 'Disable first'); const secret = 'JBSWY3DPEHPK3PXP' + uuid7().slice(0, 4).toUpperCase(); a.mfa.pending = secret; return ok({ secret, otpauthUri: `otpauth://totp/Dexter:${a.email}?secret=${secret}&issuer=Dexter&digits=6&period=30`, digits: 6, period: 30 }); });
handle('POST', '/v1/me/mfa/totp/confirm', (q) => { const a = DB.identity.accounts[q.acct]; if (!a.mfa.pending) fail(409, 'MFA_NOT_PENDING', 'Enroll first'); need(/^\d{6}$/.test((q.body || {}).code || ''), 'code', 'INVALID', '6 digits'); a.mfa = { enrolled: true, pending: null, recoveryLeft: 10 }; emit('dexter.identity.security.v1', 'identity.security.mfa-enabled', q.acct, { title: 'Two-step verification on', body: 'TOTP added' }); return ok({ recoveryCodes: Array.from({ length: 10 }, () => uuid7().slice(-12).replace(/(.{4})/g, '$1-').slice(0, 14)) }); });
handle('DELETE', '/v1/me/mfa/totp', (q) => { if (Date.parse(nowIso()) - Date.parse(q.tok.stepUpAt) > 600000) fail(401, 'STEP_UP_REQUIRED', 'Re-authenticate within 10 minutes'); const a = DB.identity.accounts[q.acct]; a.mfa = { enrolled: false, pending: null, recoveryLeft: 0 }; return ok(null, 204); });
handle('POST', '/v1/me/mfa/recovery-codes', (q) => { const a = DB.identity.accounts[q.acct]; if (!a.mfa.enrolled) fail(409, 'MFA_NOT_ENROLLED', 'Enable TOTP first'); a.mfa.recoveryLeft = 10; return ok({ recoveryCodes: Array.from({ length: 10 }, () => uuid7().slice(-12)) }); });
handle('GET', '/.well-known/jwks.json', () => ok({ keys: DB.identity.keys.map((k) => ({ kty: 'OKP', crv: 'Ed25519', kid: k.kid, use: 'sig', alg: 'EdDSA', x: hashSync(k.kid).slice(0, 43) })) }, 200, { 'Cache-Control': 'public, max-age=300' }));
handle('GET', '/_dev/p04/keys', () => ok({ active: DB.identity.keys[0], published: DB.identity.keys, loadedAt: nowIso() }));
handle('POST', '/_dev/p04/keys/rotate', () => { DB.identity.keys.unshift({ kid: 'k-' + nowIso().slice(0, 16), alg: 'EdDSA', status: 'active', createdAt: nowIso() }); DB.identity.keys[1].status = 'published'; return ok({ active: DB.identity.keys[0] }); });
handle('GET', '/actuator/health', () => ok({ status: 'UP', components: { redis: { status: 'UP' }, jwks: { status: 'UP' }, circuitBreakers: { status: 'UP', details: Object.fromEntries(['identity', 'ledger', 'catalog', 'portfolio', 'planning', 'reco', 'billing', 'commerce', 'audit'].map((s) => [s, 'CLOSED'])) } } }));
// admin identity
const pendingAdmin = {};
const adminView = (a) => ({ id: a.id, email: a.email, displayName: a.displayName, roles: a.roles, permissions: [...new Set(a.roles.flatMap((r) => PERMS[r] || []))], status: a.status, breakGlassEligible: a.breakGlassEligible, mfaEnrolled: a.mfaEnrolled, createdAt: a.createdAt, version: a.version });
handle('POST', '/v1/admin/auth/login', (q) => { const a = DB.identity.admins.find((x) => x.email === (q.body || {}).email); if (!a || (q.body || {}).password !== 'Admin@12345') fail(401, 'INVALID_CREDENTIALS', 'E-mail or password is wrong'); const mt = 'amfa_' + uuid7().slice(0, 10); pendingAdmin[mt] = a.id; return ok({ mfaToken: mt, setup: !a.mfaEnrolled, expiresIn: 300 }); });
handle('POST', '/v1/admin/auth/mfa/setup', (q) => { if (!pendingAdmin[(q.body || {}).mfaToken]) fail(401, 'MFA_TOKEN_INVALID', 'Expired'); return ok({ secret: 'ADMINJBSWY3DPEHPK', otpauthUri: 'otpauth://totp/Dexter-Admin?secret=ADMINJBSWY3DPEHPK', digits: 6, period: 30 }); });
handle('POST', '/v1/admin/auth/mfa/verify', (q) => { const id = pendingAdmin[(q.body || {}).mfaToken]; if (!id) fail(401, 'MFA_TOKEN_INVALID', 'Expired'); need(/^\d{6}$/.test(q.body.code || ''), 'code', 'INVALID', '6 digits'); delete pendingAdmin[q.body.mfaToken]; const a = DB.identity.admins.find((x) => x.id === id); a.mfaEnrolled = true; const t = issueTokens('admin', id, { roles: a.roles, perms: adminView(a).permissions }); state.tokens.admin = t; audit('admin', id, 'admin.login', 'admin', id); return ok({ accessToken: t.access, tokenType: 'Bearer', expiresIn: 600, refreshToken: t.refresh }); });
handle('POST', '/v1/admin/auth/token/refresh', (q) => HANDLERS['POST /v1/auth/token/refresh'](q));
handle('POST', '/v1/admin/auth/logout', () => ok(null, 204));
handle('POST', '/v1/admin/auth/invite/accept', (q) => { const inv = DB.identity.invites.find((i) => i.token === (q.body || {}).token && !i.acceptedAt); if (!inv) fail(400, 'TOKEN_INVALID', 'Unknown or used invite'); need((q.body.password || '').length >= 12, 'password', 'TOO_SHORT', 'At least 12 characters'); inv.acceptedAt = nowIso(); const a = DB.identity.admins.find((x) => x.id === inv.adminId); a.status = 'active'; return ok(adminView(a)); });
handle('GET', '/v1/admin/me', (q) => ok({ ...adminView(DB.identity.admins.find((a) => a.id === q.admin.sub)), elevated: DB.identity.elevations.find((e) => !e.endedAt && e.adminId === q.admin.sub) || null }));
handle('POST', '/v1/admin/elevations', (q) => { const a = DB.identity.admins.find((x) => x.id === q.admin.sub); if (!a.breakGlassEligible) fail(403, 'NOT_BREAK_GLASS_ELIGIBLE', 'This admin cannot elevate'); need(((q.body || {}).reason || '').length >= 10, 'reason', 'TOO_SHORT', 'Explain why (≥10 chars)'); need(/^\d{6}$/.test(q.body.code || ''), 'code', 'INVALID', 'TOTP'); const e = { elevationId: uuid7(), adminId: a.id, reason: q.body.reason, expiresAt: new Date(now().getTime() + 1800000).toISOString(), endedAt: null }; DB.identity.elevations.push(e); audit('admin', a.id, 'admin.elevation.started', 'admin', a.id, { reason: q.body.reason }); return ok({ elevationId: e.elevationId, expiresAt: e.expiresAt, accessToken: q.admin.access, expiresIn: 1800 }, 201); });
handle('DELETE', '/v1/admin/elevations/current', (q) => { const e = DB.identity.elevations.find((x) => !x.endedAt && x.adminId === q.admin.sub); if (e) { e.endedAt = nowIso(); audit('admin', q.admin.sub, 'admin.elevation.ended', 'admin', q.admin.sub); } return ok({ ended: !!e, accessToken: q.admin.access, expiresIn: 600 }); });
handle('GET', '/v1/admin/admins', (q) => ok(page([...DB.identity.admins].sort((a, b) => (a.createdAt < b.createdAt ? 1 : -1)).map(adminView), q, (r) => [r.createdAt, r.id])));
handle('GET', '/v1/admin/admins/{id}', (q) => { const a = DB.identity.admins.find((x) => x.id === q.params.id); if (!a) fail(404, 'NOT_FOUND', 'Admin'); return ok(adminView(a), 200, etag(a)); });
handle('POST', '/v1/admin/admins', (q) => { const b = q.body || {}; need(/@/.test(b.email || ''), 'email', 'INVALID', 'E-mail'); need((b.roles || []).every((r) => PERMS[r]), 'roles', 'INVALID', 'SUPER_ADMIN | SUPPORT | CATALOG_EDITOR'); if (DB.identity.admins.some((a) => a.email === b.email)) fail(409, 'ADMIN_EXISTS', 'Already invited'); const a = { id: uuid7(), email: b.email, displayName: b.displayName || b.email, roles: b.roles || ['SUPPORT'], status: 'invited', breakGlassEligible: !!b.breakGlassEligible, mfaEnrolled: false, createdAt: nowIso(), version: 0 }; DB.identity.admins.push(a); const tok = 'inv_' + uuid7().slice(0, 10); DB.identity.invites.push({ token: tok, adminId: a.id, acceptedAt: null }); audit('admin', q.admin.sub, 'admin.invited', 'admin', a.id); return ok({ ...adminView(a), _devInviteToken: tok }, 201); });
handle('PATCH', '/v1/admin/admins/{id}', (q) => { const a = DB.identity.admins.find((x) => x.id === q.params.id); if (!a) fail(404, 'NOT_FOUND', 'Admin'); checkIfMatch(q, a); const b = q.body || {}; if (b.roles) a.roles = b.roles; if (b.status) a.status = b.status; a.version++; audit('admin', q.admin.sub, 'admin.updated', 'admin', a.id, b); return ok(adminView(a), 200, etag(a)); });
handle('GET', '/v1/admin/accounts', (q) => { const a = findAcctByEmail(q.query.email); audit('admin', q.admin.sub, 'account.lookup', 'account', a ? a.accountId : null, { email: q.query.email }); return ok({ data: a ? [acctView(a)] : [] }); });

/* ================= ledger ================= */
const expView = (e) => ({ id: e.id, occurredAt: e.occurredAt, amountPaise: e.amountPaise, currency: 'INR', merchant: e.merchantDisplay, merchantId: e.merchantId, categoryPath: e.categoryPath, categorySource: e.categorySource, resolutionConfidence: e.resolutionConfidence, paymentMethod: e.paymentMethod, userCardId: e.userCardId, note: e.note, tags: e.tags, clientRef: e.clientRef, source: e.source, createdAt: e.createdAt, updatedAt: e.updatedAt, version: e.version });
const myExpenses = (acct) => DB.ledger.expenses.filter((e) => e.accountId === acct);
const expSort = (a, b) => (a.occurredAt < b.occurredAt ? 1 : a.occurredAt > b.occurredAt ? -1 : a.id < b.id ? 1 : -1);
function validateExpense(b, partial = false) {
  if (!partial || b.amountPaise !== undefined) need(Number.isInteger(b.amountPaise) && b.amountPaise > 0, 'amountPaise', 'INVALID', 'A positive whole number of paise');
  if (!partial || b.paymentMethod !== undefined) need(['card', 'upi', 'cash', 'netbanking', 'wallet', 'other'].includes(b.paymentMethod || 'upi'), 'paymentMethod', 'INVALID', 'card|upi|cash|netbanking|wallet|other');
  if (b.note) need(b.note.length <= 500, 'note', 'TOO_LONG', '≤ 500');
  if (b.tags) need(b.tags.length <= 10, 'tags', 'TOO_MANY', '≤ 10');
  if (b.categoryPath) need(/^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*){0,3}$/.test(b.categoryPath), 'categoryPath', 'INVALID', 'An ltree path, ≤4 labels');
}
function createExpense(acct, b, source = 'manual') {
  validateExpense(b);
  if (b.clientRef) { const dup = myExpenses(acct).find((e) => e.clientRef === b.clientRef); if (dup) return { dup }; }
  let res = { merchantId: b.merchantId || null, merchant: b.merchant, category: b.categoryPath, confidence: 1000, method: 'user' };
  if (!b.categoryPath) res = resolveMerchant(acct, b.merchant || '');
  const e = { id: uuid7(), accountId: acct, occurredAt: b.occurredAt || nowIso(), amountPaise: b.amountPaise, currency: 'INR', merchantDisplay: res.merchant || b.merchant || 'Unknown', merchantId: res.merchantId, categoryPath: b.categoryPath || res.category || 'general',
    categorySource: b.categoryPath ? 'user' : res.method === 'rule' ? 'rule' : 'resolver', resolutionConfidence: b.categoryPath ? 1000 : res.confidence, paymentMethod: b.paymentMethod || 'upi', userCardId: b.userCardId || null, note: b.note || null, tags: b.tags || [], clientRef: b.clientRef || null, source, createdAt: nowIso(), updatedAt: nowIso(), version: 0, deletedAt: null };
  DB.ledger.expenses.push(e); writeExpense(acct, null, expState(e), 'ledger.expense.created', e);
  return { e };
}
handle('POST', '/v1/expenses', (q) => { requireIdem(q); const r = createExpense(q.acct, q.body || {}); if (r.dup) return ok(expView(r.dup), 200, etag(r.dup)); return ok(expView(r.e), 201, { Location: '/v1/expenses/' + r.e.id, ...etag(r.e) }); });
function filterExpenses(q) {
  const f = q.query; let l = myExpenses(q.acct).filter((e) => !e.deletedAt);
  if (f.from) l = l.filter((e) => istDay(e.occurredAt) >= f.from); if (f.to) l = l.filter((e) => istDay(e.occurredAt) <= f.to);
  if (f.category) l = l.filter((e) => isUnder(e.categoryPath, f.category)); // category_path <@ :category
  if (f.paymentMethod) l = l.filter((e) => e.paymentMethod === f.paymentMethod); if (f.cardId) l = l.filter((e) => e.userCardId === f.cardId);
  if (f.merchantId) l = l.filter((e) => e.merchantId === f.merchantId); if (f.minPaise) l = l.filter((e) => e.amountPaise >= +f.minPaise); if (f.maxPaise) l = l.filter((e) => e.amountPaise <= +f.maxPaise);
  return l.sort(expSort);
}
handle('GET', '/v1/expenses', (q) => { const p = page(filterExpenses(q), q, (r) => [r.occurredAt, r.id]); return ok({ data: p.data.map(expView), page: p.page }); });
handle('GET', '/v1/expenses/search', (q) => {
  const n = normalizeMerchant(q.query.q || ''); need(n.length >= 2, 'q', 'TOO_SHORT', '≥ 2 characters');
  const from = q.query.from || addDays(today(), -365);
  const l = myExpenses(q.acct).filter((e) => !e.deletedAt && istDay(e.occurredAt) >= from).map((e) => ({ e, s: Math.max(similarity(n, normalizeMerchant(e.merchantDisplay)), normalizeMerchant(e.merchantDisplay).includes(n) ? 1 : 0, e.note && e.note.toLowerCase().includes(n) ? 0.9 : 0) })).filter((x) => x.s >= 0.3).map((x) => x.e).sort(expSort);
  const p = page(l, q, (r) => [r.occurredAt, r.id]); return ok({ data: p.data.map(expView), page: p.page });
});
handle('GET', '/v1/expenses/daily-totals', (q) => { const out = []; for (const [k, v] of DB.ledger.daily) { const [a, d, r] = k.split('|'); if (a === q.acct && (!q.query.from || d >= q.query.from) && (!q.query.to || d <= q.query.to) && v.count) out.push({ day: d, categoryRoot: r, totalPaise: v.total, txnCount: v.count }); } out.sort((a, b) => (a.day < b.day ? -1 : 1)); return ok({ data: out }); });
const getExp = (q) => { const e = myExpenses(q.acct).find((x) => x.id === q.params.id); if (!e) fail(404, 'NOT_FOUND', 'Expense'); return e; };
handle('GET', '/v1/expenses/{id}', (q) => { const e = getExp(q); if (e.deletedAt) fail(404, 'NOT_FOUND', 'Expense (deleted)'); return ok(expView(e), 200, etag(e)); });
handle('PATCH', '/v1/expenses/{id}', (q) => {
  const e = getExp(q); checkIfMatch(q, e); const b = q.body || {}; validateExpense(b, true);
  for (const k of Object.keys(b)) need(['amountPaise', 'occurredAt', 'merchant', 'categoryPath', 'paymentMethod', 'userCardId', 'note', 'tags'].includes(k), k, 'UNKNOWN_FIELD', 'Not patchable');
  const before = expState(e);
  if ('merchant' in b) { e.merchantDisplay = b.merchant; if (!('categoryPath' in b)) { const r = resolveMerchant(q.acct, b.merchant); e.merchantId = r.merchantId; e.categoryPath = r.category; e.categorySource = 'resolver'; e.resolutionConfidence = r.confidence; } }
  for (const k of ['amountPaise', 'occurredAt', 'paymentMethod', 'userCardId', 'note', 'tags']) if (k in b) e[k] = b[k];
  if ('categoryPath' in b) { e.categoryPath = b.categoryPath; e.categorySource = 'user'; e.resolutionConfidence = 1000; }
  e.version++; e.updatedAt = nowIso(); writeExpense(q.acct, before, expState(e), 'ledger.expense.updated', e);
  return ok(expView(e), 200, etag(e));
});
handle('DELETE', '/v1/expenses/{id}', (q) => { const e = getExp(q); if (e.deletedAt) return ok(null, 204); const before = expState(e); e.deletedAt = nowIso(); e.version++; writeExpense(q.acct, before, null, 'ledger.expense.deleted', e); return ok(null, 204); });
handle('POST', '/v1/expenses/{id}:restore', (q) => { const e = getExp(q); if (!e.deletedAt) return ok(expView(e), 200, etag(e)); e.deletedAt = null; e.version++; writeExpense(q.acct, null, expState(e), 'ledger.expense.restored', e); return ok(expView(e), 200, etag(e)); });
handle('POST', '/v1/expenses:bulk', (q) => {
  requireIdem(q); const items = (q.body || {}).items || []; need(items.length >= 1 && items.length <= 500, 'items', 'SIZE', '1..500 items');
  let created = 0, duplicates = 0, invalid = 0; const results = items.map((it, i) => { try { const r = createExpense(q.acct, it); if (r.dup) { duplicates++; return { index: i, status: 'duplicate', id: r.dup.id, errors: [] }; } created++; return { index: i, status: 'created', id: r.e.id, errors: [] }; } catch (e) { invalid++; return { index: i, status: 'invalid', id: null, errors: (e.resp && e.resp.body.errors) || [] }; } });
  return ok({ created, duplicates, invalid, results }, 207);
});
handle('GET', '/v1/categories', (q) => ok({ data: DB.ledger.categories.filter((c) => !c.accountId || c.accountId === q.acct).sort((a, b) => (a.path < b.path ? -1 : 1)) }));
handle('POST', '/v1/categories', (q) => { const b = q.body || {}; need(DB.ledger.categories.some((c) => c.path === b.parentPath && c.system), 'parentPath', 'UNKNOWN', 'A system category'); const label = String(b.name || '').toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_|_$/g, ''); need(label && /^[a-z]/.test(label), 'name', 'INVALID', 'Starts with a letter'); const path = b.parentPath + '.' + label; need(path.split('.').length <= 4, 'parentPath', 'TOO_DEEP', '≤ 4 labels'); if (DB.ledger.categories.some((c) => c.path === path && (c.accountId === q.acct || !c.accountId))) fail(409, 'CATEGORY_EXISTS', path); const c = { id: uuid7(), accountId: q.acct, path, name: b.name, icon: b.icon || 'tag', system: false }; DB.ledger.categories.push(c); return ok(c, 201); });
handle('GET', '/v1/merchants/autocomplete', (q) => { need((q.query.q || '').length >= 2, 'q', 'TOO_SHORT', '≥ 2 characters'); return ok({ data: autocomplete(q.query.q) }, 200, { 'Cache-Control': 'private, max-age=60' }); });
handle('POST', '/v1/merchants/resolve', (q) => ok(resolveMerchant(q.acct, (q.body || {}).merchant)));
handle('GET', '/v1/rules', (q) => ok({ data: DB.ledger.rules.filter((r) => r.accountId === q.acct) }));
handle('POST', '/v1/rules', (q) => { const b = q.body || {}; need(['merchant_id', 'contains'].includes(b.matchType), 'matchType', 'INVALID', 'merchant_id | contains'); need((b.pattern || '').length >= 2, 'pattern', 'TOO_SHORT', '≥2'); need(DB.ledger.categories.some((c) => c.path === b.categoryPath), 'categoryPath', 'UNKNOWN', 'An existing category'); const r = { id: uuid7(), accountId: q.acct, matchType: b.matchType, pattern: b.pattern, categoryPath: b.categoryPath, priority: b.priority ?? 100, createdAt: nowIso() }; DB.ledger.rules.push(r); return ok(r, 201); });
handle('DELETE', '/v1/rules/{id}', (q) => { const i = DB.ledger.rules.findIndex((r) => r.id === q.params.id && r.accountId === q.acct); if (i < 0) fail(404, 'NOT_FOUND', 'Rule'); DB.ledger.rules.splice(i, 1); return ok(null, 204); });
handle('POST', '/v1/imports', (q) => { const b = q.body || {}; need(/\.csv$/i.test(b.fileName || ''), 'fileName', 'INVALID', 'A .csv file'); need((b.sizeBytes || 0) <= 5 * 1048576, 'sizeBytes', 'TOO_LARGE', '≤ 5 MB'); const j = { id: uuid7(), accountId: q.acct, status: 'awaiting_upload', fileName: b.fileName, total: 0, ok: 0, duplicates: 0, failed: 0, errorCode: null, errorDetail: null, createdAt: nowIso(), startedAt: null, finishedAt: null, version: 0, upload: { url: 'https://objects.dexter.local/imports/' + uuid7() + '?X-Amz-Signature=…', method: 'PUT', expiresAt: new Date(now().getTime() + 900000).toISOString() }, csvHeader: 'occurredAt,amount,merchant,category,paymentMethod,clientRef', errors: [], _rows: b._rows || null }; DB.ledger.imports.push(j); return ok(j, 201); });
handle('POST', '/v1/imports/{id}:start', (q) => {
  const j = DB.ledger.imports.find((x) => x.id === q.params.id && x.accountId === q.acct); if (!j) fail(404, 'NOT_FOUND', 'Import'); if (j.status !== 'awaiting_upload') fail(409, 'IMPORT_NOT_STARTABLE', j.status);
  j.status = 'running'; j.startedAt = nowIso(); const rows = j._rows || [];
  rows.forEach((r, i) => { try { const res = createExpense(q.acct, { occurredAt: r.occurredAt, amountPaise: Math.round(+r.amount * 100), merchant: r.merchant, categoryPath: r.category || undefined, paymentMethod: r.paymentMethod || 'upi', clientRef: r.clientRef || ('imp-' + j.fileName + '-' + i) }, 'import'); if (res.dup) j.duplicates++; else j.ok++; } catch (e) { j.failed++; j.errors.push({ rowNo: i + 2, code: (e.resp && e.resp.body.errors && e.resp.body.errors[0].code) || 'INVALID', detail: (e.resp && e.resp.body.errors && e.resp.body.errors[0].detail) || '' }); } j.total++; });
  j.status = 'completed'; j.finishedAt = nowIso(); j.version++; emit('dexter.ledger.import.v1', 'ledger.import.completed', q.acct, { importId: j.id, ok: j.ok, failed: j.failed }); return ok(j, 202);
});
handle('GET', '/v1/imports/{id}', (q) => { const j = DB.ledger.imports.find((x) => x.id === q.params.id && x.accountId === q.acct); if (!j) fail(404, 'NOT_FOUND', 'Import'); const { _rows, ...v } = j; return ok(v); });
handle('GET', '/v1/calendar', (q) => {
  const m = q.query.month || monthOf(today()); need(/^\d{4}-\d{2}$/.test(m), 'month', 'INVALID', 'YYYY-MM');
  const days = {}; let spent = 0, cnt = 0;
  for (const [k, v] of DB.ledger.daily) { const [a, d, r] = k.split('|'); if (a !== q.acct || monthOf(d) !== m || !v.count) continue; const x = (days[d] = days[d] || { day: d, spentPaise: 0, txnCount: 0, byRoot: {}, upcoming: [] }); x.spentPaise += v.total; x.txnCount += v.count; x.byRoot[r] = v.total; spent += v.total; cnt += v.count; }
  let up = 0; // upcoming = liabilities + EMI instalments + card dues (projection merged per day)
  for (const l of DB.planning.liabilities.filter((x) => x.accountId === q.acct && x.status === 'active')) for (const i of DB.planning.lschedule[l.id]) if (monthOf(i.dueDate) === m) { const x = (days[i.dueDate] = days[i.dueDate] || { day: i.dueDate, spentPaise: 0, txnCount: 0, byRoot: {}, upcoming: [] }); x.upcoming.push({ kind: l.kind, label: l.lender, amountPaise: i.totalPaise }); up += i.totalPaise; }
  for (const p of DB.portfolio.emi.filter((x) => x.accountId === q.acct && x.status === 'active')) for (const i of DB.portfolio.schedule[p.id]) if (i.status === 'due' && monthOf(i.dueDate) === m) { const x = (days[i.dueDate] = days[i.dueDate] || { day: i.dueDate, spentPaise: 0, txnCount: 0, byRoot: {}, upcoming: [] }); x.upcoming.push({ kind: 'card_emi', label: p.description, amountPaise: i.totalPaise }); up += i.totalPaise; }
  for (const s of DB.portfolio.statements.filter((x) => x.accountId === q.acct && x.status === 'generated' && monthOf(x.dueDate) === m)) { const x = (days[s.dueDate] = days[s.dueDate] || { day: s.dueDate, spentPaise: 0, txnCount: 0, byRoot: {}, upcoming: [] }); const c = DB.portfolio.cards.find((c) => c.id === s.userCardId); x.upcoming.push({ kind: 'card_due', label: c.nickname + ' due', amountPaise: s.totalDuePaise }); up += s.totalDuePaise; }
  return ok({ month: m, spentPaise: spent, txnCount: cnt, upcomingPaise: up, days: Object.values(days).sort((a, b) => (a.day < b.day ? -1 : 1)) });
});
handle('GET', '/v1/summary', (q) => {
  const g = q.query.groupBy || 'category'; need(['category', 'merchant', 'day', 'paymentMethod'].includes(g), 'groupBy', 'INVALID', 'category|merchant|day|paymentMethod');
  const l = filterExpenses({ ...q, query: { from: q.query.from, to: q.query.to } }); const tot = l.reduce((a, e) => a + e.amountPaise, 0); const m = {};
  for (const e of l) { const k = g === 'category' ? rootOf(e.categoryPath) : g === 'merchant' ? e.merchantId || e.merchantDisplay : g === 'day' ? istDay(e.occurredAt) : e.paymentMethod; const lab = g === 'merchant' ? e.merchantDisplay : g === 'category' ? ((DB.ledger.categories.find((c) => c.path === k) || {}).name || k) : k; const x = (m[k] = m[k] || { key: k, label: lab, totalPaise: 0, txnCount: 0 }); x.totalPaise += e.amountPaise; x.txnCount++; }
  return ok({ from: q.query.from, to: q.query.to, groupBy: g, totalPaise: tot, groups: Object.values(m).map((x) => ({ ...x, shareBps: tot ? Math.round(x.totalPaise / tot * 10000) : 0 })).sort((a, b) => (g === 'day' ? (a.key < b.key ? -1 : 1) : b.totalPaise - a.totalPaise)) });
});

/* ================= catalog ================= */
const pubCard = (c) => ({ id: c.id, slug: c.slug, name: c.name, issuerCode: c.issuerCode, issuerName: c.issuerName, network: c.network, tier: c.tier, joiningFeePaise: c.joiningFeePaise, annualFeePaise: c.annualFeePaise, feeWaiverSpendPaise: c.feeWaiverSpendPaise, aprBps: c.aprBps, forexMarkupBps: c.forexMarkupBps, fuelSurchargeWaiverBps: c.fuelSurchargeWaiverBps, features: c.features, rules: c.rules, milestone: c.milestone, version: c.currentVersion });
const bestRate = (c, cat) => Math.max(0, ...c.rules.filter((r) => !r.merchantSlug && (r.categoryPath == null || isUnder(cat, r.categoryPath)) && !(r.excludedPaths || []).some((x) => isUnder(cat, x))).map((r) => r.rateBps));
handle('GET', '/v1/catalog/cards', (q) => {
  const f = q.query; let l = DB.catalog.cards.filter((c) => c.status === 'published');
  if (f.network) l = l.filter((c) => c.network === f.network); if (f.issuer) l = l.filter((c) => c.issuerCode === f.issuer); if (f.maxAnnualFee) l = l.filter((c) => c.annualFeePaise <= +f.maxAnnualFee);
  if (f.category) l = l.filter((c) => bestRate(c, f.category) >= +(f.minRateBps || 1));
  l.sort((a, b) => (a.slug < b.slug ? -1 : 1)); const p = page(l, q, (r) => [r.slug]); return ok({ data: p.data.map(pubCard), page: p.page }, 200, { 'Cache-Control': 'public, max-age=60' });
});
handle('GET', '/v1/catalog/cards/search', (q) => { const n = (q.query.q || '').toLowerCase(); need(n.length >= 2, 'q', 'TOO_SHORT', '≥2'); const l = DB.catalog.cards.filter((c) => c.status === 'published').map((c) => ({ c, s: Math.max(similarity(n, c.name.toLowerCase().replace(/mock_/, '')), c.name.toLowerCase().includes(n) ? 1 : 0) })).filter((x) => x.s >= 0.25).sort((a, b) => b.s - a.s).map((x) => ({ ...pubCard(x.c), relevance: +x.s.toFixed(3) })); return ok({ data: l.slice(0, +(q.query.limit || 25)) }); });
handle('GET', '/v1/catalog/cards/{slug}', (q) => { const c = cardBySlug(q.params.slug); if (!c || c.status !== 'published') fail(404, 'NOT_FOUND', 'Card'); return ok({ version: c.currentVersion, card: pubCard(c) }, 200, { ETag: `"${c.currentVersion}"` }); });
const offerView = (o) => ({ id: o.id, issuerCode: o.issuerCode, cardProductId: o.cardProductId, cardName: (cardById(o.cardProductId) || {}).name, merchantId: o.merchantId, merchantName: o.merchantId ? (merchantById(o.merchantId) || {}).name : null, categoryPath: o.categoryPath, title: o.title, kind: o.kind, valueBps: o.valueBps, valuePaise: o.valuePaise, maxDiscountPaise: o.maxDiscountPaise, minSpendPaise: o.minSpendPaise, validFrom: o.valid[0], validTo: o.valid[1], stackable: o.stackable, termsUrl: o.termsUrl, status: o.status, version: o.version });
handle('GET', '/v1/catalog/offers', (q) => { let l = DB.catalog.offers.filter((o) => o.status === 'active'); if (q.query.merchantId) l = l.filter((o) => o.merchantId === q.query.merchantId || (!o.merchantId && o.categoryPath && isUnder((merchantById(q.query.merchantId) || {}).category, o.categoryPath))); if (q.query.category) l = l.filter((o) => o.categoryPath && (isUnder(q.query.category, o.categoryPath) || isUnder(o.categoryPath, q.query.category))); return ok({ data: l.map(offerView) }); });
handle('GET', '/v1/catalog/categories/potential', (q) => { const cat = q.query.category; need(/^[a-z][a-z0-9_.]*$/.test(cat || ''), 'category', 'INVALID', 'An ltree path'); const l = DB.catalog.cards.filter((c) => c.status === 'published').map((c) => { const r = c.rules.filter((r) => !r.merchantSlug && (r.categoryPath == null || isUnder(cat, r.categoryPath)) && !(r.excludedPaths || []).some((x) => isUnder(cat, x))).sort((a, b) => b.rateBps - a.rateBps)[0]; return r && { cardProductId: c.id, slug: c.slug, name: c.name, rateBps: r.rateBps, rewardType: r.rewardType, capPaise: r.capPaise, capPeriod: r.capPeriod, rulePath: r.categoryPath }; }).filter(Boolean).sort((a, b) => b.rateBps - a.rateBps).slice(0, 10); return ok({ category: cat, cards: l }, 200, { 'Cache-Control': 'public, max-age=300' }); });
handle('POST', '/v1/admin/catalog/issuers', (q) => { const b = q.body || {}; need(/^[A-Z0-9_]{3,32}$/.test(b.code || ''), 'code', 'INVALID', 'UPPER_SNAKE'); need(['bank', 'nbfc', 'cobrand'].includes(b.type), 'type', 'INVALID', 'bank|nbfc|cobrand'); if (DB.catalog.issuers.some((i) => i.code === b.code)) fail(409, 'ISSUER_EXISTS', b.code); const i = { id: uuid7(), code: b.code, name: b.name, type: b.type }; DB.catalog.issuers.push(i); audit('admin', q.admin.sub, 'catalog.issuer.created', 'issuer', i.id); return ok(i, 201); });
handle('GET', '/v1/admin/catalog/cards', (q) => { const l = [...DB.catalog.cards, ...Object.values(DB.catalog.drafts).filter((d) => d._new)].map((c) => ({ id: c.id, slug: c.slug, name: c.name, network: c.network, status: c.status, currentVersion: c.currentVersion || null, hasDraft: !!DB.catalog.drafts[c.id], updatedAt: c.updatedAt })).sort((a, b) => (a.slug < b.slug ? -1 : 1)); const p = page(l, q, (r) => [r.slug]); return ok(p); });
handle('POST', '/v1/admin/catalog/cards', (q) => { const b = q.body || {}; need(/^[a-z0-9-]{3,80}$/.test(b.slug || ''), 'slug', 'INVALID', 'kebab-case'); if (DB.catalog.cards.some((c) => c.slug === b.slug)) fail(409, 'SLUG_EXISTS', b.slug); const iss = DB.catalog.issuers.find((i) => i.code === b.issuerCode); need(iss, 'issuerCode', 'UNKNOWN', 'An issuer'); const d = { id: uuid7(), slug: b.slug, name: b.name, issuerCode: iss.code, issuerName: iss.name, network: b.network || 'visa', tier: b.tier || 'entry', joiningFeePaise: b.joiningFeePaise || 0, annualFeePaise: b.annualFeePaise || 0, feeWaiverSpendPaise: b.feeWaiverSpendPaise || null, aprBps: b.aprBps || 4284, forexMarkupBps: b.forexMarkupBps || 350, fuelSurchargeWaiverBps: 100, features: {}, rules: b.rules || [], milestone: null, status: 'draft', currentVersion: null, updatedAt: nowIso(), version: 0, _new: true }; DB.catalog.drafts[d.id] = d; audit('admin', q.admin.sub, 'catalog.card.draft-created', 'card_product', d.id); return ok({ id: d.id, status: 'draft', version: 0 }, 201, etag(d)); });
const getDraft = (q) => { const d = DB.catalog.drafts[q.params.id]; if (!d) fail(404, 'NOT_FOUND', 'No draft for this card'); return d; };
handle('GET', '/v1/admin/catalog/cards/{id}/draft', (q) => { let d = DB.catalog.drafts[q.params.id]; if (!d) { const c = cardById(q.params.id); if (!c) fail(404, 'NOT_FOUND', 'Card'); d = DB.catalog.drafts[c.id] = { ...JSON.parse(JSON.stringify(c)), version: 0 }; } return ok(d, 200, etag(d)); });
handle('PUT', '/v1/admin/catalog/cards/{id}/draft', (q) => { const d = getDraft(q); checkIfMatch(q, d); const b = q.body || {}; for (const k of ['name', 'annualFeePaise', 'joiningFeePaise', 'feeWaiverSpendPaise', 'aprBps', 'forexMarkupBps', 'rules', 'milestone', 'tier', 'network']) if (k in b) d[k] = b[k]; d.version++; d.updatedAt = nowIso(); return ok(d, 200, etag(d)); });
handle('DELETE', '/v1/admin/catalog/cards/{id}/draft', (q) => { getDraft(q); delete DB.catalog.drafts[q.params.id]; return ok(null, 204); });
handle('GET', '/v1/admin/catalog/cards/{id}/versions', (q) => ok({ data: (DB.catalog.versions[q.params.id] || []).slice().reverse() }));
function publishCard(id, actor, via = 'admin') {
  const d = DB.catalog.drafts[id]; let c = cardById(id);
  if (!d && !c) fail(404, 'NOT_FOUND', 'Card'); if (!d) fail(409, 'NOTHING_TO_PUBLISH', 'No draft');
  const { version, _new, ...snap } = d;
  if (!c) { c = { ...snap, status: 'published', currentVersion: 0 }; DB.catalog.cards.push(c); }
  Object.assign(c, snap, { status: 'published' }); c.currentVersion++; c.updatedAt = nowIso();
  const vs = (DB.catalog.versions[id] = DB.catalog.versions[id] || []); if (vs.length) vs[vs.length - 1].effectiveTo = nowIso();
  vs.push({ version: c.currentVersion, effectiveFrom: nowIso(), effectiveTo: null, snapshot: JSON.parse(JSON.stringify(c)) });
  delete DB.catalog.drafts[id]; audit(via, actor, 'catalog.card.published', 'card_product', id, { version: c.currentVersion });
  emit('dexter.catalog.card.v1 (compacted)', 'catalog.card.published', null, { cardProductId: id, slug: c.slug, version: c.currentVersion });
  touch('catalog', 'card_product_versions', c.slug + ' v' + c.currentVersion, 'immutable version; previous validity range closed');
  touch('reco', 'catalog_cards_proj / catalog_rules_proj', c.slug, 'replaced wholesale (version-guarded) — NO per-account dirty fan-out (A-83)');
  return { id, version: c.currentVersion, publishedAt: nowIso() };
}
handle('POST', '/v1/admin/catalog/cards/{id}:publish', (q) => ok(publishCard(q.params.id, q.admin.sub)));
handle('POST', '/v1/admin/catalog/cards/{id}:retire', (q) => { const c = cardById(q.params.id); if (!c) fail(404, 'NOT_FOUND', 'Card'); c.status = 'retired'; c.currentVersion++; emit('dexter.catalog.card.v1 (compacted)', 'catalog.card.published', null, { cardProductId: c.id, status: 'retired' }); audit('admin', q.admin.sub, 'catalog.card.retired', 'card_product', c.id); return ok({ id: c.id, version: c.currentVersion, status: 'retired' }); });
handle('POST', '/v1/admin/catalog/offers', (q) => { const b = q.body || {}; need(cardById(b.cardProductId), 'cardProductId', 'UNKNOWN', 'A card'); need(['pct', 'flat', 'cashback'].includes(b.kind), 'kind', 'INVALID', 'pct|flat|cashback'); const o = { id: uuid7(), issuerCode: cardById(b.cardProductId).issuerCode, cardProductId: b.cardProductId, merchantId: b.merchantId || null, categoryPath: b.categoryPath || null, title: b.title, kind: b.kind, valueBps: b.valueBps || null, valuePaise: b.valuePaise || null, maxDiscountPaise: b.maxDiscountPaise || null, minSpendPaise: b.minSpendPaise || 0, valid: [nowIso(), b.validTo || '2026-12-31T23:59:59Z'], stackable: !!b.stackable, status: 'active', version: 1, termsUrl: b.termsUrl || null }; DB.catalog.offers.push(o); emit('dexter.catalog.offer.v1', 'catalog.offer.changed', null, { offerId: o.id }); touch('reco', 'offers_proj', o.title, 'upsert; read by best-card-now'); return ok(offerView(o), 201); });
handle('POST', '/v1/admin/catalog/offers/{id}:withdraw', (q) => { const o = DB.catalog.offers.find((x) => x.id === q.params.id); if (!o) fail(404, 'NOT_FOUND', 'Offer'); o.status = 'withdrawn'; o.version++; emit('dexter.catalog.offer.v1', 'catalog.offer.changed', null, { offerId: o.id, status: 'withdrawn' }); return ok(offerView(o)); });
// ingestion + review
const PRIVATE = /^(localhost|127\.|10\.|169\.254\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.|0\.|\[?::1)/;
handle('GET', '/v1/admin/ingestion/sources', (q) => ok(page([...DB.catalog.sources].sort((a, b) => (a.createdAt < b.createdAt ? 1 : -1)), q, (r) => [r.createdAt, r.id])));
handle('POST', '/v1/admin/ingestion/sources', (q) => { const b = q.body || {}; let u; try { u = new URL(b.url); } catch { need(false, 'url', 'INVALID', 'An https URL'); } need(u.protocol === 'https:', 'url', 'NOT_HTTPS', 'https only'); if (PRIVATE.test(u.hostname)) fail(400, 'SSRF_BLOCKED', 'Private, loopback or link-local addresses are refused'); need(['html', 'pdf', 'sitemap', 'image'].includes(b.kind), 'kind', 'INVALID', 'html|pdf|sitemap|image'); const s = { id: uuid7(), issuerCode: b.issuerCode, cardProductId: b.cardProductId || null, url: b.url, host: u.hostname, kind: b.kind, scheduleCron: b.scheduleCron || '0 3 * * *', enabled: true, nextRunAt: null, lastRunAt: null, createdAt: nowIso(), updatedAt: nowIso(), version: 0 }; DB.catalog.sources.push(s); return ok(s, 201, etag(s)); });
const getSrc = (q) => { const s = DB.catalog.sources.find((x) => x.id === q.params.id); if (!s) fail(404, 'NOT_FOUND', 'Source'); return s; };
handle('GET', '/v1/admin/ingestion/sources/{id}', (q) => { const s = getSrc(q); return ok(s, 200, etag(s)); });
handle('PATCH', '/v1/admin/ingestion/sources/{id}', (q) => { const s = getSrc(q); checkIfMatch(q, s); Object.assign(s, q.body || {}); s.version++; s.updatedAt = nowIso(); return ok(s, 200, etag(s)); });
handle('DELETE', '/v1/admin/ingestion/sources/{id}', (q) => { const s = getSrc(q); DB.catalog.sources.splice(DB.catalog.sources.indexOf(s), 1); return ok(null, 204); });
function runSources(ids, trigger) {
  const run = { id: uuid7(), trigger, status: 'succeeded', sourceIds: ids, attempts: 1, errorCode: null, checkpoint: { done: ids.length, of: ids.length, unchanged: 0, changed: 0 }, createdAt: nowIso(), startedAt: nowIso(), finishedAt: nowIso() };
  for (const id of ids) { const s = DB.catalog.sources.find((x) => x.id === id); s.lastRunAt = nowIso(); const c = cardById(s.cardProductId);
    if (c && Math.random() < 0.6) { run.checkpoint.changed++; DB.catalog.reviews.unshift({ id: uuid7(), status: 'pending', cardProductId: c.id, cardSlug: c.slug, cardName: c.name, baseVersion: c.currentVersion, confidence: 0.9, changes: [{ path: 'joiningFeePaise', from: c.joiningFeePaise, to: c.joiningFeePaise + 25000, confidence: 0.9 }], sourceId: s.id, snapshotId: uuid7(), url: s.url, fetchedAt: nowIso(), createdAt: nowIso(), reviewerId: null, decidedAt: null, reason: null, promotion: null }); }
    else run.checkpoint.unchanged++; }
  DB.catalog.runs.unshift(run); return run;
}
handle('POST', '/v1/admin/ingestion/sources/{id}/runs', (q) => ok(runSources([getSrc(q).id], 'manual'), 202));
handle('POST', '/v1/admin/ingestion/runs', (q) => ok(runSources(((q.body || {}).sourceIds || DB.catalog.sources.filter((s) => s.enabled).map((s) => s.id)), 'manual'), 202));
handle('GET', '/v1/admin/ingestion/runs', (q) => ok(page(DB.catalog.runs, q, (r) => [r.createdAt, r.id])));
handle('GET', '/v1/admin/ingestion/runs/{id}', (q) => { const r = DB.catalog.runs.find((x) => x.id === q.params.id); if (!r) fail(404, 'NOT_FOUND', 'Run'); return ok(r); });
handle('GET', '/v1/admin/review', (q) => ok(page(DB.catalog.reviews.filter((r) => !q.query.status || r.status === q.query.status), q, (r) => [r.createdAt, r.id])));
handle('GET', '/v1/admin/review/{id}', (q) => { const r = DB.catalog.reviews.find((x) => x.id === q.params.id); if (!r) fail(404, 'NOT_FOUND', 'Review item'); return ok(r); });
handle('POST', '/v1/admin/review/{id}:approve', (q) => {
  const r = DB.catalog.reviews.find((x) => x.id === q.params.id); if (!r) fail(404, 'NOT_FOUND', 'Review item');
  if (r.status === 'approved') return ok(r); // promoter idempotent: a double approve creates one version
  if (r.status !== 'pending') fail(409, 'REVIEW_NOT_PENDING', r.status);
  const c = cardById(r.cardProductId); if (c.currentVersion !== r.baseVersion) { r.status = 'superseded'; fail(409, 'REVIEW_STALE', 'The card changed since this diff was taken'); }
  const d = DB.catalog.drafts[c.id] = { ...JSON.parse(JSON.stringify(c)), version: 0 };
  for (const ch of r.changes) { if (ch.path.startsWith('rules[')) { const p = ch.path.match(/rules\[(.+)\]/)[1]; const rr = d.rules.find((x) => x.categoryPath === p); if (rr) rr.rateBps = ch.to; } else d[ch.path] = ch.to; }
  const pub = publishCard(c.id, q.admin.sub, 'promoter'); r.status = 'approved'; r.reviewerId = q.admin.sub; r.decidedAt = nowIso(); r.promotion = { id: uuid7(), newVersion: pub.version, promotedBy: q.admin.sub, promotedAt: nowIso() };
  DB.catalog.reviews.filter((x) => x !== r && x.cardProductId === c.id && x.status === 'pending').forEach((x) => (x.status = 'superseded'));
  return ok(r);
});
handle('POST', '/v1/admin/review/{id}:reject', (q) => { const r = DB.catalog.reviews.find((x) => x.id === q.params.id); if (!r) fail(404, 'NOT_FOUND', 'Review item'); if (r.status !== 'pending') fail(409, 'REVIEW_NOT_PENDING', r.status); r.status = 'rejected'; r.reason = (q.body || {}).reason || null; r.reviewerId = q.admin.sub; r.decidedAt = nowIso(); return ok(r); });
