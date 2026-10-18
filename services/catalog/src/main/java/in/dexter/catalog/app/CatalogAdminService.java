package in.dexter.catalog.app;

import in.dexter.catalog.app.AdminCatalogStore.Card;
import in.dexter.catalog.app.AdminCatalogStore.Draft;
import in.dexter.catalog.app.AdminCatalogStore.Issuer;
import in.dexter.catalog.app.AdminCatalogStore.OfferRow;
import in.dexter.catalog.app.AdminCatalogStore.Version;
import in.dexter.catalog.domain.CardDocument;
import in.dexter.catalog.domain.CardDocuments;
import in.dexter.catalog.domain.CatalogErrors;
import in.dexter.contracts.events.catalog.CardPublishedV1;
import in.dexter.contracts.events.catalog.OfferChangedV1;
import in.dexter.core.error.DexterException;
import in.dexter.core.error.FieldError;
import in.dexter.core.error.NotFoundException;
import in.dexter.core.error.ValidationException;
import in.dexter.core.error.VersionConflictException;
import in.dexter.core.id.Ids;
import in.dexter.core.tenant.Tenant;
import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import in.dexter.db.tenant.TenantTx;
import in.dexter.events.OutboxWriter;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Catalog administration (MASTER_PLAN P09, CATALOG_EDITOR): issuers, card drafts, publish/retire, offers. Runs on the
 * catalog_admin pool as the admin (RLS lets only editor roles in). A publish freezes the draft into an immutable
 * version: the previous version and its rules are closed at the same instant the new ones open (the exclusion
 * constraint guarantees no overlap), the card's public columns follow, and {@code catalog.card.published} carries
 * the full snapshot.
 */
public final class CatalogAdminService {
  static final Set<String> ISSUER_TYPES = Set.of("bank", "nbfc", "cobrand");
  static final Pattern ISSUER_CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,39}");
  static final Pattern PATH = Pattern.compile("[a-z][a-z0-9_]{0,31}(\\.[a-z][a-z0-9_]{0,31}){0,3}");

  public record NewIssuer(String code, String name, String type) {}

  public record CardSummary(UUID id, String slug, String name, String network, String status, Integer currentVersion,
      String updatedAt) {}

  /** The editor's view of a card: status, the published version, and the draft (with its ETag version). */
  public record DraftView(UUID cardId, String status, Integer currentVersion, long draftVersion,
      boolean unpublishedChanges, CardDocument document) {}

  public record PublishResult(UUID cardId, String slug, String status, int version, String effectiveFrom,
      String effectiveTo, boolean changed) {}

  public record VersionView(int version, String effectiveFrom, String effectiveTo, JsonNode snapshot) {}

  public record NewOffer(String issuerCode, UUID cardProductId, UUID merchantId, String categoryPath, String title,
      String kind, Integer valueBps, Long valuePaise, Long maxDiscountPaise, Long minSpendPaise, Instant validFrom,
      Instant validTo, Boolean stackable, String termsUrl) {}

  private final TenantTx tx;
  private final AdminCatalogStore store;
  private final OutboxWriter outbox;
  private final ObjectMapper mapper;
  private final ObjectMapper lenient = JsonMapper.builder()
      .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
  private final Promoter promoter;

  public CatalogAdminService(TenantTx tx, AdminCatalogStore store, OutboxWriter outbox, ObjectMapper mapper,
      Promoter promoter) {
    this.tx = tx;
    this.store = store;
    this.outbox = outbox;
    this.mapper = mapper;
    this.promoter = promoter;
  }

  // ------------------------------------------------------------------------------------------- issuers

  public Issuer createIssuer(Tenant admin, NewIssuer cmd) {
    List<FieldError> e = new ArrayList<>();
    if (cmd == null || cmd.code() == null || !ISSUER_CODE.matcher(cmd.code()).matches()) {
      e.add(new FieldError("code", "INVALID", "UPPER_SNAKE, 2-40 characters"));
    }
    if (cmd == null || cmd.name() == null || cmd.name().isBlank() || cmd.name().length() > 120) {
      e.add(new FieldError("name", "INVALID", "1-120 characters"));
    }
    if (cmd == null || !ISSUER_TYPES.contains(cmd.type())) {
      e.add(new FieldError("type", "INVALID", "bank, nbfc or cobrand"));
    }
    if (!e.isEmpty()) {
      throw new ValidationException(e);
    }
    return tx.write(admin, () -> {
      if (store.issuerByCode(cmd.code()).isPresent()) {
        throw new DexterException(CatalogErrors.ISSUER_EXISTS, "Issuer " + cmd.code() + " already exists");
      }
      Issuer i = new Issuer(Ids.newId(), cmd.code(), cmd.name().strip(), cmd.type());
      store.insertIssuer(i);
      return i;
    });
  }

  // ------------------------------------------------------------------------------------------- cards

  public Page<CardSummary> list(Tenant admin, PageRequest page, String status) {
    if (status != null && !Set.of("draft", "published", "retired").contains(status)) {
      throw new ValidationException(List.of(new FieldError("status", "INVALID", "draft, published or retired")));
    }
    return tx.read(admin, () -> store.list(page, status)).map(c -> new CardSummary(c.id(), c.slug(), c.name(),
        c.network(), c.status(), c.currentVersion(), c.updatedAt() == null ? null : c.updatedAt().toString()));
  }

  /** A new card exists only as a draft (invisible to the public) until its first publish. */
  public DraftView create(Tenant admin, CardDocument raw) {
    CardDocument doc = valid(raw);
    return tx.write(admin, () -> {
      Issuer issuer = issuer(doc.issuerCode());
      if (store.slugTaken(doc.slug())) {
        throw new DexterException(CatalogErrors.SLUG_TAKEN, "Slug " + doc.slug() + " is taken");
      }
      UUID id = Ids.newId();
      Instant now = now();
      store.insertCard(id, issuer.id(), doc, now);
      store.saveDraft(id, json(doc), admin.accountId(), null, now);
      return new DraftView(id, "draft", null, 0, true, doc);
    });
  }

  /** The draft; a published card without one gets a draft equal to its current version (created on first read). */
  public DraftView draft(Tenant admin, UUID id) {
    return tx.write(admin, () -> {
      Card c = store.find(id).orElseThrow(() -> new NotFoundException("card"));
      Draft d = ensureDraft(admin, c);
      CardDocument doc = read(d.documentJson());
      return new DraftView(c.id(), c.status(), c.currentVersion(), d.version(), changed(c, doc), doc);
    });
  }

  /** Replaces the draft (If-Match: the draft version). The slug is fixed at creation. */
  public DraftView saveDraft(Tenant admin, UUID id, long expectedVersion, CardDocument raw) {
    CardDocument doc = valid(raw);
    return tx.write(admin, () -> {
      Card c = store.lock(id).orElseThrow(() -> new NotFoundException("card"));
      if ("retired".equals(c.status())) {
        throw new DexterException(CatalogErrors.CARD_RETIRED, "A retired card cannot be edited");
      }
      if (!c.slug().equals(doc.slug())) {
        throw new ValidationException(List.of(new FieldError("slug", "IMMUTABLE", "The slug is fixed: " + c.slug())));
      }
      issuer(doc.issuerCode());
      Draft current = ensureDraft(admin, c);
      if (current.version() != expectedVersion) {
        throw new VersionConflictException("draft", expectedVersion);
      }
      store.saveDraft(id, json(doc), admin.accountId(), expectedVersion, now());
      return new DraftView(c.id(), c.status(), c.currentVersion(), expectedVersion + 1, changed(c, doc), doc);
    });
  }

  /**
   * Publishes the draft as version n+1. Publishing an unchanged draft (same content hash as the current version)
   * changes nothing and answers the current version ({@code changed=false}): the call is idempotent.
   */
  public PublishResult publish(Tenant admin, UUID id) {
    return tx.write(admin, () -> {
      Card c = store.lock(id).orElseThrow(() -> new NotFoundException("card"));
      if ("retired".equals(c.status())) {
        throw new DexterException(CatalogErrors.CARD_RETIRED, "A retired card cannot be published");
      }
      Draft d = ensureDraft(admin, c);
      CardDocument doc = valid(read(d.documentJson()));
      issuer(doc.issuerCode());
      return promoter.publish(c, doc);
    });
  }

  /**
   * Discards the unpublished edits of a published card: the draft row goes, and the next read recreates it from the
   * current version (P10: an approved review item needs a card without pending draft edits).
   */
  public void discardDraft(Tenant admin, UUID id) {
    tx.writeVoid(admin, () -> {
      Card c = store.lock(id).orElseThrow(() -> new NotFoundException("card"));
      if (c.currentVersion() == null) {
        throw new DexterException(CatalogErrors.DRAFT_NOT_DISCARDABLE,
            "A card that was never published is only its draft");
      }
      store.deleteDraft(id);
    });
  }

  /** Takes a published card out of the public catalog: its version and rules close now; the event says retired. */
  public PublishResult retire(Tenant admin, UUID id) {
    return tx.write(admin, () -> {
      Card c = store.lock(id).orElseThrow(() -> new NotFoundException("card"));
      if (!"published".equals(c.status())) {
        throw new DexterException(CatalogErrors.CARD_RETIRED, "Only a published card can be retired (status "
            + c.status() + ")");
      }
      Version v = store.currentVersion(id).orElseThrow();
      Instant now = now();
      store.closeCurrent(id, now);
      store.replaceMilestone(id, null);
      store.markRetired(id, now);
      Map<String, Object> snapshot = lenient.readValue(v.snapshotJson(), new TypeReference<Map<String, Object>>() {});
      outbox.append(CatalogEvents.CARD_RETIRED, null, id, c.version() + 1,
          new CardPublishedV1(id, c.slug(), "retired", v.version(), v.effectiveFrom().toString(), now.toString(),
              snapshot));
      return new PublishResult(id, c.slug(), "retired", v.version(), v.effectiveFrom().toString(), now.toString(),
          true);
    });
  }

  public List<VersionView> versions(Tenant admin, UUID id) {
    return tx.read(admin, () -> {
      store.find(id).orElseThrow(() -> new NotFoundException("card"));
      return store.versions(id).stream().map(v -> new VersionView(v.version(), v.effectiveFrom().toString(),
          v.effectiveTo() == null ? null : v.effectiveTo().toString(), mapper.readTree(v.snapshotJson()))).toList();
    });
  }

  // ------------------------------------------------------------------------------------------- offers

  public OfferChangedV1 createOffer(Tenant admin, NewOffer o) {
    List<FieldError> e = new ArrayList<>();
    if (o == null) {
      throw new ValidationException(List.of(new FieldError("body", "REQUIRED", "An offer")));
    }
    if (o.title() == null || o.title().isBlank() || o.title().length() > 160) {
      e.add(new FieldError("title", "INVALID", "1-160 characters"));
    }
    if (!Set.of("pct", "flat", "cashback").contains(o.kind())) {
      e.add(new FieldError("kind", "INVALID", "pct, flat or cashback"));
    } else if ("pct".equals(o.kind()) ? o.valueBps() == null || o.valueBps() < 1 || o.valueBps() > 10_000
        || o.valuePaise() != null : o.valuePaise() == null || o.valuePaise() < 1 || o.valueBps() != null) {
      e.add(new FieldError("pct".equals(o.kind()) ? "valueBps" : "valuePaise", "INVALID",
          "pct offers carry valueBps (1-10000); flat and cashback offers carry valuePaise"));
    }
    if (o.categoryPath() != null && !PATH.matcher(o.categoryPath()).matches()) {
      e.add(new FieldError("categoryPath", "INVALID", "A category path"));
    }
    if (o.validFrom() == null || o.validTo() != null && !o.validTo().isAfter(o.validFrom())) {
      e.add(new FieldError("validTo", "INVALID_RANGE", "validFrom required; validTo after it (or open)"));
    }
    if (o.termsUrl() != null && !o.termsUrl().startsWith("https://")) {
      e.add(new FieldError("termsUrl", "INVALID", "An https URL"));
    }
    if (o.maxDiscountPaise() != null && o.maxDiscountPaise() < 1 || o.minSpendPaise() != null && o.minSpendPaise() < 0) {
      e.add(new FieldError("maxDiscountPaise", "INVALID", "Positive cap, non-negative minimum spend"));
    }
    if (!e.isEmpty()) {
      throw new ValidationException(e);
    }
    return tx.write(admin, () -> {
      Issuer issuer = issuer(o.issuerCode());
      if (o.cardProductId() != null) {
        Card c = store.find(o.cardProductId()).orElseThrow(() -> new ValidationException(List.of(
            new FieldError("cardProductId", "UNKNOWN_CARD", "No such card product"))));
        if (!c.issuerId().equals(issuer.id())) {
          throw new ValidationException(List.of(new FieldError("cardProductId", "OTHER_ISSUER",
              "The card belongs to another issuer")));
        }
      }
      OfferRow row = new OfferRow(Ids.newId(), issuer.id(), o.cardProductId(), o.merchantId(), o.categoryPath(),
          o.title().strip(), o.kind(), o.valueBps(), o.valuePaise(), o.maxDiscountPaise(),
          o.minSpendPaise() == null ? 0 : o.minSpendPaise(), o.validFrom().truncatedTo(ChronoUnit.MICROS),
          o.validTo() == null ? null : o.validTo().truncatedTo(ChronoUnit.MICROS),
          Boolean.TRUE.equals(o.stackable()), o.termsUrl(), "active", 0);
      store.insertOffer(row);
      return emit(row, now());
    });
  }

  public OfferChangedV1 withdrawOffer(Tenant admin, UUID id) {
    return tx.write(admin, () -> {
      OfferRow o = store.lockOffer(id).orElseThrow(() -> new NotFoundException("offer"));
      if ("withdrawn".equals(o.status())) {
        throw new DexterException(CatalogErrors.OFFER_WITHDRAWN, "The offer is already withdrawn");
      }
      Instant now = now();
      store.withdrawOffer(id, now);
      OfferRow w = new OfferRow(o.id(), o.issuerId(), o.cardProductId(), o.merchantId(), o.categoryPath(), o.title(),
          o.kind(), o.valueBps(), o.valuePaise(), o.maxDiscountPaise(), o.minSpendPaise(), o.validFrom(), o.validTo(),
          o.stackable(), o.termsUrl(), "withdrawn", o.version() + 1);
      return emit(w, now);
    });
  }

  private OfferChangedV1 emit(OfferRow o, Instant now) {
    OfferChangedV1 e = new OfferChangedV1(o.id(), o.status(), o.issuerId(), o.cardProductId(), o.merchantId(),
        o.categoryPath(), o.title(), o.kind(), o.valueBps(), o.valuePaise(), o.maxDiscountPaise(), o.minSpendPaise(),
        o.validFrom().toString(), o.validTo() == null ? null : o.validTo().toString(), o.stackable(), o.version(),
        now.toString());
    outbox.append(CatalogEvents.OFFER_CHANGED, null, o.id(), o.version(), e);
    return e;
  }

  // ------------------------------------------------------------------------------------------- helpers

  private Draft ensureDraft(Tenant admin, Card c) {
    return store.draft(c.id()).orElseGet(() -> {
      Version v = store.currentVersion(c.id())
          .orElseThrow(() -> new IllegalStateException("card " + c.id() + " has neither a draft nor a version"));
      store.saveDraft(c.id(), json(promoter.document(v)), admin.accountId(), null, now());
      return store.draft(c.id()).orElseThrow();
    });
  }

  private Issuer issuer(String code) {
    return store.issuerByCode(code).orElseThrow(() -> new ValidationException(List.of(
        new FieldError("issuerCode", "UNKNOWN_ISSUER", "No issuer " + code + " (POST /v1/admin/catalog/issuers)"))));
  }

  private boolean changed(Card c, CardDocument doc) {
    return promoter.differsFromPublished(c, doc);
  }

  static CardDocument valid(CardDocument raw) {
    List<FieldError> errors = CardDocuments.validate(raw);
    if (!errors.isEmpty()) {
      throw new ValidationException(errors);
    }
    return CardDocuments.normalize(raw);
  }

  private String json(CardDocument doc) {
    return promoter.json(doc);
  }

  private CardDocument read(String json) {
    return promoter.read(json);
  }

  private Instant now() {
    return promoter.now();
  }
}
