package in.dexter.commerce.app;

import in.dexter.core.tenant.Tenant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Who is looking at a public commerce page (the feed, a deal, an ad slot). These routes are public, so the token is
 * optional: when one is sent it must be a valid user token, and {@code accountId} comes only from it (config's
 * {@code ViewerResolver}). {@code ipHash} / {@code uaHash} are salted SHA-256 of the client address and user agent,
 * used for anonymous uniques and frequency caps; the raw values are never stored.
 */
public record Viewer(@Nullable UUID accountId, byte[] ipHash, byte[] uaHash) {
  public boolean signedIn() {
    return accountId != null;
  }

  public Tenant tenant() {
    return accountId == null ? CommerceTenants.PUBLIC : Tenant.user(accountId);
  }
}
