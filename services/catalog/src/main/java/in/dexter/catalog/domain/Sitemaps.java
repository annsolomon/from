package in.dexter.catalog.domain;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Page URLs out of a sitemap ({@code <urlset><url><loc>}): read with a pattern, not an XML parser, so no entity or
 * DTD is ever resolved (XXE-safe). Only absolute URLs on the sitemap's own host and scheme are kept (each page is
 * fetched through the same policy anyway), duplicates dropped, at most {@code max}. Pure.
 */
public final class Sitemaps {
  private static final Pattern LOC = Pattern.compile("(?is)<loc>\\s*(.*?)\\s*</loc>");

  private Sitemaps() {}

  public static List<String> pageUrls(String xml, URI sitemap, int max) {
    Set<String> out = new LinkedHashSet<>();
    if (xml == null) {
      return List.of();
    }
    Matcher m = LOC.matcher(xml);
    while (m.find() && out.size() < max) {
      String raw = HtmlText.entities(m.group(1).replace("<![CDATA[", "").replace("]]>", "")).strip();
      try {
        URI u = new URI(raw);
        if (u.isAbsolute() && u.getHost() != null && u.getScheme().equalsIgnoreCase(sitemap.getScheme())
            && FetchPolicy.hostKey(u).equals(FetchPolicy.hostKey(sitemap)) && u.getRawUserInfo() == null) {
          out.add(u.toString());
        }
      } catch (Exception e) {
        // not a URL: skipped
      }
    }
    return new ArrayList<>(out);
  }
}
