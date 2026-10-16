package in.dexter.testkit.demo.app;

/** Create command; also the request body (hashed canonically for Idempotency-Key). */
public record CreateNote(String title, String kind, long amountPaise) {}
