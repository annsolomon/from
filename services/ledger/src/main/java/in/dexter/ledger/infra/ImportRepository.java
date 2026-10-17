package in.dexter.ledger.infra;

import in.dexter.db.sql.Rows;
import in.dexter.db.sql.Sql;
import in.dexter.db.tenant.TenantScope;
import in.dexter.ledger.app.ImportStore;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceUtils;

/**
 * Import jobs and their row errors (RLS), the cross-tenant claim ({@code ledger.import_claim_next}, SECURITY
 * DEFINER), and CSV staging: {@code COPY ... FROM STDIN (FORMAT csv, HEADER MATCH)} into a temporary table that
 * lives until the end of the transaction. COPY FROM cannot target a table with row-level security; a temporary
 * table is private to the session, so the account boundary is the transaction's (MASTER_PLAN P08).
 */
public final class ImportRepository implements ImportStore {
  /** The CSV header, exactly (COPY ... HEADER MATCH checks the names and their order). */
  public static final String HEADER = "date,amount,merchant,category,payment_method,note,client_ref";
  static final String COLS = "id, account_id, status, file_key, file_name, total, ok, duplicates, failed, error_code,"
      + " error_detail, attempts, created_at, started_at, finished_at, version";
  static final RowMapper<Job> JOB = (rs, i) -> new Job(Rows.uuid(rs, "id"), Rows.uuid(rs, "account_id"),
      rs.getString("status"), rs.getString("file_key"), rs.getString("file_name"), rs.getInt("total"),
      rs.getInt("ok"), rs.getInt("duplicates"), rs.getInt("failed"), rs.getString("error_code"),
      rs.getString("error_detail"), rs.getInt("attempts"), Rows.instant(rs, "created_at"),
      Rows.instant(rs, "started_at"), Rows.instant(rs, "finished_at"), rs.getLong("version"));

  private final JdbcClient jdbc;
  private final DataSource dataSource;

  public ImportRepository(JdbcClient jdbc, DataSource dataSource) {
    this.jdbc = jdbc;
    this.dataSource = dataSource;
  }

  @Override
  public void insert(Job j) {
    TenantScope.require();
    jdbc.sql("INSERT INTO ledger.import_jobs (id, account_id, status, file_key, file_name, created_at, updated_at)"
            + " VALUES (:id, :a, :s, :k, :n, :c, :c)")
        .param("id", j.id()).param("a", j.accountId()).param("s", j.status()).param("k", j.fileKey())
        .param("n", j.fileName()).param("c", Sql.ts(j.createdAt()))
        .update();
  }

  @Override
  public Optional<Job> find(UUID id) {
    TenantScope.require();
    return jdbc.sql("SELECT " + COLS + " FROM ledger.import_jobs WHERE id = :id").param("id", id).query(JOB).optional();
  }

  @Override
  public Optional<Job> lock(UUID id) {
    TenantScope.require();
    return jdbc.sql("SELECT " + COLS + " FROM ledger.import_jobs WHERE id = :id FOR UPDATE").param("id", id)
        .query(JOB).optional();
  }

  @Override
  public void setStatus(UUID id, String status) {
    TenantScope.require();
    jdbc.sql("UPDATE ledger.import_jobs SET status = :s, updated_at = now(), version = version + 1 WHERE id = :id")
        .param("s", status).param("id", id).update();
  }

  @Override
  public void finish(UUID id, String status, int total, int ok, int duplicates, int failed, String errorCode,
      String errorDetail, Instant finishedAt) {
    TenantScope.require();
    jdbc.sql("UPDATE ledger.import_jobs SET status = :s, total = :t, ok = :ok, duplicates = :d, failed = :f,"
            + " error_code = :ec, error_detail = :ed, finished_at = :at, updated_at = :at, version = version + 1"
            + " WHERE id = :id")
        .param("s", status).param("t", total).param("ok", ok).param("d", duplicates).param("f", failed)
        .param("ec", errorCode).param("ed", errorDetail).param("at", Sql.ts(finishedAt)).param("id", id)
        .update();
  }

  @Override
  public void insertErrors(UUID jobId, UUID accountId, List<RowError> errors) {
    TenantScope.require();
    for (int from = 0; from < errors.size(); from += 500) {
      List<RowError> chunk = errors.subList(from, Math.min(errors.size(), from + 500));
      StringBuilder sql = new StringBuilder("INSERT INTO ledger.import_errors (job_id, account_id, row_no, code, detail)"
          + " VALUES ");
      Map<String, Object> p = new HashMap<>();
      p.put("j", jobId);
      p.put("a", accountId);
      for (int i = 0; i < chunk.size(); i++) {
        if (i > 0) {
          sql.append(", ");
        }
        sql.append("(:j, :a, :r").append(i).append(", :c").append(i).append(", :d").append(i).append(")");
        p.put("r" + i, chunk.get(i).rowNo());
        p.put("c" + i, chunk.get(i).code());
        p.put("d" + i, chunk.get(i).detail());
      }
      jdbc.sql(sql.toString()).params(p).update();
    }
  }

  @Override
  public List<RowError> errors(UUID jobId, int limit) {
    TenantScope.require();
    return jdbc.sql("SELECT row_no, code, detail FROM ledger.import_errors WHERE job_id = :j ORDER BY row_no LIMIT :l")
        .param("j", jobId).param("l", limit)
        .query((rs, i) -> new RowError(rs.getInt("row_no"), rs.getString("code"), rs.getString("detail")))
        .list();
  }

  @Override
  public Optional<Claim> claim(Duration stale, int maxAttempts) {
    return jdbc.sql("SELECT job_id, account_id, file_key, attempts FROM ledger.import_claim_next("
            + "CAST(:stale AS interval), :max)")
        .param("stale", stale.toSeconds() + " seconds").param("max", maxAttempts)
        .query((rs, i) -> new Claim(Rows.uuid(rs, "job_id"), Rows.uuid(rs, "account_id"), rs.getString("file_key"),
            rs.getInt("attempts")))
        .optional();
  }

  @Override
  public List<StagedRow> stage(InputStream csv, long maxBytes, int maxRows) {
    TenantScope.require();
    jdbc.sql("CREATE TEMPORARY TABLE import_rows (row_no integer GENERATED ALWAYS AS IDENTITY, date text,"
        + " amount text, merchant text, category text, payment_method text, note text, client_ref text)"
        + " ON COMMIT DROP").update();
    Limited in = new Limited(csv, maxBytes);
    Connection con = DataSourceUtils.getConnection(dataSource);
    try {
      con.unwrap(PGConnection.class).getCopyAPI().copyIn("COPY pg_temp.import_rows (date, amount, merchant,"
          + " category, payment_method, note, client_ref) FROM STDIN WITH (FORMAT csv, HEADER MATCH, ENCODING 'UTF8')",
          in);
    } catch (SQLException | IOException e) {
      if (in.exceeded) {
        throw new InvalidFile("FILE_TOO_LARGE", "The file is larger than " + maxBytes + " bytes");
      }
      throw new InvalidFile("CSV_INVALID", detail(e));
    } finally {
      DataSourceUtils.releaseConnection(con, dataSource);
    }
    List<StagedRow> rows = jdbc.sql("SELECT row_no, date, amount, merchant, category, payment_method, note, client_ref"
            + " FROM pg_temp.import_rows ORDER BY row_no LIMIT :l")
        .param("l", maxRows + 1)
        .query((rs, i) -> new StagedRow(rs.getInt("row_no"), rs.getString("date"), rs.getString("amount"),
            rs.getString("merchant"), rs.getString("category"), rs.getString("payment_method"), rs.getString("note"),
            rs.getString("client_ref")))
        .list();
    if (rows.size() > maxRows) {
      throw new InvalidFile("TOO_MANY_ROWS", "At most " + maxRows + " rows per file");
    }
    return rows;
  }

  /** The database's first line, without the statement context (it can quote file content). */
  static String detail(Exception e) {
    String m = String.valueOf(e.getMessage());
    int nl = m.indexOf('\n');
    m = nl < 0 ? m : m.substring(0, nl);
    m = m.replaceFirst("^ERROR: ", "");
    java.util.regex.Matcher line = java.util.regex.Pattern.compile("COPY import_rows, line (\\d+)")
        .matcher(String.valueOf(e.getMessage()));
    if (line.find()) {
      m = m + " (file line " + line.group(1) + ")";
    }
    return m.length() > 300 ? m.substring(0, 300) : m;
  }

  /** Counts bytes and fails the COPY once the file is larger than allowed. */
  static final class Limited extends FilterInputStream {
    private final long max;
    private long read;
    volatile boolean exceeded;

    Limited(InputStream in, long max) {
      super(in);
      this.max = max;
    }

    @Override
    public int read() throws IOException {
      int b = super.read();
      if (b >= 0) {
        count(1);
      }
      return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      int n = super.read(b, off, len);
      if (n > 0) {
        count(n);
      }
      return n;
    }

    private void count(long n) throws IOException {
      read += n;
      if (read > max) {
        exceeded = true;
        throw new IOException("file larger than " + max + " bytes");
      }
    }
  }
}
