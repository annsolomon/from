package in.dexter.testkit.demo.app;

import in.dexter.testkit.demo.domain.Note;
import java.util.UUID;

/** Response record. */
public record NoteView(UUID id, String title, String kind, long amountPaise, String createdAt, long version) {
  public static NoteView of(Note n) {
    return new NoteView(n.id(), n.title(), n.kind(), n.amountPaise(), n.createdAt().toString(), n.version());
  }
}
