package in.dexter.testkit.demo;

import in.dexter.contracts.events.demo.NoteCreatedV1;
import in.dexter.contracts.events.demo.NoteRenamedV1;
import in.dexter.events.EventType;

/** The demo's event types (schemas in contracts/events/demo.note.*.v1.json). */
public final class DemoEvents {
  public static final EventType<NoteCreatedV1> NOTE_CREATED =
      EventType.of("demo", "note", "created", NoteCreatedV1.VERSION, NoteCreatedV1.class);
  public static final EventType<NoteRenamedV1> NOTE_RENAMED =
      EventType.of("demo", "note", "renamed", NoteRenamedV1.VERSION, NoteRenamedV1.class);
  public static final String TOPIC = NOTE_CREATED.topic();

  private DemoEvents() {}
}
