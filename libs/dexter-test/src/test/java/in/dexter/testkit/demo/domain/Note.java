package in.dexter.testkit.demo.domain;

import java.time.Instant;
import java.util.UUID;

/** A demo note. Money is long paise (MASTER_PLAN 3.4). */
public record Note(UUID accountId, UUID id, String title, String kind, long amountPaise, Instant createdAt,
    long version) {}
