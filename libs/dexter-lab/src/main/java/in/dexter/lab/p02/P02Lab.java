package in.dexter.lab.p02;

import in.dexter.cache.CacheMetrics;
import in.dexter.cache.CacheScope;
import in.dexter.cache.CacheSettings;
import in.dexter.cache.CacheUnavailableException;
import in.dexter.cache.DexterCache;
import in.dexter.cache.InvalidationConsumer;
import in.dexter.core.error.CoreErrors;
import in.dexter.core.error.DexterException;
import in.dexter.core.error.ForbiddenException;
import in.dexter.core.error.NotFoundException;
import in.dexter.core.error.UnauthenticatedException;
import in.dexter.core.error.ValidationException;
import in.dexter.core.id.Ids;
import in.dexter.core.tenant.Tenant;
import in.dexter.db.page.CursorKeyRing;
import in.dexter.db.page.KeyType;
import in.dexter.db.page.KeysetCursorCodec;
import in.dexter.db.page.KeysetPager;
import in.dexter.db.page.KeysetQuery;
import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import in.dexter.db.sql.OptimisticUpdate;
import in.dexter.db.sql.Rows;
import in.dexter.db.sql.Sql;
import in.dexter.db.template.SqlTemplates;
import in.dexter.db.tenant.TenantScope;
import in.dexter.db.tenant.TenantTx;
import in.dexter.events.EnvelopeCodec;
import in.dexter.events.EventEnvelope;
import in.dexter.events.EventType;
import in.dexter.events.InboxGuard;
import in.dexter.events.KafkaClients;
import in.dexter.events.OutboxWriter;
import in.dexter.events.Topics;
import in.dexter.events.consumer.EventConsumer;
import in.dexter.events.relay.OutboxRelay;
import in.dexter.events.topics.TopicRegistry;
import in.dexter.idempotency.ApiResponse;
import in.dexter.idempotency.IdempotencyEngine;
import in.dexter.idempotency.IdempotentRequest;
import in.dexter.idempotency.IdempotentResponse;
import in.dexter.security.AuthContext;
import in.dexter.security.jwt.JwksCache;
import in.dexter.security.jwt.JwtSettings;
import in.dexter.security.jwt.JwtVerifier;
import in.dexter.security.jwt.TokenRejectedException;
import in.dexter.security.web.BearerTokens;
import in.dexter.web.json.StrictJsonCustomizer;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.Delay;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * The P02 lab (dev only, A-23): every P02 library wired by hand against the real dev infrastructure, so the trail
 * page exercises production code paths. Started lazily on first use; a failure answers 503
 * {@code SERVICE_UNAVAILABLE} and the next request retries.
 * <ul>
 *   <li>Postgres database {@code lab}: the standard templates (idempotency, outbox, inbox, partitions) + lab tables;
 *       roles {@code lab_owner} (bootstrap), {@code lab_app} (requests, RLS), {@code lab_relay} (relay, viewer).</li>
 *   <li>Kafka: {@code dexter.lab.note.v1} with consumers {@code projector} (inbox + version guard, DLQ demo) and
 *       {@code cache-inv} (Path B); retry stages 2 s and 5 s so the DLQ is reachable in seconds.</li>
 *   <li>Redis as ACL user {@code lab} through {@link LabProxy} (outage simulation).</li>
 *   <li>JWT: {@link LabIssuer} + the real {@link JwtVerifier} (audience {@code lab}).</li>
 * </ul>
 */
public final class P02Lab implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(P02Lab.class);
  public static final String SCHEMA = "lab";
  public static final String PATH = "/_dev/p02/notes";
  public static final EventType<LabModel.NoteCreatedV1> NOTE_CREATED =
      EventType.of("lab", "note", "created", 1, LabModel.NoteCreatedV1.class);
  public static final EventType<LabModel.NoteRenamedV1> NOTE_RENAMED =
      EventType.of("lab", "note", "renamed", 1, LabModel.NoteRenamedV1.class);
  public static final String TOPIC = NOTE_CREATED.topic();
  public static final String PROJECTOR = "projector";
  public static final String CACHE_CONSUMER = "cache-inv";
  public static final List<EventConsumer.Stage> STAGES = List.of(
      new EventConsumer.Stage("retry-2s", Duration.ofSeconds(2)), new EventConsumer.Stage("retry-5s", Duration.ofSeconds(5)));
  public static final CacheScope NOTES = CacheScope.tenant("notes", Duration.ofMinutes(10));
  public static final String ALWAYS_FAILS = "always-fails";
  public static final long MAX_HOLD_MS = 5_000;
  static final byte[] CURSOR_KEY = "p02-lab-cursor-key-dev-only-0123456789".getBytes(StandardCharsets.UTF_8);

  static final String COLS = "account_id, id, title, amount_paise, created_at, version";
  static final RowMapper<LabModel.Note> MAPPER = (rs, i) -> new LabModel.Note(Rows.uuid(rs, "account_id"),
      Rows.uuid(rs, "id"), rs.getString("title"), rs.getLong("amount_paise"), Rows.instant(rs, "created_at"),
      rs.getLong("version"));
  static final KeysetQuery<LabModel.Note> LIST = KeysetQuery.<LabModel.Note>builder("created_desc")
      .key("created_at", KeyType.TIMESTAMPTZ)
      .key("id", KeyType.UUID)
      .sql("SELECT " + COLS + " FROM lab.notes WHERE account_id = (SELECT lab.app_account())")
      .mapper(MAPPER)
      .keysOf(n -> List.of(n.createdAt(), n.id()))
      .build();

  private final LabProperties props;
  private final LabIssuer issuer = new LabIssuer();
  private final JwtVerifier verifier;
  private final JsonMapper mapper = StrictJsonCustomizer.apply(JsonMapper.builder()).build();
  private final Object initLock = new Object();
  private volatile @Nullable LabRuntime rt;
  private volatile @Nullable String lastError;

  public P02Lab(LabProperties props) {
    this.props = props;
    Clock clock = Clock.systemUTC();
    this.verifier = new JwtVerifier(JwtSettings.of(LabIssuer.ISSUER, LabIssuer.AUDIENCE),
        JwksCache.withDefaults(issuer.source(), clock), clock);
  }

  /** Everything the lab started; closed in reverse order. */
  record LabRuntime(DataSource app, JdbcClient jdbc, JdbcClient relayJdbc, TenantTx tx, EnvelopeCodec codec,
      IdempotencyEngine idempotency, OutboxWriter outbox, KeysetPager pager, OutboxRelay relay,
      Producer<String, byte[]> producer, LabProxy redisProxy, StringRedisTemplate redis, DexterCache cache,
      List<AutoCloseable> closeables) {}

  public LabIssuer issuer() {
    return issuer;
  }

  public JwtVerifier verifier() {
    return verifier;
  }

  // ------------------------------------------------------------------ lifecycle

  LabRuntime runtime() {
    LabRuntime r = rt;
    if (r != null) {
      return r;
    }
    synchronized (initLock) {
      if (rt != null) {
        return rt;
      }
      try {
        LabRuntime started = start();
        rt = started;
        lastError = null;
        log.info("P02 lab ready: database lab, topic {}, Redis user lab", TOPIC);
        return started;
      } catch (RuntimeException e) {
        lastError = e.toString();
        log.warn("P02 lab could not start: {}", e.toString());
        throw new DexterException(CoreErrors.SERVICE_UNAVAILABLE,
            "P02 lab unavailable (is `./scripts/run.sh core` up with a fresh volume?): " + e.getMessage(), e);
      }
    }
  }

  private LabRuntime start() {
    List<AutoCloseable> started = new ArrayList<>();
    try {
      bootstrapSchema();
      DataSource app = dataSource("app");
      JdbcClient jdbc = JdbcClient.create(app);
      JdbcClient relayJdbc = JdbcClient.create(dataSource("relay"));
      TenantTx tx = new TenantTx(new JdbcTransactionManager(app), jdbc);
      EnvelopeCodec codec = new EnvelopeCodec(mapper);
      IdempotencyEngine idempotency = new IdempotencyEngine(jdbc, mapper, SCHEMA);
      OutboxWriter outbox = new OutboxWriter(jdbc, codec, SCHEMA, "lab", Clock.systemUTC(), () -> null);
      InboxGuard inbox = new InboxGuard(jdbc, SCHEMA);
      KeysetPager pager = new KeysetPager(new KeysetCursorCodec(new CursorKeyRing("lab1", Map.of("lab1", CURSOR_KEY))));
      ensureTopics();
      Producer<String, byte[]> producer = KafkaClients.idempotentProducer(props.kafkaBootstrap(), "ledger-p02-lab");
      started.add(producer);
      OutboxRelay relay = new OutboxRelay(dataSource("relay"), producer, OutboxRelay.Settings.defaults(SCHEMA),
          OutboxRelay.Hooks.NONE, List.of());
      relay.start();
      started.add(relay);
      LabProxy proxy = LabProxy.to(props.redisHost(), props.redisPort());
      started.add(proxy);
      LettuceConnectionFactory redisFactory = redisFactory(proxy.host(), proxy.port());
      started.add(redisFactory::destroy);
      StringRedisTemplate redis = new StringRedisTemplate(redisFactory);
      DexterCache cache = DexterCache.create(redis, SCHEMA, CacheSettings.defaults(), JsonMapper.builder().build());
      started.add(consumer(PROJECTOR, new LabProjector(PROJECTOR, tx, inbox, jdbc, codec), codec, producer));
      started.add(consumer(CACHE_CONSUMER, InvalidationConsumer.of(cache.invalidator(),
          Map.of(NOTE_CREATED.type(), List.of(NOTES), NOTE_RENAMED.type(), List.of(NOTES))), codec, producer));
      List<AutoCloseable> closeables = new ArrayList<>(started);
      Collections.reverse(closeables);
      return new LabRuntime(app, jdbc, relayJdbc, tx, codec, idempotency, outbox, pager, relay, producer, proxy, redis,
          cache, closeables);
    } catch (RuntimeException e) {
      Collections.reverse(started);
      started.forEach(P02Lab::closeQuietly);
      throw e;
    }
  }

  @Override
  public void close() {
    LabRuntime r = rt;
    rt = null;
    if (r != null) {
      r.closeables().forEach(P02Lab::closeQuietly);
    }
  }

  private DataSource dataSource(String role) {
    String pw = switch (role) {
      case "owner" -> props.ownerPassword();
      case "app" -> props.appPassword();
      case "relay" -> props.relayPassword();
      default -> throw new IllegalArgumentException(role);
    };
    return new SimpleDriverDataSource(new org.postgresql.Driver(), props.jdbcUrl(), "lab_" + role, pw);
  }

  /** Standard templates, then the lab tables; each only once (the database survives service restarts). */
  private void bootstrapSchema() {
    try (Connection c = dataSource("owner").getConnection(); Statement st = c.createStatement()) {
      c.setAutoCommit(false);
      if (!exists(st, "lab.outbox")) {
        st.execute(SqlTemplates.renderAll(SCHEMA));
      }
      if (!exists(st, "lab.notes")) {
        st.execute(resource("META-INF/dexter-lab/p02-lab.sql"));
      }
      c.commit();
    } catch (SQLException e) {
      throw new IllegalStateException("lab schema bootstrap failed: " + e.getMessage(), e);
    }
  }

  private static boolean exists(Statement st, String relation) throws SQLException {
    try (ResultSet rs = st.executeQuery("SELECT to_regclass('" + relation + "') IS NOT NULL")) {
      rs.next();
      return rs.getBoolean(1);
    }
  }

  private static String resource(String path) {
    try (InputStream in = P02Lab.class.getClassLoader().getResourceAsStream(path)) {
      if (in == null) {
        throw new IllegalStateException("missing resource " + path);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private void ensureTopics() {
    List<String> stages = STAGES.stream().map(EventConsumer.Stage::name).toList();
    TopicRegistry reg = new TopicRegistry(3, (short) 1).main("lab", "note")
        .consumer(TOPIC, PROJECTOR, stages)
        .consumer(TOPIC, CACHE_CONSUMER, stages);
    try (Admin admin = Admin.create(Map.<String, Object>of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
        props.kafkaBootstrap()))) {
      reg.ensure(admin, Duration.ofSeconds(20));
    }
  }

  private EventConsumer consumer(String name, EventConsumer.Handler handler, EnvelopeCodec codec,
      Producer<String, byte[]> producer) {
    EventConsumer c = new EventConsumer(new EventConsumer.Settings(props.kafkaBootstrap(), "lab", name,
        List.of(TOPIC), STAGES, 200, Duration.ofMillis(300)), handler, codec, producer, Clock.systemUTC());
    c.start();
    return c;
  }

  /** As a service configures Redis: ACL user, 200 ms timeout, fail fast while disconnected, 100 ms reconnect. */
  private LettuceConnectionFactory redisFactory(String host, int port) {
    RedisStandaloneConfiguration conf = new RedisStandaloneConfiguration(host, port);
    conf.setUsername(SCHEMA);
    conf.setPassword(RedisPassword.of(props.redisPassword()));
    LettuceClientConfiguration client = LettuceClientConfiguration.builder()
        .commandTimeout(Duration.ofMillis(200))
        .clientOptions(ClientOptions.builder()
            .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS).build())
        .clientResources(ClientResources.builder().reconnectDelay(Delay.constant(Duration.ofMillis(100))).build())
        .build();
    LettuceConnectionFactory f = new LettuceConnectionFactory(conf, client);
    f.afterPropertiesSet();
    f.start();
    return f;
  }

  private static void closeQuietly(AutoCloseable c) {
    try {
      c.close();
    } catch (Exception e) {
      log.debug("P02 lab close: {}", e.toString());
    }
  }

  // ------------------------------------------------------------------ auth

  /** Verifies the Bearer token with the real verifier; a rejection answers 401 with the reason (lab only). */
  public AuthContext authenticate(@Nullable String authorizationHeader) {
    String token = BearerTokens.extract(authorizationHeader);
    if (token == null) {
      throw new UnauthenticatedException("A Bearer access token is required (mint one: POST /_dev/p02/jwt/token)");
    }
    try {
      return verifier.verify(token);
    } catch (TokenRejectedException e) {
      throw new DexterException(CoreErrors.UNAUTHENTICATED, "The access token was rejected: " + e.reason(), List.of(),
          Map.of("reason", e.reason().name()), null);
    }
  }

  /** A user tenant from the token; service tokens get 403, as on real user endpoints. */
  public Tenant user(@Nullable String authorizationHeader) {
    AuthContext ctx = authenticate(authorizationHeader);
    if (ctx.isService()) {
      throw new ForbiddenException("Service tokens are not accepted on this endpoint");
    }
    return ctx.tenant();
  }

  public Map<String, Object> whoami(@Nullable String authorizationHeader) {
    AuthContext c = authenticate(authorizationHeader);
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("accepted", true);
    m.put("kind", c.kind().name());
    m.put("subject", c.subject());
    m.put("accountId", c.accountId());
    m.put("service", c.service());
    m.put("roles", new TreeSet<>(c.roles()));
    m.put("audiences", new TreeSet<>(c.audiences()));
    m.put("clientId", c.clientId());
    m.put("tokenId", c.tokenId());
    m.put("issuedAt", c.issuedAt().toString());
    m.put("expiresAt", c.expiresAt().toString());
    m.put("jwksFetches", issuer.fetches());
    return m;
  }

  // ------------------------------------------------------------------ notes (idempotency + outbox + cache)

  private static void validate(String title, long amountPaise) {
    if (title == null || title.isBlank() || title.length() > 200) {
      throw ValidationException.of("title", "TITLE_INVALID", "title must be 1..200 characters");
    }
    if (amountPaise < 0) {
      throw ValidationException.of("amountPaise", "AMOUNT_INVALID", "amountPaise must be >= 0");
    }
  }

  /**
   * POST with a required Idempotency-Key: key, row, outbox event and stored response in one transaction.
   * {@code holdMs} keeps the transaction (and the key's row lock) open, so a second request with the same key
   * waits on {@code lab_app}'s 2 s lock_timeout and gets 409 IDEMPOTENCY_IN_PROGRESS.
   */
  public IdempotentResponse create(Tenant t, UUID key, LabModel.CreateNote cmd, long holdMs) {
    validate(cmd.title(), cmd.amountPaise());
    if (holdMs < 0 || holdMs > MAX_HOLD_MS) {
      throw ValidationException.of("holdMs", "HOLD_INVALID", "holdMs must be 0.." + MAX_HOLD_MS);
    }
    LabRuntime r = runtime();
    IdempotentRequest fingerprint = IdempotentRequest.of("POST", PATH, cmd, mapper);
    return r.tx().write(t, () -> r.idempotency().execute(t, key, fingerprint, () -> {
      TenantScope.require();
      LabModel.Note n = r.jdbc().sql("INSERT INTO lab.notes (" + COLS + ") VALUES (:a, :id, :t, :amt, :at, 1)"
              + " RETURNING " + COLS)
          .param("a", t.accountId()).param("id", Ids.newId()).param("t", cmd.title()).param("amt", cmd.amountPaise())
          .param("at", Sql.ts(Instant.now().truncatedTo(ChronoUnit.MICROS)))
          .query(MAPPER).single();
      r.outbox().append(NOTE_CREATED, t.accountId(), n.id(), n.version(),
          new LabModel.NoteCreatedV1(n.id(), n.title(), n.amountPaise(), n.createdAt().toString()));
      r.cache().invalidator().afterCommit(t.accountId(), NOTES);
      if (holdMs > 0) {
        sleep(holdMs);
      }
      return ApiResponse.created(PATH + "/" + n.id(), n.view());
    }));
  }

  /** Versioned rename: 412 VERSION_CONFLICT on a stale version, 404 when missing; event + Path A bump. */
  public Map<String, Object> rename(Tenant t, UUID id, LabModel.RenameNote cmd) {
    validate(cmd.title(), 0);
    LabRuntime r = runtime();
    long v = r.tx().write(t, () -> {
      List<Long> updated = r.jdbc().sql("UPDATE lab.notes SET title = :t, version = version + 1"
              + " WHERE account_id = :a AND id = :id AND version = :v RETURNING version")
          .param("t", cmd.title()).param("a", t.accountId()).param("id", id).param("v", cmd.version())
          .query(Long.class).list();
      OptimisticUpdate.check(updated.size(), () -> r.jdbc().sql(
              "SELECT count(1) FROM lab.notes WHERE account_id = :a AND id = :id")
          .param("a", t.accountId()).param("id", id).query(Long.class).single() > 0, "note", cmd.version());
      long nv = updated.getFirst();
      r.outbox().append(NOTE_RENAMED, t.accountId(), id, nv, new LabModel.NoteRenamedV1(id, cmd.title()));
      r.cache().invalidator().afterCommit(t.accountId(), NOTES);
      return nv;
    });
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", id);
    m.put("title", cmd.title());
    m.put("version", v);
    return m;
  }

  /** A cached read and how it was served: {@code hit}, {@code miss} (this request loaded) or {@code bypass}. */
  public record Read(LabModel.View note, String cache) {}

  public Read get(Tenant t, UUID id) {
    LabRuntime r = runtime();
    long bypassBefore = r.cache().metrics().snapshot().bypass();
    boolean[] loaded = {false};
    Optional<LabModel.View> v = r.cache().tenant().find(t, NOTES, Map.of("id", id), LabModel.View.class, () -> {
      loaded[0] = true;
      return r.tx().read(t, () -> r.jdbc().sql("SELECT " + COLS + " FROM lab.notes WHERE account_id = :a AND id = :id")
          .param("a", t.accountId()).param("id", id).query(MAPPER).optional().map(LabModel.Note::view));
    });
    String how = r.cache().metrics().snapshot().bypass() > bypassBefore ? "bypass" : loaded[0] ? "miss" : "hit";
    return new Read(v.orElseThrow(() -> new NotFoundException("note")), how);
  }

  public Page<LabModel.View> list(Tenant t, PageRequest req) {
    LabRuntime r = runtime();
    Map<String, Object> params = new HashMap<>();
    params.put("limit", req.limit());
    params.put("cursor", req.cursor());
    LabModel.NotePage p = r.cache().tenant().get(t, NOTES, params, LabModel.NotePage.class,
        () -> r.tx().read(t, () -> {
          Page<LabModel.View> page = r.pager().page(r.jdbc(), LIST, req, Map.of(), Map.of()).map(LabModel.Note::view);
          return new LabModel.NotePage(page.items(), page.limit(), page.hasMore(), page.nextCursor());
        }));
    return new Page<>(p.items(), p.limit(), p.hasMore(), p.nextCursor());
  }

  // ------------------------------------------------------------------ cache controls

  public Map<String, Object> cacheInfo(Tenant t) {
    LabRuntime r = runtime();
    Map<String, Object> m = new LinkedHashMap<>();
    CacheMetrics.Snapshot s = r.cache().metrics().snapshot();
    m.put("metrics", s);
    m.put("redisCut", r.redisProxy().isCut());
    try {
      String gen = r.redis().opsForValue().get(r.cache().keys().genKey(NOTES, t.accountId()));
      m.put("generation", gen == null ? "0" : gen);
      List<String> keys = new ArrayList<>();
      for (String pattern : r.cache().keys().accountPatterns(t.accountId())) {
        try (Cursor<String> c = r.redis().scan(ScanOptions.scanOptions().match(pattern).count(500).build())) {
          c.forEachRemaining(keys::add);
        }
      }
      Collections.sort(keys);
      m.put("keys", keys);
    } catch (RuntimeException e) {
      m.put("generation", null);
      m.put("keys", List.of());
      m.put("redisError", e.getClass().getSimpleName());
    }
    return m;
  }

  /** Path B by hand: what the cache-inv consumer does when an event arrives. */
  public Map<String, Object> bump(Tenant t) {
    LabRuntime r = runtime();
    try {
      r.cache().invalidator().bump(t.accountId(), List.of(NOTES));
    } catch (CacheUnavailableException e) {
      throw new DexterException(CoreErrors.SERVICE_UNAVAILABLE, "Redis is unreachable; the bump was not applied", e);
    }
    return cacheInfo(t);
  }

  /** Privacy purge (ADR-0004 §7) of the caller's keys in the lab key space. */
  public Map<String, Object> purge(Tenant t) {
    LabRuntime r = runtime();
    long removed;
    try {
      removed = r.cache().invalidator().purgeAccount(t.accountId());
    } catch (CacheUnavailableException e) {
      throw new DexterException(CoreErrors.SERVICE_UNAVAILABLE, "Redis is unreachable; nothing was purged", e);
    }
    Map<String, Object> m = cacheInfo(t);
    m.put("removed", removed);
    return m;
  }

  public Map<String, Object> redis(boolean cut) {
    LabRuntime r = runtime();
    if (cut) {
      r.redisProxy().cut();
    } else {
      r.redisProxy().restore();
    }
    return Map.of("redisCut", r.redisProxy().isCut());
  }

  // ------------------------------------------------------------------ events viewer

  /** The caller's last 20 outbox rows (read as lab_relay: the app role may only INSERT into the outbox). */
  public List<Map<String, Object>> outbox(Tenant t) {
    LabRuntime r = runtime();
    return r.relayJdbc().sql("SELECT id, event_type, created_at, published_at, attempts FROM lab.outbox"
            + " WHERE message_key = :k ORDER BY created_at DESC LIMIT 20")
        .param("k", t.accountId().toString())
        .query((rs, i) -> {
          Map<String, Object> m = new LinkedHashMap<>();
          m.put("id", rs.getString("id"));
          m.put("type", rs.getString("event_type"));
          m.put("createdAt", Rows.instant(rs, "created_at").toString());
          Instant pub = Rows.instant(rs, "published_at");
          m.put("publishedAt", pub == null ? null : pub.toString());
          m.put("attempts", rs.getInt("attempts"));
          return m;
        }).list();
  }

  /** The projector's view of the caller (RLS-scoped): titles with projected versions, and totals. */
  public Map<String, Object> projection(Tenant t) {
    LabRuntime r = runtime();
    return r.tx().read(t, () -> {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("titles", r.jdbc().sql("SELECT note_id, title, projected_version FROM lab.note_titles"
              + " WHERE account_id = (SELECT lab.app_account()) ORDER BY note_id LIMIT 50")
          .query((rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("noteId", rs.getString("note_id"));
            row.put("title", rs.getString("title"));
            row.put("projectedVersion", rs.getLong("projected_version"));
            return row;
          }).list());
      m.put("stats", r.jdbc().sql("SELECT notes, amount_paise FROM lab.note_stats WHERE account_id = (SELECT lab.app_account())")
          .query((rs, i) -> Map.of("notes", rs.getLong("notes"), "amountPaise", rs.getLong("amount_paise")))
          .optional().orElse(Map.of("notes", 0L, "amountPaise", 0L)));
      return m;
    });
  }

  /** Messages for the caller in the projector's DLQ (read from the beginning, without a consumer group). */
  public List<Map<String, Object>> dlq(Tenant t) {
    LabRuntime r = runtime();
    String topic = Topics.dlq(TOPIC, PROJECTOR);
    Map<String, Object> cfg = new HashMap<>();
    cfg.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, props.kafkaBootstrap());
    cfg.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    cfg.put(ConsumerConfig.CLIENT_ID_CONFIG, "p02-lab-dlq-viewer");
    List<Map<String, Object>> out = new ArrayList<>();
    try (KafkaConsumer<String, byte[]> c = new KafkaConsumer<>(cfg, new StringDeserializer(), new ByteArrayDeserializer())) {
      List<TopicPartition> parts = new ArrayList<>();
      for (PartitionInfo p : c.partitionsFor(topic, Duration.ofSeconds(5))) {
        parts.add(new TopicPartition(topic, p.partition()));
      }
      c.assign(parts);
      c.seekToBeginning(parts);
      Map<TopicPartition, Long> end = c.endOffsets(parts, Duration.ofSeconds(5));
      long deadline = System.nanoTime() + Duration.ofSeconds(4).toNanos();
      while (System.nanoTime() < deadline && !caughtUp(c, parts, end)) {
        for (ConsumerRecord<String, byte[]> rec : c.poll(Duration.ofMillis(200))) {
          if (t.accountId().toString().equals(rec.key())) {
            out.add(describe(r.codec(), rec));
          }
        }
      }
    }
    return out.size() > 20 ? out.subList(out.size() - 20, out.size()) : out;
  }

  private static boolean caughtUp(KafkaConsumer<String, byte[]> c, List<TopicPartition> parts,
      Map<TopicPartition, Long> end) {
    for (TopicPartition tp : parts) {
      if (c.position(tp) < end.getOrDefault(tp, 0L)) {
        return false;
      }
    }
    return true;
  }

  private static Map<String, Object> describe(EnvelopeCodec codec, ConsumerRecord<String, byte[]> rec) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("partition", rec.partition());
    m.put("offset", rec.offset());
    m.put("attempt", header(rec, EventConsumer.H_ATTEMPT));
    m.put("originalTopic", header(rec, EventConsumer.H_ORIGINAL_TOPIC));
    m.put("error", header(rec, EventConsumer.H_ERROR));
    try {
      EventEnvelope e = codec.decode(rec.value());
      m.put("eventId", e.id().toString());
      m.put("type", e.type());
    } catch (RuntimeException poison) {
      m.put("type", "poison (not an envelope)");
    }
    return m;
  }

  private static @Nullable String header(ConsumerRecord<String, byte[]> rec, String key) {
    Header h = rec.headers().lastHeader(key);
    return h == null || h.value() == null ? null : new String(h.value(), StandardCharsets.UTF_8);
  }

  // ------------------------------------------------------------------ status

  public Map<String, Object> status(boolean init) {
    if (init) {
      try {
        runtime();
      } catch (DexterException ignored) {
        // reported below
      }
    }
    LabRuntime r = rt;
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("ready", r != null);
    m.put("error", lastError);
    m.put("issuer", LabIssuer.ISSUER);
    m.put("audience", LabIssuer.AUDIENCE);
    m.put("personas", Map.of("asha", LabIssuer.ASHA, "ravi", LabIssuer.RAVI, "svc-reco", "svc:reco"));
    m.put("defects", new java.util.TreeMap<>(LabIssuer.DEFECTS));
    m.put("currentKid", issuer.currentKid());
    m.put("publishedKids", issuer.publishedKids());
    m.put("jwksFetches", issuer.fetches());
    m.put("topic", TOPIC);
    m.put("dlqTopic", Topics.dlq(TOPIC, PROJECTOR));
    m.put("retryStages", STAGES.stream().map(EventConsumer.Stage::name).toList());
    if (r != null) {
      m.put("relayLeader", r.relay().isLeader());
      m.put("relayPublished", r.relay().publishedCount());
      m.put("redisCut", r.redisProxy().isCut());
      m.put("cache", r.cache().metrics().snapshot());
    }
    return m;
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted", e);
    }
  }
}
