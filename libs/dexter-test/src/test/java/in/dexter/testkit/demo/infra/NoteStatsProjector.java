package in.dexter.testkit.demo.infra;

import in.dexter.contracts.events.demo.NoteCreatedV1;
import in.dexter.contracts.events.demo.NoteRenamedV1;
import in.dexter.core.tenant.Tenant;
import in.dexter.db.tenant.TenantTx;
import in.dexter.events.EnvelopeCodec;
import in.dexter.events.EventEnvelope;
import in.dexter.events.InboxGuard;
import in.dexter.events.ProjectionVersionGuard;
import in.dexter.events.consumer.EventConsumer;
import in.dexter.testkit.demo.DemoEvents;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Consumer (infra): the P02 consumer pattern. One transaction per event, as the event's tenant:
 * inbox dedupe first, then the effect. Counters let tests see how often effects really ran.
 */
public final class NoteStatsProjector implements EventConsumer.Handler {
  /** Titles that make the handler fail every time (drives the DLQ test). */
  public static final String ALWAYS_FAILS = "handler-always-fails";

  static final String TITLE_UPSERT = "INSERT INTO demo.note_titles (account_id, note_id, title, projected_version)"
      + " VALUES (:a, :n, :t, :version) ON CONFLICT (account_id, note_id) DO UPDATE"
      + " SET title = EXCLUDED.title, projected_version = EXCLUDED.projected_version"
      + " WHERE note_titles.projected_version < EXCLUDED.projected_version";

  private final String consumer;
  private final TenantTx tx;
  private final InboxGuard inbox;
  private final JdbcClient jdbc;
  private final EnvelopeCodec codec;
  private final AtomicInteger failuresToInject = new AtomicInteger();
  private final java.util.concurrent.ConcurrentHashMap<String, AtomicInteger> failByTitle =
      new java.util.concurrent.ConcurrentHashMap<>();
  public final AtomicInteger effectsApplied = new AtomicInteger();
  public final AtomicInteger duplicatesSkipped = new AtomicInteger();
  public final AtomicInteger staleIgnored = new AtomicInteger();

  public NoteStatsProjector(String consumer, TenantTx tx, InboxGuard inbox, JdbcClient jdbc, EnvelopeCodec codec) {
    this.consumer = consumer;
    this.tx = tx;
    this.inbox = inbox;
    this.jdbc = jdbc;
    this.codec = codec;
  }

  public String consumer() {
    return consumer;
  }

  /** The next {@code n} deliveries fail (transient error), then handling succeeds. */
  public void failNext(int n) {
    failuresToInject.set(n);
  }

  /** Deliveries of a created note with this title fail {@code n} times, then succeed. */
  public void failTitle(String title, int n) {
    failByTitle.put(title, new AtomicInteger(n));
  }

  @Override
  public void handle(EventEnvelope e) {
    if (e.accountId() == null) {
      return; // demo events are all per-account
    }
    if (failuresToInject.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
      throw new IllegalStateException("injected transient failure");
    }
    Tenant t = Tenant.user(e.accountId());
    if (e.type().equals(DemoEvents.NOTE_CREATED.type())) {
      NoteCreatedV1 d = codec.data(e, NoteCreatedV1.class);
      if (ALWAYS_FAILS.equals(d.title())) {
        throw new IllegalStateException("this note can never be projected");
      }
      AtomicInteger left = failByTitle.get(d.title());
      if (left != null && left.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
        throw new IllegalStateException("injected transient failure for " + d.title());
      }
      tx.writeVoid(t, () -> apply(e, () -> {
        jdbc.sql("INSERT INTO demo.note_stats (account_id, notes, amount_paise) VALUES (:a, 1, :amt)"
                + " ON CONFLICT (account_id) DO UPDATE SET notes = note_stats.notes + 1,"
                + " amount_paise = note_stats.amount_paise + EXCLUDED.amount_paise")
            .param("a", e.accountId()).param("amt", d.amountPaise()).update();
        title(e, d.noteId(), d.title());
      }));
    } else if (e.type().equals(DemoEvents.NOTE_RENAMED.type())) {
      NoteRenamedV1 d = codec.data(e, NoteRenamedV1.class);
      tx.writeVoid(t, () -> apply(e, () -> title(e, d.noteId(), d.title())));
    }
    // unknown types are ignored: additive evolution (MASTER_PLAN 6.1)
  }

  private void apply(EventEnvelope e, Runnable effect) {
    if (inbox.once(consumer, e.id(), effect)) {
      effectsApplied.incrementAndGet();
    } else {
      duplicatesSkipped.incrementAndGet();
    }
  }

  private void title(EventEnvelope e, java.util.UUID noteId, String title) {
    boolean newer = ProjectionVersionGuard.apply(jdbc, TITLE_UPSERT,
        Map.of("a", e.accountId(), "n", noteId, "t", title), e.aggregateVersion());
    if (!newer) {
      staleIgnored.incrementAndGet();
    }
  }
}
