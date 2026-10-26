package in.dexter.portfolio.domain;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The upload scan (MASTER_PLAN 7.3 uploads: content sniffing + size cap; the antivirus hook comes later): a statement
 * must be a non-empty PDF ({@code %PDF-} magic) within the size cap. Pure: the caller reads the object (at most
 * {@code maxBytes + 1} bytes) and passes the bytes.
 */
public final class StatementScan {
  private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-'};

  /** Why a file is refused (the upload's error code). */
  public enum Rejection { EMPTY_FILE, FILE_TOO_LARGE, NOT_A_PDF }

  /** Passed: the SHA-256 (32 bytes) and the size. */
  public record Clean(byte[] sha256, long sizeBytes) {
    public String sha256Hex() {
      return HexFormat.of().formatHex(sha256);
    }
  }

  /** Either {@code clean} or {@code rejection} is set. */
  public record Result(Clean clean, Rejection rejection) {
    public boolean ok() {
      return clean != null;
    }
  }

  private StatementScan() {}

  public static Result scan(byte[] data, long maxBytes) {
    if (data == null || data.length == 0) {
      return new Result(null, Rejection.EMPTY_FILE);
    }
    if (data.length > maxBytes) {
      return new Result(null, Rejection.FILE_TOO_LARGE);
    }
    if (data.length < PDF_MAGIC.length) {
      return new Result(null, Rejection.NOT_A_PDF);
    }
    for (int i = 0; i < PDF_MAGIC.length; i++) {
      if (data[i] != PDF_MAGIC[i]) {
        return new Result(null, Rejection.NOT_A_PDF);
      }
    }
    return new Result(new Clean(sha256(data), data.length), null);
  }

  public static byte[] sha256(byte[] data) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(data);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
