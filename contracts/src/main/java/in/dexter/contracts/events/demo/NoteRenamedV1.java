package in.dexter.contracts.events.demo;

import java.util.UUID;

/** {@code demo.note.renamed} v1 ({@code contracts/events/demo.note.renamed.v1.json}). */
public record NoteRenamedV1(UUID noteId, String title) {
  public static final String TYPE = "demo.note.renamed";
  public static final int VERSION = 1;
}
