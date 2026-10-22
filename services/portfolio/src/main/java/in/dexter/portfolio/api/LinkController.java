package in.dexter.portfolio.api;

import in.dexter.core.error.CoreErrors;
import in.dexter.core.error.DexterException;
import in.dexter.db.page.PageRequest;
import in.dexter.portfolio.app.LinkService;
import in.dexter.security.AuthContext;
import in.dexter.web.page.PageResponse;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * /v1/links (MASTER_PLAN P11): auto-match suggestions between ledger expenses and card transactions (keyset, newest
 * first) and {@code POST /v1/links/{id}:confirm} / {@code :reject}.
 */
@RestController
@RequestMapping("/v1/links")
public class LinkController {
  private final LinkService links;

  public LinkController(LinkService links) {
    this.links = links;
  }

  @GetMapping("/suggestions")
  public PageResponse<LinkService.SuggestionView> suggestions(AuthContext caller, PageRequest page) {
    return PageResponse.from(links.suggestions(Callers.user(caller), page));
  }

  @PostMapping("/{idAction}")
  public LinkService.LinkView decide(AuthContext caller, @PathVariable("idAction") String idAction) {
    int colon = idAction.indexOf(':');
    String action = colon < 0 ? "" : idAction.substring(colon + 1);
    UUID id = Callers.id(colon < 0 ? idAction : idAction.substring(0, colon));
    return switch (action) {
      case "confirm" -> links.confirm(Callers.user(caller), id);
      case "reject" -> links.reject(Callers.user(caller), id);
      default -> throw new DexterException(CoreErrors.NOT_FOUND, "Unknown link action");
    };
  }
}
