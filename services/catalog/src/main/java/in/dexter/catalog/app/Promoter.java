package in.dexter.catalog.app;

import in.dexter.catalog.app.AdminCatalogStore.Card;
import in.dexter.catalog.app.AdminCatalogStore.Issuer;
import in.dexter.catalog.app.AdminCatalogStore.RuleRow;
import in.dexter.catalog.app.AdminCatalogStore.Version;
import in.dexter.catalog.domain.CardDocument;
import in.dexter.catalog.domain.CardDocuments;
import in.dexter.catalog.domain.CardSnapshots;
import in.dexter.contracts.events.catalog.CardPublishedV1;
import in.dexter.core.error.FieldError;
import in.dexter.core.error.ValidationException;
import in.dexter.core.id.Ids;
import in.dexter.events.OutboxWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The promoter (MASTER_PLAN P10): the only code that writes published catalog data. An editor's publish (P09) and an
 * approved review item (P10) both end here, inside the caller's transaction on a locked card row: the open version
 * and its rules close at {@code now}, version n+1 and its rules open at the same instant (the exclusion constraint
 * forbids overlap), the milestone and the card's public columns follow, and {@code catalog.card.published} carries
 * the full snapshot. A document whose canonical hash equals the published one changes nothing ({@code changed:
 * false}).
 */
public final class Promoter {
  private final AdminCatalogStore store;
  private final OutboxWriter outbox;
  private final ObjectMapper mapper;
  private final ObjectMapper lenient = JsonMapper.builder()
      .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
  private final Clock clock;

  public Promoter(AdminCatalogStore store, OutboxWriter outbox, ObjectMapper mapper, Clock clock) {
    this.store = store;
    this.outbox = outbox;
    this.mapper = mapper;
    this.clock = clock;
  }

  /** Publishes {@code doc} (already validated) as the card's next version. Call inside a transaction, card locked. */
  CatalogAdminService.PublishResult publish(Card c, CardDocument doc) {
    byte[] hash = hash(doc);
    if ("published".equals(c.status()) && Arrays.equals(hash, c.contentHash())) {
      Version v = store.currentVersion(c.id()).orElseThrow();
      return new CatalogAdminService.PublishResult(c.id(), c.slug(), c.status(), v.version(),
          v.effectiveFrom().toString(), null, false);
    }
    Issuer issuer = store.issuerByCode(doc.issuerCode()).orElseThrow(() -> new ValidationException(List.of(
        new FieldError("issuerCode", "UNKNOWN_ISSUER", "No issuer " + doc.issuerCode()))));
    Instant now = now();
    int version = c.currentVersion() == null ? 1 : c.currentVersion() + 1;
    List<UUID> ruleIds = new ArrayList<>();
    List<RuleRow> rules = new ArrayList<>();
    for (CardDocument.Rule r : doc.rules()) {
      UUID rid = Ids.newId();
      ruleIds.add(rid);
      rules.add(new RuleRow(rid, c.id(), version, r, now));
    }
    Map<String, Object> snapshot = CardSnapshots.of(c.id(), doc, issuer.name(), ruleIds);
    store.closeCurrent(c.id(), now);
    store.insertVersion(c.id(), version, mapper.writeValueAsString(snapshot), now);
    store.insertRules(rules);
    store.replaceMilestone(c.id(), doc.milestone());
    store.markPublished(c.id(), issuer.id(), doc, version, hash, now);
    outbox.append(CatalogEvents.CARD_PUBLISHED, null, c.id(), c.version() + 1,
        new CardPublishedV1(c.id(), c.slug(), "published", version, now.toString(), null, snapshot));
    return new CatalogAdminService.PublishResult(c.id(), c.slug(), "published", version, now.toString(), null, true);
  }

  /**
   * A version as a document: the snapshot's card fields, the rules from the version's rule rows (they carry the
   * merchant ids; seeded snapshots name merchants by slug only) and the snapshot's milestone.
   */
  CardDocument document(Version v) {
    ObjectNode snap = (ObjectNode) lenient.readTree(v.snapshotJson());
    Map<String, String> slugByRuleId = new HashMap<>();
    JsonNode rules = snap.get("rules");
    if (rules != null) {
      rules.forEach(r -> {
        JsonNode slug = r.get("merchantSlug");
        if (r.get("id") != null && slug != null && !slug.isNull()) {
          slugByRuleId.put(r.get("id").asString(), slug.asString());
        }
      });
    }
    snap.remove("rules");
    CardDocument base = lenient.treeToValue(snap, CardDocument.class);
    List<CardDocument.Rule> fromRows = new ArrayList<>();
    for (RuleRow row : store.rules(v.cardId(), v.version())) {
      CardDocument.Rule r = row.rule();
      fromRows.add(new CardDocument.Rule(r.categoryPath(), r.merchantId(), slugByRuleId.get(row.id().toString()),
          r.rewardType(), r.rateBps(), r.pointValueMilliPaise(), r.capPaise(), r.capPeriod(), r.minTxnPaise(),
          r.excludedPaths(), r.priority()));
    }
    return CardDocuments.normalize(new CardDocument(base.slug(), base.name(), base.issuerCode(), base.network(),
        base.tier(), base.joiningFeePaise(), base.annualFeePaise(), base.feeWaiverSpendPaise(), base.aprBps(),
        base.forexMarkupBps(), base.fuelSurchargeWaiverBps(), base.features(), fromRows, base.milestone()));
  }

  /** SHA-256 of the canonical document (records in declaration order, features sorted). */
  byte[] hash(CardDocument doc) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(json(doc).getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** True when the document differs from what the card publishes (or the card was never published). */
  boolean differsFromPublished(Card c, CardDocument doc) {
    return !"published".equals(c.status()) || !Arrays.equals(hash(doc), c.contentHash());
  }

  String json(CardDocument doc) {
    return mapper.writeValueAsString(CardDocuments.normalize(doc));
  }

  CardDocument read(String json) {
    return lenient.readValue(json, CardDocument.class);
  }

  Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }
}
