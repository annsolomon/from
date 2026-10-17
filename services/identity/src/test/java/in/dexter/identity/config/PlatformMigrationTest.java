package in.dexter.identity.config;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.db.template.SqlTemplates;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** The platform migration is the rendered dexter-db templates, byte for byte (two header lines aside). */
class PlatformMigrationTest {
  @Test
  void platformMigrationMatchesTheTemplates() throws IOException {
    String migration;
    try (InputStream in = getClass().getResourceAsStream("/db/migration/V202610170900__platform.sql")) {
      assertThat(in).isNotNull();
      migration = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    String body = migration.substring(migration.indexOf('\n', migration.indexOf('\n') + 1) + 1);
    assertThat(body).as("regenerate V202610170900__platform.sql from SqlTemplates.renderAll(\"identity\")")
        .isEqualTo(SqlTemplates.renderAll("identity"));
  }
}
