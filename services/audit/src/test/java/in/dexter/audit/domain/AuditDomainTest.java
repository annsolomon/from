package in.dexter.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.dexter.audit.domain.NotificationRules.Prefs;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The pure parts of P18: the chain walk, backoff, notification rules and texts, the saga's phases, the pseudonym. */
class AuditDomainTest {
  static final byte[] GENESIS = sha("genesis");

  static byte[] sha(String s) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** A well-formed chain of n rows: hash_i = sha(prev_i + i). */
  static List<ChainVerifier.Row> chain(int n) {
    List<ChainVerifier.Row> rows = new java.util.ArrayList<>();
    byte[] prev = GENESIS;
    for (int i = 1; i <= n; i++) {
      byte[] h = sha(java.util.HexFormat.of().formatHex(prev) + i);
      rows.add(new ChainVerifier.Row(i, prev, h, h));
      prev = h;
    }
    return rows;
  }

  static ChainVerifier.Result walk(List<ChainVerifier.Row> rows, Map<Long, byte[]> anchors, long headSeq,
      byte[] headHash) {
    ChainVerifier v = new ChainVerifier(GENESIS, anchors);
    for (ChainVerifier.Row r : rows) {
      if (!v.accept(r)) {
        break;
      }
    }
    return v.finish(headSeq, headHash);
  }

  @Test
  void anIntactChainVerifies() {
    List<ChainVerifier.Row> rows = chain(5);
    ChainVerifier.Result r = walk(rows, Map.of(3L, rows.get(2).hash()), 5, rows.get(4).hash());
    assertThat(r.ok()).isTrue();
    assertThat(r.checked()).isEqualTo(5);
    assertThat(r.anchorsChecked()).isEqualTo(1);
    assertThat(walk(List.of(), Map.of(), 0, GENESIS).ok()).as("an empty log at genesis").isTrue();
  }

  @Test
  void everyKindOfTamperingIsCaughtWhereItHappens() {
    List<ChainVerifier.Row> rows = new java.util.ArrayList<>(chain(5));
    byte[] head = rows.get(4).hash();
    // content changed, hash not recomputed
    List<ChainVerifier.Row> edited = new java.util.ArrayList<>(rows);
    ChainVerifier.Row r3 = rows.get(2);
    edited.set(2, new ChainVerifier.Row(3, r3.prevHash(), r3.hash(), sha("edited")));
    assertThat(walk(edited, Map.of(), 5, head).brokenAt()).isEqualTo(3L);
    // content changed and its hash recomputed: the next row's link breaks
    List<ChainVerifier.Row> rehashed = new java.util.ArrayList<>(rows);
    rehashed.set(2, new ChainVerifier.Row(3, r3.prevHash(), sha("x"), sha("x")));
    ChainVerifier.Result r = walk(rehashed, Map.of(), 5, head);
    assertThat(r.brokenAt()).isEqualTo(4L);
    assertThat(r.reason()).contains("prev_hash");
    // a row removed from the middle, and from the end
    List<ChainVerifier.Row> gap = new java.util.ArrayList<>(rows);
    gap.remove(1);
    assertThat(walk(gap, Map.of(), 5, head).brokenAt()).isEqualTo(2L);
    assertThat(walk(rows.subList(0, 4), Map.of(), 5, head).reason()).contains("chain head");
    // a whole rewritten tail: every link holds, the anchor does not
    List<ChainVerifier.Row> forged = chain(2);
    byte[] prev = forged.get(1).hash();
    for (int i = 3; i <= 5; i++) {
      byte[] h = sha("forged" + i);
      forged.add(new ChainVerifier.Row(i, prev, h, h));
      prev = h;
    }
    ChainVerifier.Result anchored = walk(forged, Map.of(4L, rows.get(3).hash()), 5, prev);
    assertThat(anchored.ok()).isFalse();
    assertThat(anchored.brokenAt()).isEqualTo(4L);
    assertThat(anchored.reason()).contains("anchor");
  }

  @Test
  void backoffDoublesUpToItsCapAndStopsAtTheLastAttempt() {
    Backoff b = new Backoff(Duration.ofSeconds(30), Duration.ofMinutes(5), 6);
    assertThat(List.of(1, 2, 3, 4, 5).stream().map(b::after).toList()).containsExactly(Duration.ofSeconds(30),
        Duration.ofSeconds(60), Duration.ofSeconds(120), Duration.ofSeconds(240), Duration.ofMinutes(5));
    assertThat(b.after(40)).isEqualTo(Duration.ofMinutes(5));
    assertThat(b.exhausted(5)).isFalse();
    assertThat(b.exhausted(6)).isTrue();
    assertThatThrownBy(() -> new Backoff(Duration.ZERO, Duration.ofSeconds(1), 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void channelsFollowThePreferencesAndSecurityCannotBeMuted() {
    Prefs off = new Prefs(false, false, Set.of("budget", "commerce"), 3);
    assertThat(NotificationRules.channels("security", true, off)).containsExactly("inapp", "email");
    assertThat(NotificationRules.channels("budget", false, off)).isEmpty();
    assertThat(NotificationRules.channels("billing", true, off)).containsExactly("inapp");
    assertThat(NotificationRules.channels("commerce", true, Prefs.DEFAULTS)).containsExactly("inapp", "email",
        "push");
    assertThat(NotificationRules.channels("budget", false, Prefs.DEFAULTS)).containsExactly("inapp", "email");
    assertThat(NotificationRules.MUTABLE).doesNotContain("security");
    assertThatThrownBy(() -> NotificationRules.channels("gossip", false, Prefs.DEFAULTS))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void templatesRenderMoneyInRupees() {
    Templates.Text t = Templates.render("budget.threshold", Map.of("name", "Eating out", "threshold", 80,
        "spentPaise", 812_550L, "limitPaise", 1_000_000L, "period", "monthly"));
    assertThat(t.title()).isEqualTo("Budget Eating out: 80% used");
    assertThat(t.body()).isEqualTo("You have spent ₹8,125.50 of ₹10,000.00 this monthly period.");
    assertThat(Templates.render("commerce.application", Map.of("status", "in_review")).title())
        .isEqualTo("Card application in review");
    assertThat(Templates.render("no.such", Map.of()).title()).isEqualTo("no.such");
  }

  @Test
  void theSagasPhases() {
    List<String> all = List.of("identity", "ledger", "billing");
    assertThat(PrivacySaga.firstPhase("delete")).isEqualTo("freeze");
    assertThat(List.of("freeze", "erase", "shred", "local").stream().map(p -> PrivacySaga.next("delete", p)).toList())
        .containsExactly("erase", "shred", "local", "done");
    assertThat(PrivacySaga.next("export", "export")).isEqualTo("archive");
    assertThat(PrivacySaga.servicesFor("freeze", all)).containsExactly("identity");
    assertThat(PrivacySaga.servicesFor("erase", all)).containsExactlyElementsOf(all);
    assertThat(PrivacySaga.firstActionable("delete", "freeze", List.of("ledger"))).as("no identity: no freeze")
        .isEqualTo("erase");
    assertThat(PrivacySaga.firstActionable("export", "export", List.of())).isEqualTo("archive");
    assertThat(PrivacySaga.done("erase", "ledger", 3)).isFalse();
    assertThat(PrivacySaga.done("erase", "ledger", 0)).isTrue();
    assertThat(PrivacySaga.done("erase", "identity", 2)).as("identity's rows go at shred").isTrue();
    assertThat(PrivacySaga.done("shred", "identity", 1)).isFalse();
    assertThat(PrivacySaga.remote("local")).isFalse();
    assertThatThrownBy(() -> PrivacySaga.next("delete", "export")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void thePseudonymIsStableKeyedAndNotTheId() {
    UUID a = UUID.fromString("0192f1e2-0000-7000-8000-0000000a18a1");
    SubjectRef k1 = new SubjectRef(sha("key-1"));
    assertThat(k1.of(a)).hasSize(64).isEqualTo(k1.of(a)).doesNotContain(a.toString());
    assertThat(new SubjectRef(sha("key-2")).of(a)).isNotEqualTo(k1.of(a));
    assertThatThrownBy(() -> new SubjectRef(new byte[16])).isInstanceOf(IllegalArgumentException.class);
  }
}
