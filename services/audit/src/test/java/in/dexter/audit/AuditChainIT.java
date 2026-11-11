package in.dexter.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.dexter.audit.app.AuditLogService;
import in.dexter.audit.support.AuditIT;
import in.dexter.contracts.events.identity.AccountRegisteredV1;
import in.dexter.contracts.events.identity.AdminActionV1;
import in.dexter.contracts.events.identity.SessionRevokedV1;
import in.dexter.core.id.Ids;
import in.dexter.testkit.containers.ServiceDb;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * The audit log (MASTER_PLAN P18): entries from events, the operator's keyset list and filters, the hash chain and
 * its verification — the named test: <b>a tampered row breaks chain verification</b> — and the anchors that catch a
 * rewrite of every later row.
 */
class AuditChainIT extends AuditIT {
  @Autowired
  AuditLogService log;

  UUID registered(UUID account) {
    ingest.onEvent(envelope(AccountRegisteredV1.TYPE, 1, "identity", account,
        new AccountRegisteredV1(account, "Asia/Kolkata", Instant.now().toString())));
    return account;
  }

  JsonNode verify() {
    Res r = send("POST", "/v1/admin/audit:verify-chain", null, "Authorization", "Bearer " + operator("audit:read"));
    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    return r.json();
  }

  long seqOf(String action, String targetId) {
    return count("SELECT max(seq) FROM audit.audit_log WHERE action = ? AND target_id = ?", action, targetId);
  }

  @Test
  void eventsBecomeEntriesOnceAndOperatorsPageThem() {
    UUID account = registered(Ids.newId());
    var replay = envelope(SessionRevokedV1.TYPE, 1, "identity", account,
        new SessionRevokedV1(Ids.newId(), "logout", Instant.now().toString()));
    ingest.onEvent(replay);
    ingest.onEvent(replay); // a redelivery: the inbox makes it a no-op
    ingest.onEvent(envelope(AdminActionV1.TYPE, 1, "identity", null, new AdminActionV1("ACCOUNT_LOOKUP", OPERATOR,
        "ACCOUNT", account, "support ticket 42", Instant.now().toString())));
    assertThat(count("SELECT count(1) FROM audit.audit_log WHERE target_id = ?", account.toString()))
        .as("registered + the admin lookup").isEqualTo(2);
    assertThat(count("SELECT count(1) FROM audit.audit_log WHERE actor_id = ? AND action = ?", account.toString(),
        SessionRevokedV1.TYPE)).as("the session revocation, once").isEqualTo(1);

    // the operator's list: newest first, filters, pages that never repeat
    for (int i = 0; i < 7; i++) {
      registered(account);
    }
    String path = "/v1/admin/audit?limit=3&targetType=account&targetId=" + account;
    List<Long> seqs = new ArrayList<>();
    String cursor = null;
    do {
      Res page = send("GET", path + (cursor == null ? "" : "&cursor=" + cursor), null, "Authorization",
          "Bearer " + operator("audit:read"));
      assertThat(page.status()).as(page.toString()).isEqualTo(200);
      page.json().get("data").forEach(e -> {
        seqs.add(e.get("seq").asLong());
        assertThat(e.get("hash").asString()).hasSize(64);
        assertThat(e.get("details").get("source").asString()).isEqualTo("identity");
      });
      JsonNode next = page.json().get("page").get("nextCursor");
      cursor = next == null || next.isNull() ? null : next.asString();
    } while (cursor != null);
    assertThat(seqs).hasSize(9).doesNotHaveDuplicates().isSortedAccordingTo((a, b) -> Long.compare(b, a));
    Res actor = send("GET", "/v1/admin/audit?actorId=" + OPERATOR, null, "Authorization",
        "Bearer " + operator("audit:read"));
    assertThat(actor.json().get("data")).isNotEmpty();
    actor.json().get("data").forEach(e -> assertThat(e.get("actorId").asString()).isEqualTo(OPERATOR.toString()));

    // who may read it
    assertThat(send("GET", "/v1/admin/audit", null, "Authorization", "Bearer " + token(account)).status())
        .isEqualTo(403);
    assertThat(send("GET", "/v1/admin/audit", null, "Authorization", "Bearer " + operator("accounts:read")).status())
        .isEqualTo(403);
    assertThat(send("GET", "/v1/admin/audit?targetId=x", null, "Authorization", "Bearer " + operator("audit:read"))
        .code()).isEqualTo("AUDIT_FILTER_INVALID");
    assertThat(send("GET", "/v1/admin/audit?from=yesterday", null, "Authorization",
        "Bearer " + operator("audit:read")).code()).isEqualTo("AUDIT_FILTER_INVALID");
  }

  @Test
  void theLogIsAppendOnlyForEveryRole() throws Exception {
    registered(Ids.newId());
    for (String role : List.of("app", "admin", "relay", "readonly")) {
      try (Connection c = DriverManager.getConnection(DB.url(), DB.role(role), ServiceDb.PW);
          Statement s = c.createStatement()) {
        // readonly sessions are read-only transactions as well: refused either way
        assertThatThrownBy(() -> s.executeUpdate("UPDATE audit.audit_log SET action = 'x.y'")).as(role)
            .hasMessageMatching("(?s).*(permission denied|read-only transaction).*");
        assertThatThrownBy(() -> s.executeUpdate("DELETE FROM audit.audit_log")).as(role)
            .hasMessageMatching("(?s).*(permission denied|read-only transaction).*");
        assertThatThrownBy(() -> s.executeUpdate("INSERT INTO audit.audit_log (seq, occurred_at, id, actor_type,"
            + " action, prev_hash, hash) VALUES (999999, now(), gen_random_uuid(), 'system', 'x.y', sha256('a'),"
            + " sha256('b'))")).as(role).hasMessageMatching("(?s).*(permission denied|read-only transaction).*");
      }
    }
    // even the owner and the superuser meet the trigger
    assertThatThrownBy(() -> ADMIN.sql("DELETE FROM audit.audit_log").update())
        .hasMessageContaining("append-only");
  }

  @Test
  void seqIsGapFreeUnderConcurrentAppends() throws Exception {
    long before = count("SELECT seq FROM audit.chain_head");
    List<Thread> threads = new ArrayList<>();
    for (int t = 0; t < 8; t++) {
      threads.add(Thread.ofVirtual().start(() -> {
        for (int i = 0; i < 10; i++) {
          registered(Ids.newId());
        }
      }));
    }
    for (Thread t : threads) {
      t.join();
    }
    long after = count("SELECT seq FROM audit.chain_head");
    assertThat(after - before).isEqualTo(80);
    Set<Long> seqs = new HashSet<>(ADMIN.sql("SELECT seq FROM audit.audit_log WHERE seq > ?").param(1, before)
        .query(Long.class).list());
    assertThat(seqs).hasSize(80);
    assertThat(verify().get("ok").asBoolean()).isTrue();
  }

  /** The named test. */
  @Test
  void aTamperedRowBreaksChainVerification() {
    UUID victim = registered(Ids.newId());
    registered(Ids.newId());
    JsonNode clean = verify();
    assertThat(clean.get("ok").asBoolean()).as(clean.toString()).isTrue();
    assertThat(clean.get("checked").asLong()).isPositive();
    long seq = seqOf(AccountRegisteredV1.TYPE, victim.toString());

    // a superuser edits one row's details behind the trigger's back
    try {
      tamper("UPDATE audit.audit_log SET details = details || '{\"note\":\"edited\"}' WHERE seq = " + seq);
      JsonNode broken = verify();
      assertThat(broken.get("ok").asBoolean()).isFalse();
      assertThat(broken.get("brokenAt").asLong()).isEqualTo(seq);
      assertThat(broken.get("reason").asString()).contains("content does not match its hash");
    } finally {
      tamper("UPDATE audit.audit_log SET details = details - 'note' WHERE seq = " + seq);
    }
    assertThat(verify().get("ok").asBoolean()).as("restored").isTrue();

    // recomputing the edited row's own hash moves the break to the next row
    String original = ADMIN.sql("SELECT details::text FROM audit.audit_log WHERE seq = ?").param(1, seq)
        .query(String.class).single();
    String hash = ADMIN.sql("SELECT encode(hash, 'hex') FROM audit.audit_log WHERE seq = ?").param(1, seq)
        .query(String.class).single();
    try {
      tamper("UPDATE audit.audit_log SET details = '{\"edited\":true}', hash = audit.chain_hash(prev_hash, seq,"
          + " occurred_at, id, actor_type, actor_id, action, target_type, target_id, ip_hash, request_id,"
          + " '{\"edited\":true}') WHERE seq = " + seq);
      JsonNode broken = verify();
      assertThat(broken.get("ok").asBoolean()).isFalse();
      assertThat(broken.get("brokenAt").asLong()).isEqualTo(seq + 1);
      assertThat(broken.get("reason").asString()).contains("prev_hash");
    } finally {
      tamper("UPDATE audit.audit_log SET details = '" + original.replace("'", "''") + "', hash = decode('" + hash
          + "', 'hex') WHERE seq = " + seq);
    }
    assertThat(verify().get("ok").asBoolean()).isTrue();
    // the verification runs are themselves audited
    assertThat(count("SELECT count(1) FROM audit.audit_log WHERE action = 'audit.chain.verified' AND actor_id = ?",
        OPERATOR.toString())).isGreaterThanOrEqualTo(4);
  }

  /** A rewrite of the row and of every row after it passes the row checks — the anchor exported before it does not. */
  @Test
  void anAnchorCatchesARewrittenTail() {
    UUID a = registered(Ids.newId());
    long anchored = log.anchorNow();
    assertThat(anchored).isPositive();
    assertThat(STORE.objects).containsKey(AuditLogService.anchorKey(anchored));
    assertThat(log.anchorNow()).as("nothing moved").isEqualTo(-1);
    registered(Ids.newId());
    long seq = seqOf(AccountRegisteredV1.TYPE, a.toString());
    long head = count("SELECT seq FROM audit.chain_head");
    List<String> saved = ADMIN.sql("SELECT seq || '|' || encode(prev_hash, 'hex') || '|' || encode(hash, 'hex') || '|'"
        + " || details::text FROM audit.audit_log WHERE seq >= ? ORDER BY seq").param(1, seq).query(String.class)
        .list();
    try {
      // rewrite the row and re-chain everything after it, head included: every row check passes again
      tamper("UPDATE audit.audit_log SET details = '{\"rewritten\":true}' WHERE seq = " + seq);
      for (long s = seq; s <= head; s++) {
        tamper("UPDATE audit.audit_log l SET prev_hash = coalesce((SELECT p.hash FROM audit.audit_log p WHERE p.seq"
            + " = l.seq - 1), sha256(convert_to('dexter-audit-genesis', 'UTF8'))) WHERE seq = " + s);
        tamper("UPDATE audit.audit_log SET hash = audit.chain_hash(prev_hash, seq, occurred_at, id, actor_type,"
            + " actor_id, action, target_type, target_id, ip_hash, request_id, details) WHERE seq = " + s);
      }
      tamper("UPDATE audit.chain_head SET hash = (SELECT hash FROM audit.audit_log WHERE seq = " + head + ")");
      JsonNode broken = verify();
      assertThat(broken.get("ok").asBoolean()).as(broken.toString()).isFalse();
      assertThat(broken.get("brokenAt").asLong()).isEqualTo(anchored);
      assertThat(broken.get("reason").asString()).contains("anchor");
    } finally {
      for (String row : saved) {
        String[] p = row.split("\\|", 4);
        tamper("UPDATE audit.audit_log SET prev_hash = decode('" + p[1] + "', 'hex'), hash = decode('" + p[2]
            + "', 'hex'), details = '" + p[3].replace("'", "''") + "' WHERE seq = " + p[0]);
      }
      // the verification run during the rewrite appended a row chained to the rewritten head: chain it back
      rechainAfter(head);
    }
    JsonNode ok = verify();
    assertThat(ok.get("ok").asBoolean()).as(ok.toString()).isTrue();
    assertThat(ok.get("anchorsChecked").asInt()).isPositive();
    assertThat(ok.get("anchorsExportedChecked").asInt()).isPositive();

    // the exported copy is checked too
    byte[] copy = STORE.objects.remove(AuditLogService.anchorKey(anchored));
    try {
      JsonNode missing = verify();
      assertThat(missing.get("ok").asBoolean()).isFalse();
      assertThat(missing.get("reason").asString()).contains("missing");
    } finally {
      STORE.objects.put(AuditLogService.anchorKey(anchored), copy);
    }
  }

  /** Re-links and re-hashes every row after {@code seq} and moves the head to the last one. */
  static void rechainAfter(long seq) {
    long max = count("SELECT max(seq) FROM audit.audit_log");
    for (long s = seq + 1; s <= max; s++) {
      tamper("UPDATE audit.audit_log l SET prev_hash = (SELECT p.hash FROM audit.audit_log p WHERE p.seq = l.seq - 1)"
          + " WHERE seq = " + s);
      tamper("UPDATE audit.audit_log SET hash = audit.chain_hash(prev_hash, seq, occurred_at, id, actor_type,"
          + " actor_id, action, target_type, target_id, ip_hash, request_id, details) WHERE seq = " + s);
    }
    tamper("UPDATE audit.chain_head SET hash = (SELECT hash FROM audit.audit_log WHERE seq = " + max + "), seq = "
        + max);
  }

  /** Runs one statement as the superuser with triggers off (the session setting lives on one connection). */
  static void tamper(String sql) {
    try (Connection c = DB.conn("postgres"); Statement s = c.createStatement()) {
      s.execute("SET session_replication_role = replica");
      s.execute(sql);
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(sql + " -> " + e.getMessage(), e);
    }
  }
}
