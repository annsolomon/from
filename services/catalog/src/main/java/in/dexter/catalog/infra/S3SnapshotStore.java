package in.dexter.catalog.infra;

import in.dexter.catalog.app.SnapshotStore;
import in.dexter.objectstore.S3Objects;

/** Snapshot content in the object store (ADR-0003, libs/dexter-objectstore); keys are content-addressed. */
public final class S3SnapshotStore implements SnapshotStore {
  private final S3Objects objects;

  public S3SnapshotStore(S3Objects objects) {
    this.objects = objects;
  }

  @Override
  public void put(String key, byte[] body, String contentType) {
    objects.put(key, body, contentType);
  }
}
