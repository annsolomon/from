package in.dexter.identity.app;

import in.dexter.core.error.FieldError;
import in.dexter.core.error.NotFoundException;
import in.dexter.core.error.ValidationException;
import in.dexter.db.tenant.TenantTx;
import in.dexter.identity.app.AccountStore.Account;
import in.dexter.identity.domain.Profiles;
import in.dexter.security.AuthContext;
import java.util.ArrayList;
import java.util.List;

/** GET/PATCH /v1/me. The phone number is stored encrypted (per-account DEK) plus a keyed hash for uniqueness. */
public final class ProfileService {
  static final String PHONE = "phone";

  private final TenantTx tx;
  private final AccountStore accounts;
  private final FieldCipher cipher;

  public ProfileService(TenantTx tx, AccountStore accounts, FieldCipher cipher) {
    this.tx = tx;
    this.accounts = accounts;
    this.cipher = cipher;
  }

  public MeView me(AuthContext caller) {
    return tx.read(caller.tenant(), () -> view(load(caller), caller.mfaEnrolled()));
  }

  public MeView patch(AuthContext caller, long expectedVersion, ProfilePatch p) {
    List<FieldError> errors = new ArrayList<>();
    String displayName = null;
    if (p.displayName().present() && p.displayName().value() != null) {
      displayName = p.displayName().value().strip();
      FieldError e = Profiles.checkDisplayName("displayName", displayName);
      if (e != null) {
        errors.add(e);
      }
    }
    if (p.tz().present()) {
      FieldError e = Profiles.checkTz("tz", p.tz().value());
      if (e != null) {
        errors.add(e);
      }
    }
    String phone = null;
    if (p.phone().present() && p.phone().value() != null) {
      phone = Profiles.normalizePhone(p.phone().value());
      if (phone == null) {
        errors.add(new FieldError("phone", "INVALID_PHONE", "Use an international number such as +919876543210"));
      }
    }
    if (!errors.isEmpty()) {
      throw new ValidationException(errors);
    }
    String newName = displayName;
    String newPhone = phone;
    return tx.write(caller.tenant(), () -> {
      Account cur = load(caller);
      String name = p.displayName().present() ? newName : cur.displayName();
      String tz = p.tz().present() ? p.tz().value() : cur.tz();
      byte[] phoneEnc = cur.phoneEnc();
      byte[] phoneHash = null;
      if (p.phone().present()) {
        phoneEnc = newPhone == null ? null : cipher.encrypt(cur.id(), PHONE, newPhone);
        phoneHash = newPhone == null ? null : cipher.blindIndex(PHONE, newPhone);
      } else if (phoneEnc != null) {
        phoneHash = cipher.blindIndex(PHONE, cipher.decrypt(cur.id(), PHONE, phoneEnc));
      }
      accounts.updateProfile(cur.id(), expectedVersion, name, tz, phoneEnc, phoneHash);
      return view(load(caller), caller.mfaEnrolled());
    });
  }

  private Account load(AuthContext caller) {
    return accounts.find(caller.accountId()).orElseThrow(() -> new NotFoundException("account"));
  }

  private MeView view(Account a, boolean mfa) {
    String phone = a.phoneEnc() == null ? null : cipher.decrypt(a.id(), PHONE, a.phoneEnc());
    return new MeView(a.id(), a.email(), a.displayName(), phone, a.tz(), a.tier(), a.status(),
        a.emailVerifiedAt() != null, mfa, a.createdAt(), a.version());
  }
}
