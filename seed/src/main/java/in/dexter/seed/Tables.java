package in.dexter.seed;

import java.util.List;

/**
 * The seeded tables, in load order (parents before children), with the column shapes MASTER_PLAN §13 gives the
 * phases that create them. When a phase's migration differs from this list, that phase updates it here (ADR-0008).
 */
public final class Tables {
  private Tables() {}

  // ---------------------------------------------------------------- identity (P04 / P05)
  public static final Table ACCOUNTS = Table.of("identity", "accounts", "P04")
      .col("id", "uuid").col("email", "public.citext").col("password_hash", "text").col("status", "text")
      .col("email_verified_at", "timestamptz").col("tz", "text").col("tier_cache", "text")
      .col("created_at", "timestamptz").col("updated_at", "timestamptz").col("version", "integer").build();

  public static final Table ADMINS = Table.of("identity", "admins", "P05")
      .col("id", "uuid").col("email", "public.citext").col("password_hash", "text").col("status", "text")
      .col("mfa_required", "boolean").col("created_at", "timestamptz").col("updated_at", "timestamptz")
      .col("version", "integer").build();

  // ---------------------------------------------------------------- catalog (P09)
  public static final Table ISSUERS = Table.of("catalog", "issuers", "P09")
      .col("id", "uuid").col("code", "text").col("name", "text").col("type", "text").build();

  public static final Table CARD_PRODUCTS = Table.of("catalog", "card_products", "P09")
      .col("id", "uuid").col("issuer_id", "uuid").col("slug", "text").col("name", "text").col("network", "text")
      .col("tier", "text").col("joining_fee_paise", "bigint").col("annual_fee_paise", "bigint")
      .col("fee_waiver_spend_paise", "bigint").col("apr_bps", "integer").col("forex_markup_bps", "integer")
      .col("fuel_surcharge_waiver_bps", "integer").col("features", "jsonb").col("status", "text")
      .col("current_version", "integer").col("content_hash", "bytea").col("created_at", "timestamptz")
      .col("updated_at", "timestamptz").col("version", "integer").build();

  public static final Table CARD_PRODUCT_VERSIONS = Table.of("catalog", "card_product_versions", "P09")
      .col("card_product_id", "uuid").col("version", "integer").col("snapshot", "jsonb")
      .col("effective_from", "timestamptz").col("effective_to", "timestamptz").build();

  public static final Table REWARD_RULES = Table.of("catalog", "reward_rules", "P09")
      .col("id", "uuid").col("card_product_id", "uuid").col("version", "integer").col("category_path", "public.ltree")
      .col("merchant_id", "uuid").col("reward_type", "text").col("rate_bps", "integer")
      .col("point_value_milli_paise", "bigint").col("cap_paise", "bigint").col("cap_period", "text")
      .col("min_txn_paise", "bigint").col("excluded_paths", "public.ltree[]").col("valid", "tstzrange")
      .col("priority", "integer").build();

  public static final Table MILESTONES = Table.of("catalog", "milestones", "P09")
      .col("card_product_id", "uuid").col("spend_threshold_paise", "bigint").col("period", "text")
      .col("benefit_type", "text").col("benefit_value_paise", "bigint").build();

  // ---------------------------------------------------------------- portfolio (P11)
  public static final Table USER_CARDS = Table.of("portfolio", "user_cards", "P11")
      .col("id", "uuid").col("account_id", "uuid").col("card_product_id", "uuid").col("nickname", "text")
      .col("last4", "char(4)").col("network", "text").col("statement_day", "smallint")
      .col("due_offset_days", "smallint").col("credit_limit_paise", "bigint").col("opened_on", "date")
      .col("status", "text").col("created_at", "timestamptz").col("updated_at", "timestamptz")
      .col("version", "integer").build();

  // ---------------------------------------------------------------- ledger (P07 / P08)
  public static final Table CATEGORIES = Table.of("ledger", "categories", "P07")
      .col("id", "uuid").col("account_id", "uuid").col("path", "public.ltree").col("name", "text").col("icon", "text")
      .col("is_system", "boolean").build();

  public static final Table MERCHANTS = Table.of("ledger", "merchants", "P08")
      .col("id", "uuid").col("canonical_name", "text").col("normalized_name", "text")
      .col("default_category", "public.ltree").col("logo_key", "text").col("status", "text").build();

  public static final Table MERCHANT_ALIASES = Table.of("ledger", "merchant_aliases", "P08")
      .col("alias_normalized", "text").col("merchant_id", "uuid").col("weight", "integer").build();

  public static final Table EXPENSES = Table.of("ledger", "expenses", "P07")
      .col("id", "uuid").col("account_id", "uuid").col("occurred_at", "timestamptz").col("amount_paise", "bigint")
      .col("currency", "char(3)").col("merchant_raw", "text").col("merchant_display", "text")
      .col("merchant_id", "uuid").col("category_path", "public.ltree").col("category_source", "text")
      .col("resolution_confidence", "smallint").col("payment_method", "text").col("user_card_id", "uuid")
      .col("note", "text").col("tags", "text[]").col("client_ref", "text").col("source", "text")
      .col("created_at", "timestamptz").col("updated_at", "timestamptz").col("version", "integer")
      .col("deleted_at", "timestamptz").build();

  public static final Table EXPENSE_DAILY_TOTALS = Table.of("ledger", "expense_daily_totals", "P07")
      .col("account_id", "uuid").col("day", "date").col("category_root", "public.ltree").col("total_paise", "bigint")
      .col("txn_count", "integer").build();

  /** Load order: parents first (real tables get foreign keys inside a service). */
  public static final List<Table> ALL = List.of(ACCOUNTS, ADMINS, ISSUERS, CARD_PRODUCTS, CARD_PRODUCT_VERSIONS,
      REWARD_RULES, MILESTONES, USER_CARDS, CATEGORIES, MERCHANTS, MERCHANT_ALIASES, EXPENSES, EXPENSE_DAILY_TOTALS);
}
