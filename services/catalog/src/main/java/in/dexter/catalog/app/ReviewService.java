package in.dexter.catalog.app;

import in.dexter.catalog.app.AdminCatalogStore.Card;
import in.dexter.catalog.app.AdminCatalogStore.Draft;
import in.dexter.catalog.app.AdminCatalogStore.Version;
import in.dexter.catalog.app.IngestionStore.Promotion;
import in.dexter.catalog.app.IngestionStore.ReviewItem;
import in.dexter.catalog.domain.CardDiff;
import in.dexter.catalog.domain.CardDocument;
import in.dexter.catalog.domain.CatalogErrors;
import in.dexter.core.error.DexterException;
import in.dexter.core.error.FieldError;
import in.dexter.core.error.NotFoundException;
import in.dexter.core.error.ValidationException;
import in.dexter.core.id.Ids;
import in.dexter.core.tenant.Tenant;
import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import in.dexter.db.tenant.TenantTx;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The review queue (MASTER_PLAN P10, admin only, keyset): an editor approves or rejects an item. Approve runs the
 * {@link Promoter} on the card's published document with the diff applied, in one transaction with the decision and
 * the promotion row: a second approve of the same item (sequential or concurrent — the item row is locked) answers
 * the existing promotion and creates no version. An item is stale when the card moved past its base version; a card
 * with unpublished draft edits must be published or discarded first (the promotion would otherwise be undone by
 * that draft's next publish).
 */
public final class ReviewService {
  static final Set<String> STATUSES = Set.of("pending", "approved", "rejected", "superseded");

  public record ReviewView(UUID id, String status, UUID cardProductId, String cardSlug, String cardName,
      int baseVersion, double confidence, JsonNode changes, UUID sourceId, UUID snapshotId, String url,
      String fetchedAt, String createdAt, UUID reviewerId, String decidedAt, String reason, PromotionView promotion) {}

  public record PromotionView(UUID id, int newVersion, UUID promotedBy, String promotedAt) {}

  /** The approve answer; {@code replayed} = this item was already approved (no new version). */
  public record Approval(UUID reviewItemId, UUID cardProductId, String cardSlug, int newVersion, String promotedAt,
      boolean replayed) {}

  private final TenantTx tx;
  private final IngestionStore store;
  private final AdminCatalogStore catalog;
  private final Promoter promoter;
  private final ObjectMapper mapper;

  public ReviewService(TenantTx tx, IngestionStore store, AdminCatalogStore catalog, Promoter promoter,
      ObjectMapper mapper) {
    this.tx = tx;
    this.store = store;
    this.catalog = catalog;
    this.promoter = promoter;
    this.mapper = mapper;
  }

  public Page<ReviewView> list(Tenant admin, PageRequest page, String status) {
    String s = status == null ? "pending" : status;
    if (!STATUSES.contains(s)) {
      throw new ValidationException(List.of(new FieldError("status", "INVALID",
          "pending, approved, rejected or superseded")));
    }
    return tx.read(admin, () -> store.reviews(page, s)).map(r -> view(r, null));
  }

  public ReviewView get(Tenant admin, UUID id) {
    return tx.read(admin, () -> {
      ReviewItem r = store.review(id).orElseThrow(() -> new NotFoundException("review item"));
      return view(r, store.promotionFor(id).orElse(null));
    });
  }

  public Approval approve(Tenant editor, UUID id) {
    return tx.write(editor, () -> {
      ReviewItem item = store.lockReview(id).orElseThrow(() -> new NotFoundException("review item"));
      switch (item.status()) {
        case "approved" -> {
          Promotion p = store.promotionFor(id).orElseThrow();
          return new Approval(id, p.cardProductId(), item.cardSlug(), p.newVersion(), p.promotedAt().toString(), true);
        }
        case "rejected" -> throw new DexterException(CatalogErrors.REVIEW_DECIDED, "The item was rejected");
        case "superseded" -> throw new DexterException(CatalogErrors.REVIEW_STALE,
            "A newer review item replaced this one");
        default -> {
          // pending
        }
      }
      Card card = catalog.lock(item.cardProductId()).orElseThrow(() -> new NotFoundException("card"));
      if (!"published".equals(card.status()) || card.currentVersion() == null
          || card.currentVersion() != item.baseVersion()) {
        throw new DexterException(CatalogErrors.REVIEW_STALE, "The card is at version " + card.currentVersion()
            + " (status " + card.status() + "); the diff was made against version " + item.baseVersion());
      }
      Version current = catalog.currentVersion(card.id()).orElseThrow();
      Draft draft = catalog.draft(card.id()).orElse(null);
      if (draft != null && promoter.differsFromPublished(card, promoter.read(draft.documentJson()))) {
        throw new DexterException(CatalogErrors.DRAFT_PENDING, "The card has unpublished draft edits: publish or"
            + " discard them (DELETE /v1/admin/catalog/cards/" + card.id() + "/draft) first");
      }
      CardDocument doc = CatalogAdminService.valid(CardDiff.apply(promoter.document(current), changes(item)));
      if (draft != null) {
        catalog.deleteDraft(card.id()); // it equals the old version; the next read recreates it from the new one
      }
      CatalogAdminService.PublishResult result = promoter.publish(card, doc);
      Instant now = promoter.now();
      store.decide(id, "approved", editor.accountId(), null, now);
      store.insertPromotion(new Promotion(Ids.newId(), id, card.id(), result.version(), editor.accountId(), now));
      return new Approval(id, card.id(), card.slug(), result.version(), now.toString(), false);
    });
  }

  public ReviewView reject(Tenant editor, UUID id, String reason) {
    if (reason != null && reason.length() > 500) {
      throw new ValidationException(List.of(new FieldError("reason", "TOO_LONG", "At most 500 characters")));
    }
    return tx.write(editor, () -> {
      ReviewItem item = store.lockReview(id).orElseThrow(() -> new NotFoundException("review item"));
      if ("rejected".equals(item.status())) {
        return view(item, null);
      }
      if (!"pending".equals(item.status())) {
        throw new DexterException(CatalogErrors.REVIEW_DECIDED, "The item is " + item.status());
      }
      store.decide(id, "rejected", editor.accountId(), reason == null || reason.isBlank() ? null : reason.strip(),
          promoter.now());
      return view(store.review(id).orElseThrow(), null);
    });
  }

  private List<CardDiff.Change> changes(ReviewItem item) {
    JsonNode diff = mapper.readTree(item.diffJson());
    List<CardDiff.Change> out = new ArrayList<>();
    for (JsonNode c : diff.get("changes")) {
      out.add(new CardDiff.Change(c.get("path").asString(), value(c.get("from")), value(c.get("to")),
          c.get("confidence") == null ? 0 : c.get("confidence").asDouble()));
    }
    return out;
  }

  private Object value(JsonNode n) {
    if (n == null || n.isNull()) {
      return null;
    }
    if (n.isObject()) {
      return mapper.convertValue(n, java.util.Map.class);
    }
    return n.isIntegralNumber() ? (Object) n.asLong() : n.asString();
  }

  private ReviewView view(ReviewItem r, Promotion p) {
    JsonNode diff = mapper.readTree(r.diffJson());
    return new ReviewView(r.id(), r.status(), r.cardProductId(), r.cardSlug(), r.cardName(), r.baseVersion(),
        r.confidence(), diff.get("changes"), r.sourceId(), r.snapshotId(), r.url(), str(r.fetchedAt()),
        str(r.createdAt()), r.reviewerId(), str(r.decidedAt()), r.reason(),
        p == null ? null : new PromotionView(p.id(), p.newVersion(), p.promotedBy(), p.promotedAt().toString()));
  }

  private static String str(Instant i) {
    return i == null ? null : i.toString();
  }
}
