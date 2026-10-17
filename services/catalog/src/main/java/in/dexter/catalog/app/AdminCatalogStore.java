package in.dexter.catalog.app;

import in.dexter.catalog.domain.CardDocument;
import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Admin reads and writes on the catalog_admin pool (RLS: editor roles only). Needs the admin TenantTx. */
public interface AdminCatalogStore {
  record Issuer(UUID id, String code, String name, String type) {}

  /** A card_products row (any status). */
  record Card(UUID id, UUID issuerId, String slug, String name, String network, String status,
      Integer currentVersion, byte[] contentHash, Instant updatedAt, long version) {}

  record Draft(UUID cardId, String documentJson, UUID updatedBy, Instant updatedAt, long version) {}

  record Version(UUID cardId, int version, String snapshotJson, Instant effectiveFrom, Instant effectiveTo) {}

  /** One rule row of a version. */
  record RuleRow(UUID id, UUID cardId, int version, CardDocument.Rule rule, Instant validFrom) {}

  record OfferRow(UUID id, UUID issuerId, UUID cardProductId, UUID merchantId, String categoryPath, String title,
      String kind, Integer valueBps, Long valuePaise, Long maxDiscountPaise, long minSpendPaise, Instant validFrom,
      Instant validTo, boolean stackable, String termsUrl, String status, int version) {}

  Optional<Issuer> issuerByCode(String code);

  Optional<Issuer> issuer(UUID id);

  void insertIssuer(Issuer issuer);

  Page<Card> list(PageRequest page, String status);

  Optional<Card> find(UUID id);

  Optional<Card> lock(UUID id);

  boolean slugTaken(String slug);

  /** A new draft-only card (status draft, no version) with the document's searchable columns. */
  void insertCard(UUID id, UUID issuerId, CardDocument doc, Instant now);

  /** Copies the published document into the row's columns, status published, version bump. */
  void markPublished(UUID id, UUID issuerId, CardDocument doc, int currentVersion, byte[] contentHash, Instant now);

  void markRetired(UUID id, Instant now);

  Optional<Draft> draft(UUID cardId);

  /** Insert (expectedVersion null) or replace when the stored version matches; false on a version mismatch. */
  boolean saveDraft(UUID cardId, String documentJson, UUID adminId, Long expectedVersion, Instant now);

  Optional<Version> currentVersion(UUID cardId);

  List<Version> versions(UUID cardId);

  /** Closes the open version and the open rules of the card at {@code at}. */
  void closeCurrent(UUID cardId, Instant at);

  /** The rule rows of one version of a card. */
  List<RuleRow> rules(UUID cardId, int version);

  void insertVersion(UUID cardId, int version, String snapshotJson, Instant from);

  void insertRules(List<RuleRow> rules);

  void replaceMilestone(UUID cardId, CardDocument.Milestone milestone);

  void insertOffer(OfferRow offer);

  Optional<OfferRow> lockOffer(UUID id);

  void withdrawOffer(UUID id, Instant now);
}
