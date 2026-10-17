package in.dexter.identity.app;

import in.dexter.identity.app.Mailer.Mail;

/**
 * The security emails. Plain text, no tracking, links built from configured templates. The raw token exists only
 * in the link; the database stores its SHA-256.
 */
final class IdentityMails {
  private IdentityMails() {}

  static Mail verify(String to, String link, long hours) {
    return new Mail(to, "Verify your Dexter email address",
        "Welcome to Dexter.\n\nConfirm this email address to activate your account:\n\n" + link
            + "\n\nThe link works once and expires in " + hours + " hours.\n"
            + "If you did not sign up for Dexter, ignore this email.\n");
  }

  static Mail alreadyRegistered(String to, String signInLink) {
    return new Mail(to, "You already have a Dexter account",
        "Someone (hopefully you) tried to create a Dexter account with this email address, but it already has one.\n\n"
            + "Sign in: " + signInLink + "\nForgot your password? Use \"Forgot password\" on the sign-in page.\n\n"
            + "If this was not you, you can ignore this email; nothing was changed.\n");
  }

  static Mail reset(String to, String link, long minutes) {
    return new Mail(to, "Reset your Dexter password",
        "We received a request to reset your Dexter password.\n\nChoose a new password here:\n\n" + link
            + "\n\nThe link works once and expires in " + minutes + " minutes. Resetting signs you out everywhere.\n"
            + "If you did not ask for this, ignore this email; your password stays the same.\n");
  }

  static Mail passwordChanged(String to) {
    return new Mail(to, "Your Dexter password was changed",
        "The password of your Dexter account was just changed and your other sessions were signed out.\n\n"
            + "If this was not you, reset your password now with \"Forgot password\".\n");
  }

  static Mail adminInvite(String to, String link, long hours) {
    return new Mail(to, "You have been invited to the Dexter admin console",
        "An administrator invited you to the Dexter admin console.\n\nSet your password here:\n\n" + link
            + "\n\nThe link works once and expires in " + hours + " hours. At your first sign-in you will set up an "
            + "authenticator app; admin access always needs it.\nIf you did not expect this, ignore this email.\n");
  }

  static String link(String template, String token) {
    return template.replace("{token}", token);
  }
}
