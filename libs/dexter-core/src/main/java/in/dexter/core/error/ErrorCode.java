package in.dexter.core.error;

/**
 * A stable, machine-readable error code (MASTER_PLAN 9). {@link #code()} is UPPER_SNAKE and never
 * changes once published; {@link #status()} is the HTTP status it maps to.
 */
public interface ErrorCode {
  String code();

  int status();

  String title();
}
