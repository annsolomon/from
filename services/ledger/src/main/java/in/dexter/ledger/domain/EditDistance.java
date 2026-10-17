package in.dexter.ledger.domain;

/**
 * Optimal string alignment distance (Levenshtein plus adjacent transposition): the resolver's last stage
 * (MASTER_PLAN P08) uses it on trigram candidates to accept one-letter typos and swaps ("zerohda" → "zerodha") that
 * fall under the trigram threshold on short names.
 */
public final class EditDistance {
  private EditDistance() {}

  public static int osa(String a, String b) {
    int n = a.length();
    int m = b.length();
    int[][] d = new int[n + 1][m + 1];
    for (int i = 0; i <= n; i++) {
      d[i][0] = i;
    }
    for (int j = 0; j <= m; j++) {
      d[0][j] = j;
    }
    for (int i = 1; i <= n; i++) {
      for (int j = 1; j <= m; j++) {
        int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
        int v = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
        if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
          v = Math.min(v, d[i - 2][j - 2] + 1);
        }
        d[i][j] = v;
      }
    }
    return d[n][m];
  }

  /** Edits a fuzzy match may need: 1 up to 5 characters, 2 above. */
  public static int allowed(String s) {
    return s.length() <= 5 ? 1 : 2;
  }
}
