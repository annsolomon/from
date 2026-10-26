package in.dexter.portfolio.infra;

import in.dexter.core.error.DexterException;
import in.dexter.db.page.KeyType;
import in.dexter.db.page.KeysetPager;
import in.dexter.db.page.KeysetQuery;
import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import in.dexter.db.sql.Rows;
import in.dexter.db.sql.Sql;
import in.dexter.db.tenant.TenantScope;
import in.dexter.portfolio.app.StatementStore;
import in.dexter.portfolio.domain.PortfolioErrors;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Statement uploads, parsed headers and lines (RLS: the tenant's rows). */
public final class StatementRepository implements StatementStore {
  static final String UPLOAD_COLS = "id, account_id, user_card_id, file_name, size_bytes, quarantine_key, object_key,"
      + " sha256, status, error_code, error_detail, imported_count, duplicate_count, excluded_count, submitted_at,"
      + " parsed_at, decided_at, created_at, updated_at, version";
  static final RowMapper<UploadRow> UPLOAD = (rs, n) -> new UploadRow(Rows.uuid(rs, "id"), Rows.uuid(rs, "account_id"),
      Rows.uuid(rs, "user_card_id"), rs.getString("file_name"), Rows.nullableLong(rs, "size_bytes"),
      rs.getString("quarantine_key"), rs.getString("object_key"), rs.getBytes("sha256"), rs.getString("status"),
      rs.getString("error_code"), rs.getString("error_detail"), Rows.nullableInt(rs, "imported_count"),
      Rows.nullableInt(rs, "duplicate_count"), Rows.nullableInt(rs, "excluded_count"),
      Rows.instant(rs, "submitted_at"), Rows.instant(rs, "parsed_at"), Rows.instant(rs, "decided_at"),
      Rows.instant(rs, "created_at"), Rows.instant(rs, "updated_at"), rs.getInt("version"));
  static final String PARSED_COLS = "upload_id, account_id, parser_version, format, issuer, last4, period_start,"
      + " period_end, due_date, total_due_paise, min_due_paise, redactions, line_count, debits_paise, credits_paise,"
      + " duplicate_count, parsed_at";
  static final RowMapper<ParsedRow> PARSED = (rs, n) -> new ParsedRow(Rows.uuid(rs, "upload_id"),
      Rows.uuid(rs, "account_id"), rs.getString("parser_version"), rs.getString("format"), rs.getString("issuer"),
      rs.getString("last4"), Rows.date(rs, "period_start"), Rows.date(rs, "period_end"), Rows.date(rs, "due_date"),
      Rows.nullableLong(rs, "total_due_paise"), Rows.nullableLong(rs, "min_due_paise"), rs.getInt("redactions"),
      rs.getInt("line_count"), rs.getLong("debits_paise"), rs.getLong("credits_paise"), rs.getInt("duplicate_count"),
      Rows.instant(rs, "parsed_at"));
  static final String LINE_COLS = "upload_id, line_no, account_id, posted_on, description, amount_paise, direction,"
      + " kind, client_ref, status, card_txn_id";
  static final RowMapper<LineRow> LINE = (rs, n) -> new LineRow(Rows.uuid(rs, "upload_id"), rs.getInt("line_no"),
      Rows.uuid(rs, "account_id"), Rows.date(rs, "posted_on"), rs.getString("description"), rs.getLong("amount_paise"),
      rs.getString("direction"), rs.getString("kind"), rs.getString("client_ref"), rs.getString("status"),
      Rows.uuid(rs, "card_txn_id"));
  /** Rows per multi-row INSERT of lines (11 parameters each, well under the 65,535 bind limit). */
  static final int LINE_BATCH = 500;

  /** The account's uploads newest first (statement_uploads_list); mask 1 = one card (statement_uploads_card_list). */
  static KeysetQuery<UploadRow> listQuery(int mask) {
    return KeysetQuery.<UploadRow>builder("created_desc")
        .direction(KeysetQuery.Direction.DESC)
        .key("created_at", KeyType.TIMESTAMPTZ)
        .key("id", KeyType.UUID)
        .sql("SELECT " + UPLOAD_COLS + " FROM portfolio.statement_uploads"
            + " WHERE account_id = (SELECT portfolio.app_account())" + (mask == 1 ? " AND user_card_id = :card" : ""))
        .mapper(UPLOAD)
        .keysOf(u -> List.of(u.createdAt(), u.id()))
        .build();
  }

  /** One upload's lines in statement order (parsed_lines_pkey). */
  public static final KeysetQuery<LineRow> LINES = KeysetQuery.<LineRow>builder("line_asc")
      .direction(KeysetQuery.Direction.ASC)
      .key("line_no", KeyType.INTEGER)
      .sql("SELECT " + LINE_COLS + " FROM portfolio.parsed_lines"
          + " WHERE account_id = (SELECT portfolio.app_account()) AND upload_id = :upload")
      .mapper(LINE)
      .keysOf(l -> List.of(l.lineNo()))
      .build();

  private final JdbcClient jdbc;
  private final KeysetPager pager;
  private final Map<Integer, KeysetQuery<UploadRow>> lists = new ConcurrentHashMap<>();

  public StatementRepository(JdbcClient jdbc, KeysetPager pager) {
    this.jdbc = jdbc;
    this.pager = pager;
  }

  public static KeysetQuery<UploadRow> list(int mask) {
    return listQuery(mask);
  }

  @Override
  public void insert(UploadRow u) {
    TenantScope.require();
    Map<String, Object> m = new HashMap<>();
    m.put("id", u.id());
    m.put("a", u.accountId());
    m.put("card", u.userCardId());
    m.put("name", u.fileName());
    m.put("size", u.sizeBytes());
    m.put("qk", u.quarantineKey());
    m.put("status", u.status());
    m.put("now", Sql.ts(u.createdAt()));
    jdbc.sql("INSERT INTO portfolio.statement_uploads (id, account_id, user_card_id, file_name, size_bytes,"
            + " quarantine_key, status, created_at, updated_at) VALUES (:id, :a, :card, :name, :size, :qk, :status,"
            + " :now, :now)")
        .params(m).update();
  }

  @Override
  public Optional<UploadRow> find(UUID id) {
    TenantScope.require();
    return jdbc.sql("SELECT " + UPLOAD_COLS + " FROM portfolio.statement_uploads WHERE id = :id").param("id", id)
        .query(UPLOAD).optional();
  }

  @Override
  public Optional<UploadRow> lock(UUID id) {
    TenantScope.require();
    return jdbc.sql("SELECT " + UPLOAD_COLS + " FROM portfolio.statement_uploads WHERE id = :id FOR UPDATE")
        .param("id", id).query(UPLOAD).optional();
  }

  @Override
  public Page<UploadRow> list(PageRequest page, UUID cardId) {
    TenantScope.require();
    int mask = cardId == null ? 0 : 1;
    Map<String, Object> p = cardId == null ? Map.of() : Map.of("card", cardId);
    Map<String, String> f = cardId == null ? Map.of() : Map.of("cardId", cardId.toString());
    return pager.page(jdbc, lists.computeIfAbsent(mask, StatementRepository::listQuery), page, p, f);
  }

  @Override
  public Optional<UUID> sameFile(UUID cardId, byte[] sha256, UUID exceptId) {
    TenantScope.require();
    return jdbc.sql("SELECT id FROM portfolio.statement_uploads WHERE account_id = (SELECT portfolio.app_account())"
            + " AND user_card_id = :card AND sha256 = :sha AND status IN ('submitted', 'parsed', 'confirmed')"
            + " AND id <> :except LIMIT 1")
        .param("card", cardId).param("sha", sha256).param("except", exceptId).query(UUID.class).optional();
  }

  @Override
  public void setScanned(UUID id, String objectKey, byte[] sha256, long sizeBytes, Instant now) {
    TenantScope.require();
    jdbc.sql("UPDATE portfolio.statement_uploads SET object_key = :k, sha256 = :sha, size_bytes = :size,"
            + " updated_at = :now, version = version + 1 WHERE id = :id")
        .param("k", objectKey).param("sha", sha256).param("size", sizeBytes).param("now", Sql.ts(now))
        .param("id", id).update();
  }

  @Override
  public void setStatus(UUID id, String status, String errorCode, String errorDetail, Instant now) {
    TenantScope.require();
    jdbc.sql("UPDATE portfolio.statement_uploads SET status = :s, error_code = :c, error_detail = :d,"
            + " decided_at = CASE WHEN :s IN ('discarded', 'confirmed') THEN CAST(:now AS timestamptz) ELSE decided_at END,"
            + " parsed_at = CASE WHEN :s = 'failed' AND submitted_at IS NOT NULL THEN CAST(:now AS timestamptz)"
            + " ELSE parsed_at END, updated_at = :now, version = version + 1 WHERE id = :id")
        .param("s", status).param("c", errorCode).param("d", errorDetail).param("now", Sql.ts(now)).param("id", id)
        .update();
  }

  @Override
  public void markSubmitted(UUID id, Instant now) {
    TenantScope.require();
    try {
      jdbc.sql("UPDATE portfolio.statement_uploads SET status = 'submitted', submitted_at = :now, updated_at = :now,"
              + " version = version + 1 WHERE id = :id")
          .param("now", Sql.ts(now)).param("id", id).update();
    } catch (DuplicateKeyException e) {
      // statement_uploads_same_file: a concurrent upload of the same file for the card won
      throw new DexterException(PortfolioErrors.STATEMENT_ALREADY_UPLOADED,
          "This file was already uploaded for this card", e);
    }
  }

  @Override
  public void markParsed(UUID id, Instant now) {
    TenantScope.require();
    jdbc.sql("UPDATE portfolio.statement_uploads SET status = 'parsed', parsed_at = :now, updated_at = :now,"
            + " version = version + 1 WHERE id = :id")
        .param("now", Sql.ts(now)).param("id", id).update();
  }

  @Override
  public void markConfirmed(UUID id, int imported, int duplicates, int excluded, Instant now) {
    TenantScope.require();
    jdbc.sql("UPDATE portfolio.statement_uploads SET status = 'confirmed', imported_count = :i, duplicate_count = :d,"
            + " excluded_count = :x, decided_at = :now, updated_at = :now, version = version + 1 WHERE id = :id")
        .param("i", imported).param("d", duplicates).param("x", excluded).param("now", Sql.ts(now)).param("id", id)
        .update();
  }

  @Override
  public void insertParsed(ParsedRow p) {
    TenantScope.require();
    Map<String, Object> m = new HashMap<>();
    m.put("u", p.uploadId());
    m.put("a", p.accountId());
    m.put("pv", p.parserVersion());
    m.put("fmt", p.format());
    m.put("iss", p.issuer());
    m.put("l4", p.last4());
    m.put("ps", p.periodStart());
    m.put("pe", p.periodEnd());
    m.put("due", p.dueDate());
    m.put("tot", p.totalDuePaise());
    m.put("min", p.minDuePaise());
    m.put("red", p.redactions());
    m.put("n", p.lineCount());
    m.put("deb", p.debitsPaise());
    m.put("cre", p.creditsPaise());
    m.put("dup", p.duplicateCount());
    m.put("at", Sql.ts(p.parsedAt()));
    jdbc.sql("INSERT INTO portfolio.parsed_statements (" + PARSED_COLS + ") VALUES (:u, :a, :pv, :fmt, :iss, :l4,"
            + " :ps, :pe, :due, :tot, :min, :red, :n, :deb, :cre, :dup, :at)")
        .params(m).update();
  }

  @Override
  public Optional<ParsedRow> parsed(UUID uploadId) {
    TenantScope.require();
    return jdbc.sql("SELECT " + PARSED_COLS + " FROM portfolio.parsed_statements WHERE upload_id = :u")
        .param("u", uploadId).query(PARSED).optional();
  }

  @Override
  public void insertLines(List<LineRow> lines) {
    TenantScope.require();
    for (int from = 0; from < lines.size(); from += LINE_BATCH) {
      List<LineRow> chunk = lines.subList(from, Math.min(lines.size(), from + LINE_BATCH));
      StringBuilder sql = new StringBuilder("INSERT INTO portfolio.parsed_lines (" + LINE_COLS + ") VALUES ");
      Map<String, Object> m = new HashMap<>();
      for (int i = 0; i < chunk.size(); i++) {
        LineRow l = chunk.get(i);
        if (i > 0) {
          sql.append(", ");
        }
        sql.append("(:u").append(i).append(", :n").append(i).append(", :a").append(i).append(", :d").append(i)
            .append(", :t").append(i).append(", :p").append(i).append(", :dir").append(i).append(", :k").append(i)
            .append(", :r").append(i).append(", :s").append(i).append(", :x").append(i).append(')');
        m.put("u" + i, l.uploadId());
        m.put("n" + i, l.lineNo());
        m.put("a" + i, l.accountId());
        m.put("d" + i, l.postedOn());
        m.put("t" + i, l.description());
        m.put("p" + i, l.amountPaise());
        m.put("dir" + i, l.direction());
        m.put("k" + i, l.kind());
        m.put("r" + i, l.clientRef());
        m.put("s" + i, l.status());
        m.put("x" + i, l.cardTxnId());
      }
      jdbc.sql(sql.toString()).params(m).update();
    }
  }

  @Override
  public Page<LineRow> lines(PageRequest page, UUID uploadId) {
    TenantScope.require();
    // the path id scopes the rows; the cursor binds query filters only
    return pager.page(jdbc, LINES, page, Map.of("upload", uploadId), Map.of());
  }

  @Override
  public List<LineRow> allLines(UUID uploadId) {
    TenantScope.require();
    return jdbc.sql("SELECT " + LINE_COLS + " FROM portfolio.parsed_lines WHERE account_id ="
            + " (SELECT portfolio.app_account()) AND upload_id = :u ORDER BY line_no LIMIT 2000")
        .param("u", uploadId).query(LINE).list();
  }

  @Override
  public void setLineStatus(UUID uploadId, int lineNo, String status, UUID cardTxnId) {
    TenantScope.require();
    jdbc.sql("UPDATE portfolio.parsed_lines SET status = :s, card_txn_id = :t WHERE upload_id = :u AND line_no = :n")
        .param("s", status).param("t", cardTxnId).param("u", uploadId).param("n", lineNo).update();
  }

  @Override
  public Set<String> existingRefs(Collection<String> refs) {
    TenantScope.require();
    Set<String> out = new HashSet<>();
    List<String> all = new ArrayList<>(refs);
    for (int from = 0; from < all.size(); from += 1000) {
      List<String> chunk = all.subList(from, Math.min(all.size(), from + 1000));
      out.addAll(jdbc.sql("SELECT client_ref FROM portfolio.card_transactions WHERE account_id ="
              + " (SELECT portfolio.app_account()) AND client_ref = ANY(:refs)")
          .param("refs", Sql.texts(chunk)).query(String.class).list());
    }
    return out;
  }
}
