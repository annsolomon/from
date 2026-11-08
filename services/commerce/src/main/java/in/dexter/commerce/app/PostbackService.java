package in.dexter.commerce.app;

import in.dexter.commerce.app.CommerceViews.PostbackResult;
import in.dexter.commerce.app.TrackingStore.Click;
import in.dexter.commerce.app.TrackingStore.Conversion;
import in.dexter.commerce.domain.CommerceErrors;
import in.dexter.commerce.domain.ConversionStatus;
import in.dexter.commerce.domain.PostbackSignature;
import in.dexter.core.error.DexterException;
import in.dexter.core.error.FieldError;
import in.dexter.core.error.ValidationException;
import in.dexter.core.id.Ids;
import in.dexter.db.tenant.TenantTx;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /v1/webhooks/affiliate/{network}} (MASTER_PLAN P17): an affiliate network reports a conversion.
 * Signed like billing's webhooks ({@code Postback-Signature}, HMAC-SHA256 with a per-network secret, ±5 min), and
 * idempotent by (network, ref): the first delivery creates the conversion, a replay changes nothing
 * ({@code duplicate}), a later one moves pending forward ({@code updated}). {@code subid} is the click id the
 * redirect appended; when it names a logged click the conversion is attributed to that click's deal and viewer,
 * and the deal's counters gain the conversion (on creation) and the commission (when it is approved) — in the
 * same transaction, so the counters can never count a conversion twice.
 */
public final class PostbackService {
  public static final String PATH = "/v1/webhooks/affiliate";
  private static final Pattern REF = Pattern.compile("[A-Za-z0-9._:-]{1,100}");

  public record Postback(@Nullable String ref, @Nullable String subid, @Nullable String status,
      @Nullable Long amountPaise, @Nullable Long commissionPaise, @Nullable String occurredAt) {}

  private final TenantTx tx;
  private final TrackingStore tracking;
  private final Map<String, byte[]> secrets;
  private final JsonMapper mapper;
  private final Clock clock;

  public PostbackService(TenantTx tx, TrackingStore tracking, Map<String, byte[]> secrets, JsonMapper mapper,
      Clock clock) {
    this.tx = tx;
    this.tracking = tracking;
    this.secrets = Map.copyOf(secrets);
    this.mapper = mapper;
    this.clock = clock;
  }

  public PostbackResult receive(String network, @Nullable String signature, byte[] body) {
    byte[] secret = secrets.get(network);
    if (secret == null) {
      throw new DexterException(CommerceErrors.NETWORK_UNKNOWN, "No affiliate network " + network);
    }
    PostbackSignature.Check check = PostbackSignature.verify(secret, signature, body, clock.instant(),
        PostbackSignature.TOLERANCE);
    if (check != PostbackSignature.Check.OK) {
      throw new DexterException(CommerceErrors.POSTBACK_SIGNATURE_INVALID, "Signature " + check.name().toLowerCase());
    }
    Postback p;
    try {
      p = mapper.readValue(body, Postback.class);
    } catch (JacksonException e) {
      throw ValidationException.of("body", "POSTBACK_MALFORMED", "a JSON postback");
    }
    List<FieldError> errors = new ArrayList<>();
    if (p.ref() == null || !REF.matcher(p.ref()).matches()) {
      errors.add(new FieldError("ref", "INVALID", "1..100 of [A-Za-z0-9._:-]"));
    }
    ConversionStatus status = null;
    try {
      status = p.status() == null ? null : ConversionStatus.ofDb(p.status());
    } catch (IllegalArgumentException e) {
      status = null;
    }
    if (status == null) {
      errors.add(new FieldError("status", "INVALID", "pending, approved or rejected"));
    }
    long amount = p.amountPaise() == null ? -1 : p.amountPaise();
    long commission = p.commissionPaise() == null ? -1 : p.commissionPaise();
    if (amount < 0 || amount > 100_000_000_000L) {
      errors.add(new FieldError("amountPaise", "INVALID", "0..100000000000"));
    }
    if (commission < 0 || commission > 100_000_000_000L) {
      errors.add(new FieldError("commissionPaise", "INVALID", "0..100000000000"));
    }
    Instant occurred = null;
    try {
      occurred = p.occurredAt() == null ? null : Instant.parse(p.occurredAt());
    } catch (DateTimeParseException e) {
      occurred = null;
    }
    if (occurred == null) {
      errors.add(new FieldError("occurredAt", "INVALID", "an ISO-8601 instant"));
    }
    UUID clickId = null;
    if (p.subid() != null) {
      try {
        UUID id = UUID.fromString(p.subid());
        clickId = Ids.isV7(id) ? id : null;
      } catch (IllegalArgumentException e) {
        clickId = null;
      }
    }
    if (!errors.isEmpty()) {
      throw new ValidationException(errors);
    }
    return apply(network, p.ref(), clickId, status, amount, commission, occurred);
  }

  private PostbackResult apply(String network, String ref, @Nullable UUID subid, ConversionStatus status,
      long amount, long commission, Instant occurred) {
    Instant now = clock.instant();
    return tx.write(CommerceTenants.TRACKER, () -> {
      Optional<Click> click = subid == null ? Optional.empty() : tracking.click(subid, Ids.timestampOf(subid));
      UUID deal = click.map(Click::dealId).orElse(null);
      Conversion c = new Conversion(Ids.newId(), network, ref, click.map(Click::id).orElse(null), deal,
          click.map(Click::accountId).orElse(null), amount, commission, status, occurred, 0);
      if (tracking.insertConversion(c)) {
        if (deal != null) {
          tracking.addDealCounters(deal, ClickService.day(occurred), 0, 0, 1,
              status == ConversionStatus.APPROVED ? commission : 0);
        }
        return new PostbackResult("created", c.id(), status.db());
      }
      Conversion stored = tracking.lockConversion(network, ref).orElseThrow();
      if (!stored.status().mayMoveTo(status)) {
        return new PostbackResult("duplicate", stored.id(), stored.status().db());
      }
      tracking.updateConversion(stored.id(), status, amount, commission, now);
      if (status == ConversionStatus.APPROVED && stored.dealId() != null) {
        tracking.addDealCounters(stored.dealId(), ClickService.day(stored.occurredAt()), 0, 0, 0, commission);
      }
      return new PostbackResult("updated", stored.id(), status.db());
    });
  }
}
