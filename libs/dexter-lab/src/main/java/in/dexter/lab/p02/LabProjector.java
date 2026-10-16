package in.dexter.lab.p02;

import in.dexter.core.tenant.Tenant;
import in.dexter.db.tenant.TenantTx;
import in.dexter.events.EnvelopeCodec;
import in.dexter.events.EventEnvelope;
import in.dexter.events.InboxGuard;
import in.dexter.events.ProjectionVersionGuard;
import in.dexter.events.consumer.EventConsumer;
import java.util.Map;
import java.util.UUID;

/**
 * The lab's projection consumer: inbox dedupe + version guard in one transaction per event (MASTER_PLAN 6.4).
 * A note titled {@value P02Lab#ALWAYS_FAILS} can never be projected, so it walks retry-2s → retry-5s → DLQ.
 */
final class LabProjector implements EventConsumer.Handler {
  static final String TITLE_UPSERT = "INSERT INTO lab.note_titles (account_id, note_id, title, projected_version)"
      + " VALUES (:a, :n, :t, :version) ON CONFLICT (account_id, note_id) DO UPDATE"
      + " SET title = EXCLUDED.title, projected_version = EXCLUDED.projected_version"
      + " WHERE note_titles.projected_version < EXCLUDED.projected_version";

  private final String consumer;
  private final TenantTx tx;
  private final InboxGuard inbox;
  private final org.springframework.jdbc.core.simple.JdbcClient jdbc;
  private final EnvelopeCodec codec;

  LabProjector(String consumer, TenantTx tx, InboxGuard inbox, org.springframework.jdbc.core.simple.JdbcClient jdbc,
      EnvelopeCodec codec) {
    this.consumer = consumer;
    this.tx = tx;
    this.inbox = inbox;
    this.jdbc = jdbc;
    this.codec = codec;
  }

  @Override
  public void handle(EventEnvelope e) {
    if (e.accountId() == null) {
      return;
    }
    Tenant t = Tenant.user(e.accountId());
    if (e.type().equals(P02Lab.NOTE_CREATED.type())) {
      LabModel.NoteCreatedV1 d = codec.data(e, LabModel.NoteCreatedV1.class);
      if (P02Lab.ALWAYS_FAILS.equals(d.title())) {
        throw new IllegalStateException("lab: notes titled '" + P02Lab.ALWAYS_FAILS + "' can never be projected");
      }
      tx.writeVoid(t, () -> inbox.once(consumer, e.id(), () -> {
        jdbc.sql("INSERT INTO lab.note_stats (account_id, notes, amount_paise) VALUES (:a, 1, :amt)"
                + " ON CONFLICT (account_id) DO UPDATE SET notes = note_stats.notes + 1,"
                + " amount_paise = note_stats.amount_paise + EXCLUDED.amount_paise")
            .param("a", e.accountId()).param("amt", d.amountPaise()).update();
        title(e, d.noteId(), d.title());
      }));
    } else if (e.type().equals(P02Lab.NOTE_RENAMED.type())) {
      LabModel.NoteRenamedV1 d = codec.data(e, LabModel.NoteRenamedV1.class);
      tx.writeVoid(t, () -> inbox.once(consumer, e.id(), () -> title(e, d.noteId(), d.title())));
    }
  }

  private void title(EventEnvelope e, UUID noteId, String title) {
    ProjectionVersionGuard.apply(jdbc, TITLE_UPSERT, Map.of("a", e.accountId(), "n", noteId, "t", title),
        e.aggregateVersion());
  }
}
