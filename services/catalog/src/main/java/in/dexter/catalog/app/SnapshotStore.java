package in.dexter.catalog.app;

/** Where fetched content lives (the object store, ADR-0003); rows keep the key. Keys are content-addressed. */
public interface SnapshotStore {
  void put(String key, byte[] body, String contentType);
}
