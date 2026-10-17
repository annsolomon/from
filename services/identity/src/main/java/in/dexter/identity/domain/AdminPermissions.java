package in.dexter.identity.domain;

/**
 * Permission codes (identity.permissions, MASTER_PLAN 7.2). The role → permission matrix is data
 * (identity.role_permissions); these constants name what identity's own endpoints require.
 */
public final class AdminPermissions {
  public static final String ACCOUNTS_READ = "accounts:read";
  public static final String ADMINS_READ = "admins:read";
  public static final String ADMINS_WRITE = "admins:write";

  /** Roles that may read accounts (accounts_admin_read policy), least privileged first: the use-case acts as the first held. */
  public static final java.util.List<String> ACCOUNT_READER_ROLES = java.util.List.of("SUPPORT", "FINANCE", "SUPER_ADMIN");

  private AdminPermissions() {}
}
