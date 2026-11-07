-- P16: identity keeps the tier billing announces (billing.entitlements.changed v1, consumer billing-tier) and puts it
-- in the `tier` claim of the next token it issues, so a refresh after a checkout carries the new tier (MASTER_PLAN
-- P16). tier_version is billing's per-account entitlement version: the update applies only when it is newer, so an
-- event that arrives late through a retry topic cannot put an account back on an old tier. Expand-only.
ALTER TABLE identity.accounts ADD COLUMN tier_version bigint NOT NULL DEFAULT 0 CHECK (tier_version >= 0);
