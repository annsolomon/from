package in.dexter.billing.domain;

import java.util.Set;

/**
 * Two-letter codes for the 28 states and 8 union territories, used as the GST place of supply. A mock list for the
 * mock tax split (ADR-0022 §6): a real tax provider works with GST state codes and its own rules for places of supply.
 */
public final class IndianStates {
  public static final Set<String> CODES = Set.of(
      "AN", "AP", "AR", "AS", "BR", "CH", "CG", "DH", "DL", "GA", "GJ", "HP", "HR", "JH", "JK", "KA", "KL", "LA",
      "LD", "MH", "ML", "MN", "MP", "MZ", "NL", "OD", "PB", "PY", "RJ", "SK", "TN", "TR", "TS", "UK", "UP", "WB");

  private IndianStates() {}

  public static boolean valid(String code) {
    return code != null && CODES.contains(code);
  }
}
