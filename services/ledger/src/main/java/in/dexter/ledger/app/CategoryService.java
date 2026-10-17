package in.dexter.ledger.app;

import in.dexter.core.error.DexterException;
import in.dexter.core.error.FieldError;
import in.dexter.core.error.ValidationException;
import in.dexter.core.id.Ids;
import in.dexter.core.tenant.Tenant;
import in.dexter.db.tenant.TenantTx;
import in.dexter.idempotency.ApiResponse;
import in.dexter.idempotency.IdempotencyEngine;
import in.dexter.idempotency.IdempotentRequest;
import in.dexter.idempotency.IdempotentResponse;
import in.dexter.ledger.app.CategoryStore.Category;
import in.dexter.ledger.domain.CategoryPaths;
import in.dexter.ledger.domain.LedgerErrors;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

/**
 * Categories (MASTER_PLAN P07): the shared system tree plus custom subtrees a user adds under a system root
 * ({@code food.street_food}). Paths are ltree; a custom path is {@code <visible parent>.<slug(name)>}, at most 4
 * levels, and may never shadow a system path.
 */
public final class CategoryService {
  public static final String PATH = "/v1/categories";

  /** POST /v1/categories body. */
  public record NewCategory(String parentPath, String name, String icon) {}

  public record CategoryView(UUID id, String path, String name, String icon, boolean system) {
    static CategoryView of(Category c) {
      return new CategoryView(c.id(), c.path(), c.name(), c.icon(), c.system());
    }
  }

  private final TenantTx tx;
  private final IdempotencyEngine idempotency;
  private final CategoryStore store;
  private final ObjectMapper mapper;

  public CategoryService(TenantTx tx, IdempotencyEngine idempotency, CategoryStore store, ObjectMapper mapper) {
    this.tx = tx;
    this.idempotency = idempotency;
    this.store = store;
    this.mapper = mapper;
  }

  public List<CategoryView> list(Tenant tenant) {
    return tx.read(tenant, () -> store.listVisible().stream().map(CategoryView::of).toList());
  }

  /** Idempotency-Key optional. */
  public IdempotentResponse create(Tenant tenant, UUID key, NewCategory cmd) {
    List<FieldError> errors = new ArrayList<>();
    String parent = cmd.parentPath();
    if (!CategoryPaths.isValid(parent)) {
      errors.add(new FieldError("parentPath", parent == null ? "REQUIRED" : "INVALID", "A visible category path"));
    }
    String name = cmd.name() == null ? null : cmd.name().strip();
    String slug = CategoryPaths.slug(name);
    if (name == null || name.isEmpty() || name.length() > 60 || slug == null) {
      errors.add(new FieldError("name", "INVALID", "1-60 characters with at least one letter or digit"));
    }
    String icon = cmd.icon() == null || cmd.icon().isBlank() ? null : cmd.icon().strip();
    if (icon != null && (icon.length() > 40 || !icon.matches("[a-z0-9-]+"))) {
      errors.add(new FieldError("icon", "INVALID", "An icon name ([a-z0-9-], at most 40)"));
    }
    if (errors.isEmpty() && CategoryPaths.levels(parent) >= CategoryPaths.MAX_LEVELS) {
      errors.add(new FieldError("parentPath", "TOO_DEEP", "Categories are at most " + CategoryPaths.MAX_LEVELS
          + " levels"));
    }
    if (!errors.isEmpty()) {
      throw new ValidationException(errors);
    }
    String path = parent + "." + slug;
    IdempotentRequest fingerprint = IdempotentRequest.of("POST", PATH, cmd, mapper);
    return tx.write(tenant, () -> idempotency.execute(tenant, key, fingerprint, () -> {
      Category p = store.findVisible(parent).orElseThrow(() -> new ValidationException(List.of(
          new FieldError("parentPath", "UNKNOWN_CATEGORY", "No such category (GET /v1/categories)"))));
      Category root = store.findVisible(CategoryPaths.root(parent)).orElse(null);
      if (root == null || !root.system()) {
        throw new ValidationException(List.of(new FieldError("parentPath", "NOT_UNDER_SYSTEM_ROOT",
            "Custom categories live under a system root")));
      }
      if (store.findVisible(path).isPresent()) {
        throw new DexterException(LedgerErrors.CATEGORY_EXISTS,
            "Category " + path + " already exists");
      }
      UUID id = Ids.newId();
      store.insert(id, tenant.accountId(), path, name, icon != null ? icon : p.icon());
      return ApiResponse.created(PATH + "/" + id,
          new CategoryView(id, path, name, icon != null ? icon : p.icon(), false));
    }));
  }
}
