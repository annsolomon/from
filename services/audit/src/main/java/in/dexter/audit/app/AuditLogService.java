package in.dexter.audit.app;

import in.dexter.audit.app.AuditStore.Entry;
import in.dexter.audit.app.AuditStore.Filter;
import in.dexter.audit.app.AuditStore.Head;
import in.dexter.audit.app.AuditViews.EntryView;
import in.dexter.audit.app.AuditViews.VerifyView;
import in.dexter.audit.domain.AuditErrors;
import in.dexter.audit.domain.ChainVerifier;
import in.dexter.core.error.DexterException;
import in.dexter.core.tenant.Tenant;
import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import in.dexter.db.tenant.TenantTx;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import tools.jackson.databind.json.JsonMapper;

/**
 * The audit log for operators (MASTER_PLAN P18): the keyset list with filters, the chain verification, and the
 * anchor job. Reads run on the admin pool as the operator (RLS: operators only); appends go through
 * {@code audit.append}, the single writer, from whichever pool the caller is on.
 */
public final class AuditLogService {
  private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);
  public static final String PATH = "/v1/admin/audit";
  public static final byte[] GENESIS = sha256("dexter-audit-genesis");
  static final int BATCH = 2_000;

  private final TenantTx adminTx;
  private final AuditStore adminStore;
  private final TenantTx appTx;
  private final AuditStore appStore;
  private final ExportStore exports;
  private final JsonMapper mapper;
  private final Clock clock;

  public AuditLogService(TenantTx adminTx, AuditStore adminStore, TenantTx appTx, AuditStore appStore,
      ExportStore exports, JsonMapper mapper, Clock clock) {
    this.adminTx = adminTx;
    this.adminStore = adminStore;
    this.appTx = appTx;
    this.appStore = appStore;
    this.exports = exports;
    this.mapper = mapper;
    this.clock = clock;
  }

  public Page<EntryView> list(Tenant operator, Filter filter, PageRequest page) {
    if (filter.targetId() != null && filter.targetType() == null) {
      throw new DexterException(AuditErrors.AUDIT_FILTER_INVALID, "targetId needs targetType");
    }
    if (filter.from() != null && filter.to() != null && !filter.from().isBefore(filter.to())) {
      throw new DexterException(AuditErrors.AUDIT_FILTER_INVALID, "from must be before to");
    }
    return adminTx.read(operator, () -> adminStore.page(filter, page)).map(r -> new EntryView(r.seq(),
        r.occurredAt().toString(), r.id(), r.actorType(), r.actorId(), r.action(), r.targetType(), r.targetId(),
        r.requestId(), mapper.readTree(r.detailsJson()), HexFormat.of().formatHex(r.hash())));
  }

  /**
   * Walks the whole chain up to the head read first (rows appended meanwhile are left for the next run), checks
   * every anchor against its row and its exported copy, and audits the run itself.
   */
  public VerifyView verify(Tenant operator, String operatorId) {
    long t0 = System.nanoTime();
    Head head = adminTx.read(operator, adminStore::head);
    Map<Long, byte[]> anchors = adminTx.read(operator, adminStore::anchors);
    ChainVerifier v = new ChainVerifier(GENESIS, anchors);
    long from = 1;
    while (from <= head.seq()) {
      long start = from;
      List<ChainVerifier.Row> rows = adminTx.read(operator, () -> adminStore.chain(start, BATCH)).stream()
          .filter(r -> r.seq() <= head.seq()).toList();
      if (rows.isEmpty()) {
        break;
      }
      boolean ok = true;
      for (ChainVerifier.Row r : rows) {
        if (!v.accept(r)) {
          ok = false;
          break;
        }
      }
      if (!ok) {
        break;
      }
      from = v.nextSeq();
    }
    ChainVerifier.Result result = v.finish(head.seq(), head.hash());
    int exported = 0;
    if (result.ok()) {
      for (Map.Entry<Long, byte[]> a : anchors.entrySet()) {
        if (a.getKey() == 0 || a.getKey() > head.seq()) {
          continue;
        }
        String reason = exportedAnchorProblem(a.getKey(), a.getValue());
        if (reason != null) {
          result = new ChainVerifier.Result(false, result.checked(), result.headSeq(), result.headHash(), a.getKey(),
              reason, result.anchorsChecked());
          break;
        }
        exported++;
      }
    }
    long ms = (System.nanoTime() - t0) / 1_000_000;
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("ok", result.ok());
    details.put("checked", result.checked());
    details.put("headSeq", result.headSeq());
    if (result.brokenAt() != null) {
      details.put("brokenAt", result.brokenAt());
    }
    adminTx.writeVoid(operator, () -> adminStore.append(new Entry("admin", operatorId, "audit.chain.verified", null,
        null, null, MDC.get("requestId"), details)));
    log.info("AUDIT_CHAIN_VERIFIED ok={} checked={} head={} brokenAt={}", result.ok(), result.checked(),
        result.headSeq(), result.brokenAt());
    return VerifyView.of(result, exported, ms);
  }

  private @Nullable String exportedAnchorProblem(long seq, byte[] hash) {
    try {
      Map<?, ?> copy = mapper.readValue(exports.get(anchorKey(seq)), Map.class);
      Object h = copy.get("hash");
      if (h == null || !Arrays.equals(HexFormat.of().parseHex(h.toString()), hash)) {
        return "the exported copy of anchor " + seq + " differs from the stored anchor";
      }
      return null;
    } catch (ExportStore.Missing e) {
      return "the exported copy of anchor " + seq + " is missing";
    }
  }

  /**
   * The anchor job (SYSTEM on the app pool): anchors the current head when it moved since the last anchor, and
   * exports it first — a stored anchor always has its copy. Returns the anchored seq, or -1 when nothing moved.
   */
  public long anchorNow() {
    return appTx.write(AuditTenants.SYSTEM, () -> {
      Head head = appStore.head();
      if (head.seq() == 0 || head.seq() <= appStore.lastAnchorSeq()) {
        return -1L;
      }
      String key = anchorKey(head.seq());
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("seq", head.seq());
      body.put("hash", HexFormat.of().formatHex(head.hash()));
      body.put("anchoredAt", Instant.now(clock).toString());
      exports.put(key, mapper.writeValueAsBytes(body), "application/json");
      appStore.insertAnchor(head.seq(), head.hash(), key);
      log.info("AUDIT_ANCHORED seq={} key={}", head.seq(), key);
      return head.seq();
    });
  }

  public static String anchorKey(long seq) {
    return String.format("anchors/%012d.json", seq);
  }

  static byte[] sha256(String s) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
