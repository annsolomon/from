package in.dexter.ledger.support;

import java.util.UUID;

/**
 * Medium-sized data for PlanAssert (MASTER_PLAN 3.5, P07 DoD "PlanAssert green on ≥ 1M rows"): 1,200,000 expenses
 * over 10,000 accounts and one year, the medium seed's shape (A-46): ~120 per account, a heavy account with 30,000,
 * ~35 % card payments over 3 cards, ~70 % with one of 357 merchants, 1 % soft-deleted. Inserted once per JVM as the
 * superuser, then ANALYZEd.
 */
public final class BulkData {
  public static final UUID HEAVY = UUID.fromString("00000000-0000-7000-8000-00000000beef");
  public static final long TOTAL = 1_200_000;
  private static volatile boolean loaded;

  private BulkData() {}

  public static synchronized void ensure() {
    if (loaded) {
      return;
    }
    Long have = LedgerIT.ADMIN.sql("SELECT count(1) FROM ledger.expenses WHERE account_id = :h")
        .param("h", HEAVY).query(Long.class).single();
    if (have == 0) {
      LedgerIT.ADMIN.sql("SET maintenance_work_mem = '256MB'").update();
      LedgerIT.ADMIN.sql("""
          INSERT INTO ledger.expenses (id, account_id, occurred_at, amount_paise, payment_method, user_card_id,
                                       merchant_id, merchant_display, category_path, category_source, source,
                                       created_at, updated_at, deleted_at)
          SELECT gen_random_uuid(), acc, at, amt, pm,
                 CASE WHEN pm = 'card' THEN md5(acc::text || (g % 3))::uuid END,
                 CASE WHEN g % 10 < 7 THEN md5('merchant-' || (g * 7919 % 357))::uuid END,
                 'Bulk merchant', cat, 'resolver', 'manual', at, at,
                 CASE WHEN g % 100 = 0 THEN at + interval '1 day' END
          FROM (
            SELECT g,
                   CASE WHEN g <= 30000 THEN CAST(:heavy AS uuid)
                        ELSE md5('acct-' || (g % 9999))::uuid END AS acc,
                   timestamptz '2025-10-01 00:00:00+05:30' + (g * 7907 % 31536000) * interval '1 second' AS at,
                   1000 + (g * 104729 % 500000) AS amt,
                   (ARRAY['card','upi','upi','cash','card','netbanking','wallet','card','upi','other'])[1 + g % 10] AS pm,
                   CAST((ARRAY['food.dining','food.delivery','food.groceries','travel.cab','travel.air','fuel.petrol',
                     'shopping.online','utilities.mobile','entertainment.ott','health.pharmacy','housing.rent',
                     'general'])[1 + g * 31 % 12] AS ltree) AS cat
            FROM generate_series(CAST(1 AS bigint), CAST(:n AS bigint)) g) s
          """).param("heavy", HEAVY).param("n", TOTAL).update();
      LedgerIT.ADMIN.sql("ANALYZE ledger.expenses").update();
    }
    loaded = true;
  }
}
