package in.dexter.catalog.support;

import in.dexter.catalog.app.SnapshotStore;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Snapshot content in memory (the ITs have no object store; api-smoke covers SeaweedFS). */
public final class MemorySnapshotStore implements SnapshotStore {
  public final Map<String, byte[]> objects = new ConcurrentHashMap<>();

  @Override
  public void put(String key, byte[] body, String contentType) {
    objects.put(key, body.clone());
  }
}
