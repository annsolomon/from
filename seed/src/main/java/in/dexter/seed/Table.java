package in.dexter.seed;

import java.util.ArrayList;
import java.util.List;

/**
 * One seeded table: the service database it belongs to, the table name inside the service schema, the phase whose
 * Flyway migration creates the real table, and its columns in COPY order with the planned SQL types.
 *
 * <p>The column list is the contract with that phase's migration (ADR-0008): the loader COPYs into the real table
 * when it exists and fails loudly if a column is missing there; until then it COPYs into {@code seed_stage.<name>},
 * created from {@link #stagingDdl()}.
 */
public record Table(String service, String name, String phase, List<Column> columns) {

  public record Column(String name, String sqlType) {}

  public Table {
    columns = List.copyOf(columns);
  }

  public List<String> columnNames() {
    List<String> out = new ArrayList<>(columns.size());
    for (Column c : columns) {
      out.add(c.name());
    }
    return out;
  }

  /** {@code CREATE TABLE seed_stage.<name> (...)}: same columns and types, no constraints (raw staging only). */
  public String stagingDdl() {
    StringBuilder sb = new StringBuilder("CREATE TABLE IF NOT EXISTS seed_stage.").append(name).append(" (");
    for (int i = 0; i < columns.size(); i++) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append(columns.get(i).name()).append(' ').append(columns.get(i).sqlType());
    }
    return sb.append(')').toString();
  }

  public String file() {
    return service + "/" + name + ".tsv";
  }

  static Builder of(String service, String name, String phase) {
    return new Builder(service, name, phase);
  }

  static final class Builder {
    private final String service;
    private final String name;
    private final String phase;
    private final List<Column> cols = new ArrayList<>();

    private Builder(String service, String name, String phase) {
      this.service = service;
      this.name = name;
      this.phase = phase;
    }

    Builder col(String name, String sqlType) {
      cols.add(new Column(name, sqlType));
      return this;
    }

    Table build() {
      return new Table(service, name, phase, cols);
    }
  }
}
