package in.dexter.identity.app;

import in.dexter.core.error.DexterException;
import in.dexter.identity.domain.IdentityErrors;

/** Public (app) client ids accepted at registration and login. */
final class Clients {
  private Clients() {}

  static String require(IdentitySettings settings, String clientId) {
    String id = clientId == null || clientId.isBlank() ? settings.defaultClient() : clientId;
    if (!settings.publicClients().contains(id)) {
      throw new DexterException(IdentityErrors.UNKNOWN_CLIENT, "Unknown client id");
    }
    return id;
  }
}
