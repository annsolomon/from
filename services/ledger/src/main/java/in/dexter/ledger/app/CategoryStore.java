package in.dexter.ledger.app;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** {@code ledger.categories} visible to the tenant: the system tree plus its own custom categories. */
public interface CategoryStore {
  record Category(UUID id, String path, String name, String icon, boolean system) {}

  List<Category> listVisible();

  Optional<Category> findVisible(String path);

  /** Inserts a custom category; an existing path throws {@code CATEGORY_EXISTS}. */
  void insert(UUID id, UUID accountId, String path, String name, String icon);
}
