package in.dexter.identity.app;

/** Unwraps a stored key with the KEK it names and wraps it with the active KEK (same associated data). */
public interface KekRewrapper {
  String activeId();

  byte[] rewrap(KekRewrapStore.Wrapped row);
}
