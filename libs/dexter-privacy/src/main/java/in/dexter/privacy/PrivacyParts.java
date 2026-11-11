package in.dexter.privacy;

/** Where a participant writes its export part (the object store in a running stack, memory in tests). */
@FunctionalInterface
public interface PrivacyParts {
  void put(String key, byte[] json);
}
