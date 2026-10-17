package in.dexter.identity.app;

/**
 * JSON Merge Patch (RFC 7386) of the editable profile: each field is absent (unchanged), null (cleared, where
 * allowed) or a value. Built by the controller from the request document.
 */
public record ProfilePatch(Field displayName, Field tz, Field phone) {
  /** One member of the patch. */
  public record Field(boolean present, String value) {
    public static final Field ABSENT = new Field(false, null);

    public static Field of(String value) {
      return new Field(true, value);
    }
  }
}
