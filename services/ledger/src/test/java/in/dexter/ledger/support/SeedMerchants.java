package in.dexter.ledger.support;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Loads the seed's 357 reference merchants and their aliases ({@code seed/data/v1}) into the IT database once, as
 * the superuser (the app role can only read them). Ids are name-based UUIDs of the slug.
 */
public final class SeedMerchants {
  private static Map<String, UUID> ids;

  private SeedMerchants() {}

  public static UUID id(String slug) {
    return UUID.nameUUIDFromBytes(("merchant:" + slug).getBytes(StandardCharsets.UTF_8));
  }

  /** slug → id; loads on first call (idempotent across test classes). */
  public static synchronized Map<String, UUID> ensureLoaded(JdbcClient admin) {
    if (ids != null) {
      return ids;
    }
    Map<String, UUID> m = new HashMap<>();
    List<LabelledMerchants.Merchant> merchants = LabelledMerchants.merchants();
    for (LabelledMerchants.Merchant x : merchants) {
      UUID id = id(x.slug());
      m.put(x.slug(), id);
      admin.sql("INSERT INTO ledger.merchants (id, canonical_name, normalized_name, default_category)"
              + " VALUES (:id, :n, :nn, CAST(:c AS public.ltree)) ON CONFLICT (id) DO NOTHING")
          .param("id", id).param("n", x.name()).param("nn", in.dexter.ledger.domain.MerchantText.basic(x.name()))
          .param("c", x.category())
          .update();
    }
    for (LabelledMerchants.Alias a : LabelledMerchants.aliases()) {
      admin.sql("INSERT INTO ledger.merchant_aliases (alias_normalized, merchant_id, weight) VALUES (:a, :m, :w)"
              + " ON CONFLICT DO NOTHING")
          .param("a", a.alias()).param("m", id(a.slug())).param("w", a.weight())
          .update();
    }
    // lookups cached before the merchants existed ("no merchant") must not survive into the tests
    in.dexter.testkit.containers.TestRedis.admin().execute(
        (org.springframework.data.redis.core.RedisCallback<Object>) c -> {
          c.serverCommands().flushAll();
          return null;
        });
    admin.sql("ANALYZE ledger.merchants").update();
    admin.sql("ANALYZE ledger.merchant_aliases").update();
    ids = m;
    return m;
  }
}
