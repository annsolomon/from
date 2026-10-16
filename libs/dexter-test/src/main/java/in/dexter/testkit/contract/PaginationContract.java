package in.dexter.testkit.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * MASTER_PLAN 4.5 — every list endpoint's test class extends this. The endpoint must answer
 * {@code {"data":[{"id":...}],"page":{"limit","hasMore","nextCursor"}}}. Each test uses fresh accounts.
 */
public abstract class PaginationContract {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** Inserts {@code n} rows for {@code account}; all with {@code at} when non-null, else distinct times. */
  protected abstract void seed(UUID account, int n, Instant at) throws Exception;

  /** GET the list with optional limit/cursor and filters (query parameters). */
  protected abstract HttpResult list(UUID account, Integer limit, String cursor, Map<String, String> filters)
      throws Exception;

  /** The account's ids in the endpoint's order, straight from SQL (the oracle). */
  protected abstract List<String> expectedOrder(UUID account) throws Exception;

  /** Two different filter values for case 6 (a cursor from A used with B); null to skip. */
  protected abstract List<Map<String, String>> twoDifferentFilters();

  /** Case 9: PlanAssert on the list query (index scan, no Sort). The medium seed arrives in P03. */
  protected abstract void assertListPlan() throws Exception;

  record PageView(List<String> ids, int limit, boolean hasMore, String nextCursor) {}

  PageView page(UUID account, Integer limit, String cursor, Map<String, String> filters) throws Exception {
    HttpResult r = list(account, limit, cursor, filters == null ? Map.of() : filters);
    assertThat(r.status()).as("list: " + r).isEqualTo(200);
    JsonNode n = JSON.readTree(r.body());
    List<String> ids = new ArrayList<>();
    n.get("data").forEach(item -> ids.add(item.get("id").asString()));
    JsonNode p = n.get("page");
    JsonNode next = p.get("nextCursor");
    return new PageView(ids, p.get("limit").asInt(), p.get("hasMore").booleanValue(),
        next == null || next.isNull() ? null : next.asString());
  }

  List<String> walk(UUID account, Map<String, String> filters) throws Exception {
    List<String> all = new ArrayList<>();
    PageView p = page(account, 50, null, filters);
    all.addAll(p.ids());
    int guard = 0;
    while (p.hasMore()) {
      assertThat(++guard).as("walk terminates").isLessThan(10_000);
      p = page(account, 25, p.nextCursor(), filters);
      all.addAll(p.ids());
    }
    assertThat(p.nextCursor()).isNull();
    return all;
  }

  @Test
  void firstPageOfFiftyHasMore() throws Exception {
    UUID a = UUID.randomUUID();
    seed(a, 120, null);
    PageView p = page(a, 50, null, null);
    assertThat(p.ids()).hasSize(50);
    assertThat(p.limit()).isEqualTo(50);
    assertThat(p.hasMore()).isTrue();
    assertThat(p.nextCursor()).isNotBlank();
  }

  @Test
  void walkingAllPagesYieldsEveryRowOnceInOrder() throws Exception {
    UUID a = UUID.randomUUID();
    seed(a, 137, null);
    assertThat(walk(a, null)).containsExactlyElementsOf(expectedOrder(a));
  }

  @Test
  void twoHundredIdenticalTimestampsGiveNoDuplicatesOrGaps() throws Exception {
    UUID a = UUID.randomUUID();
    seed(a, 200, Instant.parse("2026-09-01T10:00:00Z"));
    List<String> all = walk(a, null);
    assertThat(all).hasSize(200).doesNotHaveDuplicates();
    assertThat(all).containsExactlyElementsOf(expectedOrder(a));
  }

  @Test
  void insertsDuringTheWalkCauseNoDuplicates() throws Exception {
    UUID a = UUID.randomUUID();
    seed(a, 100, null);
    List<String> before = expectedOrder(a);
    PageView p = page(a, 50, null, null);
    List<String> seen = new ArrayList<>(p.ids());
    seed(a, 30, null); // newer rows land above the cursor
    while (p.hasMore()) {
      p = page(a, 25, p.nextCursor(), null);
      seen.addAll(p.ids());
    }
    assertThat(seen).doesNotHaveDuplicates().containsExactlyElementsOf(before);
  }

  @Test
  void lastPageHasNoCursor() throws Exception {
    UUID a = UUID.randomUUID();
    seed(a, 10, null);
    PageView p = page(a, 50, null, null);
    assertThat(p.ids()).hasSize(10);
    assertThat(p.hasMore()).isFalse();
    assertThat(p.nextCursor()).isNull();
  }

  @Test
  void tamperedOrMismatchedCursorsAre400() throws Exception {
    UUID a = UUID.randomUUID();
    seed(a, 60, null);
    String cursor = page(a, 25, null, null).nextCursor();
    char last = cursor.charAt(cursor.length() - 1);
    String tampered = cursor.substring(0, cursor.length() - 1) + (last == 'A' ? 'B' : 'A');
    HttpResult bad = list(a, 25, tampered, Map.of());
    assertThat(bad.status()).isEqualTo(400);
    assertThat(bad.body()).contains("CURSOR_INVALID");
    List<Map<String, String>> f = twoDifferentFilters();
    if (f != null) {
      String c1 = page(a, 5, null, f.get(0)).nextCursor();
      if (c1 != null) {
        HttpResult mismatch = list(a, 5, c1, f.get(1));
        assertThat(mismatch.status()).isEqualTo(400);
        assertThat(mismatch.body()).contains("CURSOR_FILTER_MISMATCH");
      }
    }
  }

  @Test
  void limitBounds() throws Exception {
    UUID a = UUID.randomUUID();
    for (int limit : new int[] {0, 101, -1}) {
      HttpResult r = list(a, limit, null, Map.of());
      assertThat(r.status()).as("limit=" + limit).isEqualTo(400);
      assertThat(r.body()).contains("LIMIT_INVALID");
    }
  }

  @Test
  void anotherTenantsCursorStillReturnsOnlyOwnRows() throws Exception {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    seed(a, 40, null);
    seed(b, 40, null);
    String bCursor = page(b, 10, null, null).nextCursor();
    PageView p = page(a, 25, bCursor, null);
    Set<String> aIds = new HashSet<>(expectedOrder(a));
    assertThat(aIds).containsAll(p.ids());
  }

  @Test
  void listQueryUsesTheIndex() throws Exception {
    assertListPlan();
  }
}
