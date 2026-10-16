package in.dexter.seed;

import java.time.Instant;
import java.util.List;

/**
 * The demo identities (MASTER_PLAN 11.1, BUILD_README §9). Passwords are never in code: they come from
 * {@code DEMO_PASSWORD_*} in {@code .env} (the defaults are the {@code .env.example} values, dev only).
 */
public record Persona(String key, String email, String displayName, String role, String tier, Instant createdAt,
    String passwordEnv, List<String> cardSlugs) {

  public static final Persona ASHA = new Persona("asha", "asha@demo.in", "Asha Iyer", "USER", "PREMIUM",
      Instant.parse("2025-04-12T04:45:00Z"), "DEMO_PASSWORD_ASHA",
      List.of("mock-saffron-millennia-cashback", "mock-indus-ace-cashback", "mock-lotus-feast"));
  public static final Persona RAVI = new Persona("ravi", "ravi@demo.in", "Ravi Kumar", "USER", "FREE",
      Instant.parse("2025-07-03T13:10:00Z"), "DEMO_PASSWORD_RAVI",
      List.of("mock-deccan-fuel-plus", "mock-kaveri-cashback-every-day"));
  public static final Persona ADMIN = new Persona("admin", "admin@demo.in", "Dexter Admin", "SUPER_ADMIN", null,
      Instant.parse("2025-01-15T04:30:00Z"), "DEMO_PASSWORD_ADMIN", List.of());

  /** Demo users, in account order. */
  public static final List<Persona> USERS = List.of(ASHA, RAVI);

  /** Shared password of every bulk account (one precomputed hash). */
  public static final String BULK_PASSWORD_ENV = "SEED_BULK_PASSWORD";
}
