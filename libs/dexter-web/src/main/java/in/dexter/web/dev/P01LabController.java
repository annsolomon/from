package in.dexter.web.dev;

import in.dexter.core.error.CoreErrors;
import in.dexter.core.error.DexterException;
import in.dexter.core.error.VersionConflictException;
import in.dexter.core.id.Uuids;
import in.dexter.core.log.PiiMasker;
import in.dexter.core.money.Bps;
import in.dexter.core.money.Money;
import in.dexter.core.validation.Require;
import in.dexter.db.page.CursorKeyRing;
import in.dexter.db.page.CursorPosition;
import in.dexter.db.page.KeyType;
import in.dexter.db.page.KeysetCursorCodec;
import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import in.dexter.web.etag.ETags;
import in.dexter.web.etag.IfMatchVersion;
import in.dexter.web.page.PageResponse;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * THROWAWAY dev-only lab for the P01 trail page (MASTER_PLAN 11.2). Registered only under the
 * {@code dev} profile (DexterWebAutoConfiguration, guarded by a test). Exercises the real library
 * code: Money/Bps, the signed cursor codec + keyset paging, ProblemDetail codes, strict JSON and
 * If-Match. Removed in P19 with the trail.
 */
@RestController
@RequestMapping("/_dev/p01")
public class P01LabController {
  /** Lab-only keys (not secrets: this controller never runs outside dev). */
  static final byte[] LAB_K1 = "p01-lab-key-c1-0123456789abcdefghij".getBytes(StandardCharsets.UTF_8);
  static final byte[] LAB_K2 = "p01-lab-key-c2-0123456789abcdefghij".getBytes(StandardCharsets.UTF_8);
  static final String SORT = "occurred_desc";

  record LabItem(UUID id, Instant occurredAt, long amountPaise, String category) {}

  record LabBody(String name, long amountPaise) {}

  record CursorEncodeRequest(String ring, List<String> keys, Map<String, String> filters) {}

  record CursorDecodeRequest(String ring, String cursor, Map<String, String> filters) {}

  private final List<LabItem> items = new ArrayList<>();
  private final AtomicLong docVersion = new AtomicLong(0);

  public P01LabController() {
    // 120 deterministic items; 40 share one timestamp to show the id tie-breaker.
    Instant base = Instant.parse("2026-09-01T10:00:00Z");
    for (int i = 0; i < 120; i++) {
      UUID id = new UUID(0x0192f0c17d3e7000L | i, 0x8000000000000000L | (i * 7919L));
      Instant t = i < 40 ? base : base.minusSeconds(60L * i);
      items.add(new LabItem(id, t, 1000L + i * 37, i % 3 == 0 ? "food" : "travel"));
    }
    // (occurred_at, id) DESC with the id compared the way Postgres compares uuid (unsigned bytes).
    items.sort(Comparator.comparing(LabItem::occurredAt)
        .thenComparing(LabItem::id, Uuids.POSTGRES_ORDER).reversed());
  }

  static KeysetCursorCodec codec(String ring) {
    return switch (ring == null ? "c1" : ring) {
      case "c1" -> new KeysetCursorCodec(new CursorKeyRing("c1", Map.of("c1", LAB_K1)));
      case "c2" -> new KeysetCursorCodec(new CursorKeyRing("c2", Map.of("c1", LAB_K1, "c2", LAB_K2)));
      case "c2-only" -> new KeysetCursorCodec(new CursorKeyRing("c2", Map.of("c2", LAB_K2)));
      default -> throw new DexterException(CoreErrors.BAD_REQUEST, "ring must be c1, c2 or c2-only");
    };
  }

  @GetMapping("/money")
  public Map<String, Object> money(@RequestParam long paise, @RequestParam int bps,
      @RequestParam(defaultValue = "HALF_EVEN") String rounding) {
    RoundingMode mode;
    try {
      mode = RoundingMode.valueOf(rounding);
    } catch (IllegalArgumentException e) {
      throw new DexterException(CoreErrors.BAD_REQUEST, "unknown rounding mode");
    }
    Money m = Money.ofPaise(paise);
    Bps rate;
    try {
      rate = Bps.of(bps);
    } catch (IllegalArgumentException e) {
      throw new DexterException(CoreErrors.BAD_REQUEST, "bps out of range");
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("inputPaise", paise);
    out.put("rateBps", bps);
    out.put("rate", rate.toString());
    out.put("rounding", mode.name());
    out.put("exactPaise", java.math.BigDecimal.valueOf(paise).multiply(rate.asFraction()).toPlainString());
    out.put("resultPaise", m.times(rate, mode).paise());
    out.put("display", m.times(rate, mode).toString());
    return out;
  }

  @PostMapping("/cursor/encode")
  public Map<String, Object> encode(@RequestBody CursorEncodeRequest req) {
    List<String> keys = req.keys() == null ? List.of() : req.keys();
    String cursor = codec(req.ring()).encode(SORT, keys, KeysetCursorCodec.filterHash(req.filters()));
    return Map.of("cursor", cursor, "payload", decodePayload(cursor));
  }

  @PostMapping("/cursor/decode")
  public Map<String, Object> decode(@RequestBody CursorDecodeRequest req) {
    CursorPosition p = codec(req.ring()).decode(req.cursor(), SORT, KeysetCursorCodec.filterHash(req.filters()));
    return Map.of("sort", p.sort(), "keys", p.keys(), "kid", p.kid());
  }

  /** In-memory keyset paging with the real cursor codec and PageResponse (no database). */
  @GetMapping("/items")
  public PageResponse<LabItem> items(PageRequest page, @RequestParam(required = false) String category) {
    Map<String, String> filters = category == null ? Map.of() : Map.of("category", category);
    KeysetCursorCodec c = codec("c1");
    String fhash = KeysetCursorCodec.filterHash(filters);
    List<LabItem> source = items.stream().filter(i -> category == null || i.category().equals(category)).toList();
    int start = 0;
    if (!page.isFirst()) {
      CursorPosition pos = c.decode(page.cursor(), SORT, fhash);
      if (pos.keys().size() != 2) {
        throw new DexterException(CoreErrors.CURSOR_INVALID, "The cursor is invalid or has expired");
      }
      Instant t;
      UUID id;
      try {
        t = ((java.time.OffsetDateTime) KeyType.TIMESTAMPTZ.decode(pos.keys().get(0))).toInstant();
        id = (UUID) KeyType.UUID.decode(pos.keys().get(1));
      } catch (IllegalArgumentException e) {
        throw new DexterException(CoreErrors.CURSOR_INVALID, "The cursor is invalid or has expired");
      }
      while (start < source.size() && !isBelow(source.get(start), t, id)) {
        start++;
      }
    }
    int end = Math.min(source.size(), start + page.limit() + 1);
    List<LabItem> rows = new ArrayList<>(source.subList(start, end));
    boolean hasMore = rows.size() > page.limit();
    if (hasMore) {
      rows = new ArrayList<>(rows.subList(0, page.limit()));
    }
    String next = null;
    if (hasMore) {
      LabItem last = rows.getLast();
      next = c.encode(SORT, List.of(KeyType.TIMESTAMPTZ.encode(last.occurredAt()), last.id().toString()), fhash);
    }
    return PageResponse.from(new Page<>(rows, page.limit(), hasMore, next));
  }

  private static boolean isBelow(LabItem i, Instant t, UUID id) {
    int c = i.occurredAt().compareTo(t);
    return c < 0 || (c == 0 && Uuids.compare(i.id(), id) < 0);
  }

  @GetMapping("/errors/{code}")
  public Map<String, Object> error(@PathVariable String code) {
    CoreErrors e = CoreErrors.byCode(code);
    if (e == null) {
      throw new DexterException(CoreErrors.NOT_FOUND, "no such error code");
    }
    if (e == CoreErrors.VALIDATION_FAILED) {
      Require.check().notBlank("", "name").positive(0, "amountPaise").orThrow();
    }
    throw new DexterException(e, "lab-triggered " + e.code());
  }

  @PostMapping("/strict-json")
  public LabBody strictJson(@RequestBody LabBody body) {
    Require.check().notBlank(body.name(), "name").maxLength(body.name(), 40, "name")
        .positive(body.amountPaise(), "amountPaise").orThrow();
    return body;
  }

  @GetMapping("/doc")
  public ResponseEntity<Map<String, Object>> doc() {
    long v = docVersion.get();
    return ResponseEntity.ok().eTag(ETags.of(v)).body(Map.of("version", v));
  }

  @PutMapping("/doc")
  public ResponseEntity<Map<String, Object>> putDoc(@IfMatchVersion long expected) {
    if (!docVersion.compareAndSet(expected, expected + 1)) {
      throw new VersionConflictException("lab doc", expected);
    }
    long v = expected + 1;
    return ResponseEntity.ok().eTag(ETags.of(v)).body(Map.of("version", v));
  }

  @GetMapping("/mask")
  public Map<String, String> mask(@RequestParam String text) {
    return Map.of("masked", PiiMasker.mask(text));
  }

  private static String decodePayload(String cursor) {
    return new String(Base64.getUrlDecoder().decode(cursor.substring(0, cursor.indexOf('.'))), StandardCharsets.UTF_8);
  }
}
