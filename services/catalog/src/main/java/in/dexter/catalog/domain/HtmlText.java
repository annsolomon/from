package in.dexter.catalog.domain;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plain text out of an issuer's HTML page (no DOM library, no script execution): scripts, styles, comments and
 * templates are dropped, block elements become line breaks, tags are removed, common entities decoded, whitespace
 * collapsed. Good enough for the pattern extractor; not a renderer.
 */
public final class HtmlText {
  private static final Pattern DROP = Pattern.compile(
      "(?is)<!--.*?-->|<(script|style|noscript|template|svg)\\b[^>]*>.*?</\\1\\s*>");
  private static final Pattern BLOCK = Pattern.compile(
      "(?i)</?(p|div|br|li|ul|ol|tr|td|th|table|h[1-6]|section|article|header|footer|dt|dd|dl)\\b[^>]*>");
  private static final Pattern TAG = Pattern.compile("(?s)<[^>]*>");
  private static final Pattern TITLE = Pattern.compile("(?is)<title\\b[^>]*>(.*?)</title>");
  private static final Pattern H1 = Pattern.compile("(?is)<h1\\b[^>]*>(.*?)</h1>");
  private static final Pattern OG_TITLE = Pattern.compile(
      "(?is)<meta\\s+[^>]*property\\s*=\\s*[\"']og:title[\"'][^>]*content\\s*=\\s*[\"']([^\"']*)[\"']");
  private static final Pattern NUM_ENTITY = Pattern.compile("&#(x[0-9a-fA-F]+|[0-9]+);");

  private HtmlText() {}

  /** Visible text: one line per block element, single spaces inside a line. */
  public static String text(String html) {
    if (html == null) {
      return "";
    }
    String s = DROP.matcher(html).replaceAll(" ");
    s = BLOCK.matcher(s).replaceAll("\n");
    s = TAG.matcher(s).replaceAll(" ");
    s = entities(s);
    StringBuilder out = new StringBuilder();
    for (String line : s.split("\n")) {
      String l = line.replaceAll("[\\s\\u00a0]+", " ").strip();
      if (!l.isEmpty()) {
        out.append(l).append('\n');
      }
    }
    return out.toString();
  }

  /** The card's name as the page states it: og:title, else the first h1, else the title. */
  public static String title(String html) {
    if (html == null) {
      return null;
    }
    for (Pattern p : new Pattern[] {OG_TITLE, H1, TITLE}) {
      Matcher m = p.matcher(html);
      if (m.find()) {
        String t = entities(TAG.matcher(m.group(1)).replaceAll(" ")).replaceAll("[\\s\\u00a0]+", " ").strip();
        if (!t.isEmpty()) {
          return t.length() > 200 ? t.substring(0, 200) : t;
        }
      }
    }
    return null;
  }

  static String entities(String s) {
    String r = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&rupee;", "₹")
        .replace("&ndash;", "–").replace("&mdash;", "—").replace("&percnt;", "%");
    Matcher m = NUM_ENTITY.matcher(r);
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
      String g = m.group(1);
      int cp;
      try {
        cp = g.toLowerCase(Locale.ROOT).startsWith("x") ? Integer.parseInt(g.substring(1), 16) : Integer.parseInt(g);
      } catch (NumberFormatException e) {
        cp = ' ';
      }
      m.appendReplacement(sb, Matcher.quoteReplacement(Character.isValidCodePoint(cp) ? new String(Character.toChars(cp))
          : " "));
    }
    m.appendTail(sb);
    return sb.toString();
  }
}
