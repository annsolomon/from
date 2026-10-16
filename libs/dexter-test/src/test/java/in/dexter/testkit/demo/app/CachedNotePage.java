package in.dexter.testkit.demo.app;

import in.dexter.db.page.Page;
import java.util.List;

/** The cached shape of one list page (a record, so the cache can deserialize it by class). */
public record CachedNotePage(List<NoteView> items, int limit, boolean hasMore, String nextCursor) {
  static CachedNotePage of(Page<NoteView> p) {
    return new CachedNotePage(p.items(), p.limit(), p.hasMore(), p.nextCursor());
  }

  Page<NoteView> toPage() {
    return new Page<>(items, limit, hasMore, nextCursor);
  }
}
