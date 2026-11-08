-- P17: the commerce admin permissions (MASTER_PLAN P17: card-application decisions, deals, campaigns, stats).
--   commerce:read    the application queue, deals, campaigns, stats         SUPPORT, CATALOG_EDITOR
--   commerce:write   create / change deals and campaigns                    CATALOG_EDITOR
--   commerce:decide  move a card application to review, approve, reject     SUPPORT
-- SUPER_ADMIN holds every permission; V202610171000 granted it the permissions that existed then, so the new ones
-- are granted explicitly here. Expand-only (new rows).
INSERT INTO identity.permissions (code, description) VALUES
  ('commerce:read', 'Read card applications, deals, campaigns and commerce stats (P17)'),
  ('commerce:write', 'Create and change marketplace deals and ad campaigns (P17)'),
  ('commerce:decide', 'Review, approve and reject card applications (P17)');
INSERT INTO identity.role_permissions (role, permission) VALUES
  ('SUPPORT', 'commerce:read'), ('SUPPORT', 'commerce:decide'),
  ('CATALOG_EDITOR', 'commerce:read'), ('CATALOG_EDITOR', 'commerce:write'),
  ('SUPER_ADMIN', 'commerce:read'), ('SUPER_ADMIN', 'commerce:write'), ('SUPER_ADMIN', 'commerce:decide');
