package in.dexter.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.catalog.app.CardFilter;
import in.dexter.catalog.app.CatalogQueryService;
import in.dexter.catalog.infra.CatalogRepository;
import in.dexter.catalog.support.CatalogIT;
import in.dexter.db.plan.PlanAssert;
import in.dexter.db.tenant.TenantTx;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * PlanAssert (MASTER_PLAN 3.5, P09 "search plan") on 50,000 generated published cards: the list walks
 * {@code card_products_public} in (name, id) order without a Sort, the network filter uses its own index, and search
 * uses the trigram index (inside the owner's search function) — never a sequential scan of card_products.
 */
class CatalogPlanIT extends CatalogIT {
  static final int CARDS = 50_000;
  static final UUID ISSUER = UUID.nameUUIDFromBytes("plan-issuer".getBytes());

  @Autowired
  TenantTx tx;
  @Autowired
  JdbcClient jdbc;

  @BeforeAll
  static void bulk() {
    if (ADMIN.sql("SELECT count(1) FROM catalog.issuers WHERE id = :i").param("i", ISSUER).query(Long.class).single() > 0) {
      return;
    }
    ADMIN.sql("INSERT INTO catalog.issuers (id, code, name, type) VALUES (:i, 'PLAN_BULK', 'Plan Bulk Bank', 'bank')")
        .param("i", ISSUER).update();
    ADMIN.sql("""
        INSERT INTO catalog.card_products (id, issuer_id, slug, name, network, tier, annual_fee_paise, status,
                                           current_version, content_hash)
        SELECT md5('plan-card-' || g)::uuid, CAST(:i AS uuid), 'plan-card-' || g,
               (ARRAY['Aurora','Borealis','Cascade','Delta','Ember','Falcon','Glacier','Harbor'])[1 + g % 8]
                 || ' ' || (ARRAY['Rewards','Cashback','Platinum','Select','Prime'])[1 + g % 5] || ' ' || g,
               (ARRAY['visa','mastercard','rupay','amex','diners'])[1 + g % 5], 'entry', (g % 50) * 10000,
               CASE WHEN g % 10 = 0 THEN 'draft' ELSE 'published' END,
               CASE WHEN g % 10 = 0 THEN NULL ELSE 1 END, CASE WHEN g % 10 = 0 THEN NULL ELSE '\\x00'::bytea END
        FROM generate_series(1, :n) g
        """).param("i", ISSUER).param("n", CARDS).update();
    ADMIN.sql("""
        INSERT INTO catalog.card_product_versions (card_product_id, version, snapshot, effective_from)
        SELECT id, 1, jsonb_build_object('id', id, 'slug', slug, 'name', name), now()
        FROM catalog.card_products WHERE issuer_id = CAST(:i AS uuid) AND status = 'published'
        """).param("i", ISSUER).update();
    // VACUUM moves the GIN pending list (50k fast-update inserts) into the index; ANALYZE alone leaves it, and
    // the planner then prices the trigram index as if every pending entry had to be scanned.
    ADMIN.sql("VACUUM ANALYZE catalog.card_products").update();
    ADMIN.sql("ANALYZE catalog.card_product_versions").update();
  }

  void plan(String sql, Map<String, Object> params, String index, boolean noSort) {
    tx.read(CatalogQueryService.PUBLIC, () -> {
      PlanAssert a = PlanAssert.query(jdbc, sql, params).forbidSeqScanOn("card_products").requireIndex(index);
      if (noSort) {
        a.forbidSort();
      }
      return a.verify();
    });
  }

  @Test
  void theListWalksThePublicIndexWithoutASort() {
    var q = CatalogRepository.listQuery(0);
    plan(q.firstPageSql(), Map.of("__limit", 51), "card_products_public", true);
    Map<String, Object> next = new HashMap<>(Map.of("__limit", 26, "__k0", "Delta Prime 123", "__k1",
        UUID.fromString("ffffffff-ffff-4fff-bfff-ffffffffffff")));
    plan(q.nextPageSql(), next, "card_products_public", true);
  }

  @Test
  void theNetworkFilterUsesItsIndex() {
    var q = CatalogRepository.listQuery(CatalogRepository.mask(new CardFilter("amex", null, null, null, null)));
    plan(q.firstPageSql(), Map.of("__limit", 51, "network", "amex"), "card_products_network", true);
  }

  /**
   * Search runs inside {@code catalog.search_published_cards} (SECURITY DEFINER, ADR-0014 §6), so EXPLAIN cannot see
   * its inner query: auto_explain logs the nested plan as a NOTICE on a superuser connection that calls the function
   * as catalog_app, exactly like the service.
   */
  @Test
  void searchUsesTheTrigramIndex() throws Exception {
    List<String> plans = new ArrayList<>();
    try (Connection c = DriverManager.getConnection(DB.url(), "postgres", "postgres");
         Statement st = c.createStatement()) {
      st.execute("LOAD 'auto_explain'");
      st.execute("SET auto_explain.log_min_duration = 0");
      st.execute("SET auto_explain.log_nested_statements = on");
      st.execute("SET auto_explain.log_level = notice");
      st.execute("SET client_min_messages = notice");
      st.execute("SET ROLE catalog_app");
      for (String q : List.of("glacier prime", "borealis cashbak", "harbor select 77")) {
        st.clearWarnings();
        try (ResultSet rs = st.executeQuery("SELECT count(1) FROM catalog.search_published_cards('" + q + "', 20)")) {
          rs.next();
          assertThat(rs.getLong(1)).as(q).isPositive();
        }
        StringBuilder all = new StringBuilder();
        for (SQLWarning w = st.getWarnings(); w != null; w = w.getNextWarning()) {
          all.append(w.getMessage()).append('\n');
        }
        plans.add(all.toString());
      }
    }
    for (String plan : plans) {
      assertThat(plan).as(plan).contains("FROM catalog.card_products c").contains("card_products_name_trgm")
          .doesNotContain("Seq Scan on card_products");
    }
    assertThat(anon("GET", "/v1/catalog/cards/search?q=glacier%20prime").json().get("data").size()).isEqualTo(20);
  }
}
