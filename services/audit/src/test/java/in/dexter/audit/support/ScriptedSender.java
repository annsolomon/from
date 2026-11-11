package in.dexter.audit.support;

import in.dexter.audit.app.NotificationSender;
import in.dexter.audit.app.NotificationStore.Notification;
import in.dexter.audit.domain.Templates;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** A transport the test scripts: refuse each notification's first {@code failFirst} attempts; every call recorded. */
public final class ScriptedSender implements NotificationSender {
  public record Call(UUID id, String channel, int attempt, boolean ok, Instant at) {}

  public final AtomicInteger failFirst = new AtomicInteger();
  public final List<Call> calls = new CopyOnWriteArrayList<>();

  @Override
  public void send(Notification n, Templates.Text text, int attempt) {
    boolean ok = attempt > failFirst.get();
    calls.add(new Call(n.id(), n.channel(), attempt, ok, Instant.now()));
    if (!ok) {
      throw new IllegalStateException("scripted failure of attempt " + attempt);
    }
  }

  public List<Call> of(UUID id) {
    return calls.stream().filter(c -> c.id().equals(id)).toList();
  }
}
