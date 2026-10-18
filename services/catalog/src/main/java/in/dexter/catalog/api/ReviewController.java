package in.dexter.catalog.api;

import in.dexter.catalog.app.ReviewService;
import in.dexter.core.error.CoreErrors;
import in.dexter.core.error.DexterException;
import in.dexter.db.page.PageRequest;
import in.dexter.security.AuthContext;
import in.dexter.security.web.RequiresPermission;
import in.dexter.web.page.PageResponse;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * /v1/admin/review (MASTER_PLAN P10): the review queue (keyset, oldest first, one status), an item, and
 * {@code POST /v1/admin/review/{id}:approve} (the promoter publishes; a repeat answers the same promotion) /
 * {@code :reject}.
 */
@RestController
@RequestMapping("/v1/admin/review")
public class ReviewController {
  /** {@code :reject} body (optional). */
  public record Decision(String reason) {}

  private final ReviewService reviews;

  public ReviewController(ReviewService reviews) {
    this.reviews = reviews;
  }

  @GetMapping
  @RequiresPermission("catalog:read")
  public PageResponse<ReviewService.ReviewView> queue(AuthContext caller, PageRequest page,
      @RequestParam(value = "status", required = false) String status) {
    return PageResponse.from(reviews.list(Callers.editor(caller), page, status));
  }

  @GetMapping("/{id}")
  @RequiresPermission("catalog:read")
  public ReviewService.ReviewView item(AuthContext caller, @PathVariable("id") String id) {
    return reviews.get(Callers.editor(caller), id(id));
  }

  @PostMapping("/{idAction}")
  @RequiresPermission("catalog:publish")
  public ResponseEntity<?> decide(AuthContext caller, @PathVariable("idAction") String idAction,
      @RequestBody(required = false) Decision body) {
    int colon = idAction.indexOf(':');
    String action = colon < 0 ? "" : idAction.substring(colon + 1);
    UUID id = id(colon < 0 ? idAction : idAction.substring(0, colon));
    return switch (action) {
      case "approve" -> ResponseEntity.ok(reviews.approve(Callers.editor(caller), id));
      case "reject" -> ResponseEntity.ok(reviews.reject(Callers.editor(caller), id, body == null ? null : body.reason()));
      default -> throw new DexterException(CoreErrors.NOT_FOUND, "Unknown review action");
    };
  }

  private static UUID id(String s) {
    try {
      return UUID.fromString(s);
    } catch (IllegalArgumentException e) {
      throw new DexterException(CoreErrors.INVALID_ID, "Not an id");
    }
  }
}
