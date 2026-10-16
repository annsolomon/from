package in.dexter.testkit.archfixture.bad.domain;

import org.springframework.util.StringUtils;

/** ArchRules fixture: domain must not import Spring. */
public class SpringyDomain {
  public boolean blank(String s) {
    return !StringUtils.hasText(s);
  }
}
