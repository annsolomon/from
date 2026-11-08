package in.dexter.commerce.app;

import in.dexter.commerce.domain.ConversionStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Raw clicks and impressions, conversions, and the maintained counters (MASTER_PLAN 8.3). Infra implements it. */
public interface TrackingStore {
  record Click(UUID id, Instant at, @Nullable UUID accountId, UUID dealId, byte[] tokenHash, byte[] ipHash,
      byte[] uaHash) {}

  record Conversion(UUID id, String network, String providerRef, @Nullable UUID clickId, @Nullable UUID dealId,
      @Nullable UUID accountId, long amountPaise, long commissionPaise, ConversionStatus status, Instant occurredAt,
      int version) {}

  record DealStat(UUID dealId, String title, long clicks, long dailyUniques, long conversions,
      long commissionPaise) {}

  void insertClick(Click click);

  /** The click with this UUIDv7 id (its timestamp is {@code at}; the lookup prunes to its partition). */
  Optional<Click> click(UUID id, Instant at);

  void insertImpression(UUID id, Instant at, UUID creativeId, UUID campaignId, String placement,
      @Nullable UUID accountId, byte[] visitorHash);

  /** Inserts unless (network, provider_ref) exists; true when this call inserted it. */
  boolean insertConversion(Conversion c);

  /** The stored conversion, locked for the update that may follow. */
  Optional<Conversion> lockConversion(String network, String providerRef);

  void updateConversion(UUID id, ConversionStatus status, long amountPaise, long commissionPaise, Instant at);

  /** Additive upsert of one (deal, IST day) counter row. */
  void addDealCounters(UUID dealId, LocalDate day, long clicks, long uniques, long conversions,
      long commissionPaise);

  /** Additive upsert of one (creative, IST day) counter row. */
  void addAdCounters(UUID creativeId, LocalDate day, long impressions, long clicks);

  /** Operators: per deal over [from, to] (IST days), from the maintained counters. */
  List<DealStat> dealStats(LocalDate from, LocalDate to);
}
