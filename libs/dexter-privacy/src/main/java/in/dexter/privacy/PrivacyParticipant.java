package in.dexter.privacy;

import in.dexter.contracts.events.audit.PrivacyRequestedV1;
import in.dexter.contracts.events.privacy.PrivacyCompletedV1;
import in.dexter.events.EnvelopeCodec;
import in.dexter.events.EventEnvelope;
import in.dexter.events.EventType;
import in.dexter.events.InboxGuard;
import in.dexter.events.OutboxWriter;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Answers the privacy saga for one service (MASTER_PLAN P18, ADR-0024). For every {@code audit.privacy.requested}
 * that names this service and a phase it runs, in <em>one</em> transaction on the service's app pool: the inbox row
 * (a redelivered event does nothing), {@code <schema>.privacy_<phase>(account)} (SECURITY DEFINER, so the app role
 * reaches the account's rows without being that account), {@code privacy_remaining(account)}, the export part
 * written to the object store, and the {@code <svc>.privacy.completed} acknowledgement appended to the outbox. A
 * failure rolls all of it back and the event goes to the retry stages; the saga also re-asks on its own timeout, so
 * every phase is idempotent (a second erase deletes nothing, a second export overwrites the same part).
 */
public final class PrivacyParticipant {
  private static final Logger log = LoggerFactory.getLogger(PrivacyParticipant.class);
  private final String service;
  private final Set<String> phases;
  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final OutboxWriter outbox;
  private final InboxGuard inbox;
  private final PrivacyParts parts;
  private final EnvelopeCodec codec;
  private final Clock clock;
  private final String statementTimeout;
  private final EventType<PrivacyCompletedV1> completed;

  /**
   * @param service          the service name, which is also its schema ({@code identity}, {@code ledger}, ...)
   * @param phases           the phases it runs ({@code identity}: freeze, erase, shred, export; others: erase, export)
   * @param statementTimeout for the phase's statements (an account's whole history; the app role's 5 s is too short)
   */
  public PrivacyParticipant(String service, Set<String> phases, JdbcClient jdbc, TransactionTemplate tx,
      OutboxWriter outbox, InboxGuard inbox, PrivacyParts parts, EnvelopeCodec codec, Clock clock,
      Duration statementTimeout) {
    this.service = Objects.requireNonNull(service, "service");
    this.phases = Set.copyOf(phases);
    this.jdbc = jdbc;
    this.tx = tx;
    this.outbox = outbox;
    this.inbox = inbox;
    this.parts = parts;
    this.codec = codec;
    this.clock = clock;
    this.statementTimeout = statementTimeout.toMillis() + "ms";
    this.completed = completedType(service);
    phases.forEach(p -> PrivacyRequests.function(service, p));
  }

  /** {@code <svc>.privacy.completed} v1 on {@code dexter.<svc>.privacy.v1}. */
  public static EventType<PrivacyCompletedV1> completedType(String service) {
    return EventType.of(service, PrivacyCompletedV1.AGGREGATE, "completed", PrivacyCompletedV1.VERSION,
        PrivacyCompletedV1.class);
  }

  public String service() {
    return service;
  }

  /** The {@link in.dexter.events.consumer.EventConsumer} handler. */
  public void handle(EventEnvelope event) {
    if (!PrivacyRequestedV1.TYPE.equals(event.type())) {
      return;
    }
    PrivacyRequestedV1 request = codec.mapper().treeToValue(event.data(), PrivacyRequestedV1.class);
    if (!PrivacyRequests.appliesTo(request, service, phases)) {
      return;
    }
    UUID account = event.accountId();
    if (account == null) {
      log.warn("PRIVACY_REQUEST_WITHOUT_ACCOUNT request={} event={}", request.requestId(), event.id());
      return;
    }
    Boolean ran = tx.execute(status -> run(event.id(), account, request));
    if (Boolean.TRUE.equals(ran)) {
      log.info("PRIVACY_PHASE_DONE service={} request={} phase={} attempt={}", service, request.requestId(),
          request.phase(), request.attempt());
    }
  }

  private boolean run(UUID eventId, UUID account, PrivacyRequestedV1 request) {
    if (!inbox.firstDelivery(PrivacyRequests.CONSUMER, eventId)) {
      return false;
    }
    jdbc.sql("SELECT set_config('statement_timeout', :t, true)").param("t", statementTimeout).query().singleRow();
    String fn = PrivacyRequests.function(service, request.phase());
    long rows;
    String partKey = null;
    if (PrivacyRequestedV1.EXPORT.equals(request.phase())) {
      String json = jdbc.sql("SELECT CAST(" + fn + "(:a) AS text)").param("a", account).query(String.class).single();
      JsonNode export = codec.mapper().readTree(json);
      rows = PrivacyRequests.rows(export);
      partKey = PrivacyRequests.partKey(request.requestId(), service);
      parts.put(partKey, json.getBytes(StandardCharsets.UTF_8));
    } else {
      rows = jdbc.sql("SELECT " + fn + "(:a)").param("a", account).query(Long.class).single();
    }
    long remaining = jdbc.sql("SELECT " + service + ".privacy_remaining(:a)").param("a", account)
        .query(Long.class).single();
    outbox.append(completed, account, request.requestId(), request.attempt(), new PrivacyCompletedV1(
        request.requestId(), service, request.phase(), request.attempt(), rows, remaining, partKey,
        clock.instant().toString()));
    return true;
  }
}
