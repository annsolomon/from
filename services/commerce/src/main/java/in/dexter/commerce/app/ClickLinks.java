package in.dexter.commerce.app;

import in.dexter.commerce.domain.ClickToken;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Mints the {@code /r/{token}} paths listings hand out (MASTER_PLAN P17: signed, expiring). */
public final class ClickLinks {
  public static final String PREFIX = "/r/";

  private final ClickToken tokens;
  private final Duration ttl;
  private final Clock clock;

  public ClickLinks(ClickToken tokens, Duration ttl, Clock clock) {
    this.tokens = tokens;
    this.ttl = ttl;
    this.clock = clock;
  }

  public String deal(UUID dealId, @Nullable UUID viewer) {
    return PREFIX + tokens.mint(new ClickToken.Claims(ClickToken.Kind.DEAL, dealId, viewer, clock.instant().plus(ttl)));
  }

  public String ad(UUID creativeId, @Nullable UUID viewer) {
    return PREFIX + tokens.mint(new ClickToken.Claims(ClickToken.Kind.AD, creativeId, viewer,
        clock.instant().plus(ttl)));
  }

  public ClickToken tokens() {
    return tokens;
  }
}
