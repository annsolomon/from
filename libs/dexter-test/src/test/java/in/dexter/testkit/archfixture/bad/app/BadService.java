package in.dexter.testkit.archfixture.bad.app;

import in.dexter.testkit.archfixture.bad.api.BadController;

/** ArchRules fixture: app must not call api. */
public class BadService {
  public String leak(BadController controller) {
    return controller.combined();
  }
}
