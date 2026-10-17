package in.dexter.gateway.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** The rows of docs/api/routes.md's route tables. */
public final class RoutesDoc {
  public record Row(String pattern, String method, String path, String service, boolean publicAccess, String routeClass,
      boolean dev) {}

  private RoutesDoc() {}

  public static List<Row> rows() {
    List<String> lines;
    try {
      lines = Files.readAllLines(repoFile("docs/api/routes.md"), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    List<Row> out = new ArrayList<>();
    for (String l : lines) {
      if (!l.startsWith("| `/")) {
        continue;
      }
      String[] c = l.split("\\|");
      String pattern = between(c[1]);
      String[] example = between(c[2]).split(" ", 2);
      out.add(new Row(pattern, example[0], example[1], c[3].strip(), c[4].strip().equals("public"), c[5].strip(),
          c[1].contains("(dev)")));
    }
    return out;
  }

  public static java.nio.file.Path repoFile(String relative) {
    for (java.nio.file.Path dir = java.nio.file.Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
      java.nio.file.Path p = dir.resolve(relative);
      if (Files.exists(p)) {
        return p;
      }
    }
    throw new IllegalStateException(relative + " not found");
  }

  static String between(String cell) {
    int a = cell.indexOf('`');
    int b = cell.indexOf('`', a + 1);
    return cell.substring(a + 1, b);
  }
}
