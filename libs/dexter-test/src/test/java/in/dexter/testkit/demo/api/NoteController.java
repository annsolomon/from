package in.dexter.testkit.demo.api;

import in.dexter.core.tenant.Tenant;
import in.dexter.db.page.PageRequest;
import in.dexter.idempotency.web.IdempotencyKey;
import in.dexter.idempotency.web.IdempotentResponses;
import in.dexter.testkit.demo.app.CreateNote;
import in.dexter.testkit.demo.app.NoteQueries;
import in.dexter.testkit.demo.app.NoteService;
import in.dexter.testkit.demo.app.NoteView;
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
 * Controller (api): never touches JdbcClient. The caller is the {@link Tenant} resolved from the
 * verified access token (dexter-security, MASTER_PLAN 7.2); an account id never comes from a header,
 * path or body. No annotation needed: every handler requires a valid user token by default. Reads go through
 * {@link NoteQueries} (cache-aside); the controller itself never touches the cache.
 */
@RestController
@RequestMapping(NoteService.PATH)
public class NoteController {
  private final NoteService service;
  private final NoteQueries queries;

  public NoteController(NoteService service, NoteQueries queries) {
    this.service = service;
    this.queries = queries;
  }

  @PostMapping
  public ResponseEntity<String> create(Tenant tenant, @IdempotencyKey(required = true) UUID key,
      @RequestBody CreateNote body) {
    return IdempotentResponses.toEntity(service.create(tenant, key, body));
  }

  @GetMapping
  public PageResponse<NoteView> list(Tenant tenant, PageRequest page,
      @RequestParam(name = "kind", required = false) String kind) {
    return PageResponse.from(queries.list(tenant, page, kind));
  }

  @GetMapping("/{id}")
  public NoteView get(Tenant tenant, @PathVariable("id") UUID id) {
    return queries.get(tenant, id);
  }
}
