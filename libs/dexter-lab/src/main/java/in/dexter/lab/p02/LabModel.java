package in.dexter.lab.p02;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The lab's records (request bodies, views, cached pages, event payloads). */
public final class LabModel {
  private LabModel() {}

  public record Note(UUID accountId, UUID id, String title, long amountPaise, Instant createdAt, long version) {
    public View view() {
      return new View(id, title, amountPaise, createdAt.toString(), version);
    }
  }

  public record View(UUID id, String title, long amountPaise, String createdAt, long version) {}

  /** Cached shape of one list page. */
  public record NotePage(List<View> items, int limit, boolean hasMore, String nextCursor) {}

  public record CreateNote(String title, long amountPaise) {}

  public record RenameNote(String title, long version) {}

  public record TokenRequest(String persona, String defect) {}

  /** Event payload {@code lab.note.created} v1 (dev only; no contract schema). */
  public record NoteCreatedV1(UUID noteId, String title, long amountPaise, String createdAt) {}

  /** Event payload {@code lab.note.renamed} v1. */
  public record NoteRenamedV1(UUID noteId, String title) {}
}
