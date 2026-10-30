package in.dexter.planning.app;

import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Saved what-if scenarios (planning_app pool, RLS per account). */
public interface ScenarioStore {

  /**
   * @param baseSnapshot the account as it was when the scenario was saved, as JSON text
   * @param adjustments a JSON array of adjustments, as text
   */
  record ScenarioRow(UUID id, UUID accountId, String name, String baseSnapshot, String adjustments,
      Instant createdAt, Instant updatedAt, int version) {}

  void insert(ScenarioRow s);

  Optional<ScenarioRow> find(UUID id);

  Optional<ScenarioRow> lock(UUID id);

  void update(ScenarioRow s);

  Page<ScenarioRow> list(PageRequest page);
}
