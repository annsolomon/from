package in.dexter.reco.api;

import in.dexter.core.error.CoreErrors;
import in.dexter.core.error.DexterException;
import in.dexter.core.tenant.Tenant;
import in.dexter.reco.domain.RecoErrors;
import in.dexter.security.AuthContext;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The tenant of a user request: the token's account as {@code USER}; admin-console tokens are refused. */
final class Callers {
  /** The plan's {@code ?cardProductIds=} is for ranking a search result, so a page's worth is plenty. */
  static final int MAX_IDS = 50;

  private Callers() {}

  static Tenant user(AuthContext caller) {
    if (caller.scopes().contains("admin")) {
      throw new DexterException(RecoErrors.USER_TOKEN_REQUIRED, "Admin console tokens cannot act as a user");
    }
    return caller.tenant();
  }

  static UUID id(String s) {
    try {
      return UUID.fromString(s);
    } catch (IllegalArgumentException e) {
      throw new DexterException(CoreErrors.INVALID_ID, "Not an id");
    }
  }

  /** A comma-separated {@code cardProductIds} query parameter; empty when absent. */
  static List<UUID> ids(String csv) {
    if (csv == null || csv.isBlank()) {
      return List.of();
    }
    String[] parts = csv.split(",", -1);
    if (parts.length > MAX_IDS) {
      throw new DexterException(CoreErrors.VALIDATION_FAILED, "At most " + MAX_IDS + " cardProductIds");
    }
    List<UUID> out = new ArrayList<>(parts.length);
    for (String p : parts) {
      String t = p.strip();
      if (!t.isEmpty()) {
        out.add(id(t));
      }
    }
    return List.copyOf(out);
  }
}
