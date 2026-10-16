package in.dexter.events;

import in.dexter.db.sql.Sql;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Consumer-side dedupe (MASTER_PLAN 6.4): {@code INSERT INTO inbox ... ON CONFLICT DO NOTHING} in the
 * same transaction as the effect. A second delivery of the same event finds the row and is skipped;
 * a concurrent second delivery waits on the primary key until the first commits, then skips. Exactly
 * once <em>effects</em> on top of at-least-once delivery.
 */
public final class InboxGuard {
  private final JdbcClient jdbc;
  private final String insertSql;

  public InboxGuard(JdbcClient jdbc, String schema) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.insertSql = "INSERT INTO " + Sql.ident(schema) + ".inbox (consumer, event_id) VALUES (:c, :e) ON CONFLICT DO NOTHING";
  }

  /** True on the first delivery (apply the effect now); false for a duplicate (skip). */
  public boolean firstDelivery(String consumer, UUID eventId) {
    Topics.name(consumer);
    Objects.requireNonNull(eventId, "eventId");
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("InboxGuard must run in the read-write transaction that applies the effect");
    }
    return jdbc.sql(insertSql).param("c", consumer).param("e", eventId).update() == 1;
  }

  /** Runs {@code effect} only on the first delivery. Returns whether it ran. */
  public boolean once(String consumer, UUID eventId, Runnable effect) {
    if (!firstDelivery(consumer, eventId)) {
      return false;
    }
    effect.run();
    return true;
  }
}
