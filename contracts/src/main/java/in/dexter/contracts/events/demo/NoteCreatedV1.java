package in.dexter.contracts.events.demo;

import java.util.UUID;

/**
 * {@code demo.note.created} v1 ({@code contracts/events/demo.note.created.v1.json}). {@code createdAt}
 * is ISO-8601 UTC text so the wire form never depends on mapper date settings.
 */
public record NoteCreatedV1(UUID noteId, String title, long amountPaise, String createdAt) {
  public static final String TYPE = "demo.note.created";
  public static final int VERSION = 1;
}
