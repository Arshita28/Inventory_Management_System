package com.inventory.security.auth;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Versioned PBKDF2 hashes using the JDK cryptography API; no plaintext persistence. */
public final class PasswordHasher {
    private static final int ITERATIONS = 600_000;
    private final SecureRandom random = new SecureRandom();

    public String hash(char[] password) {
        if (password.length < 15 || password.length > 128) throw new IllegalArgumentException("Use a password between 15 and 128 characters.");
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        return "pbkdf2-sha256$" + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(derive(password, salt, ITERATIONS));
    }

    public boolean matches(char[] password, String encoded) {
        if (password.length > 128) return false;
        String[] parts = encoded.split("\\$");
        if (parts.length != 4 || !parts[0].equals("pbkdf2-sha256")) return false;
        int iterations = Integer.parseInt(parts[1]);
        if (iterations < ITERATIONS || iterations > 2_000_000) return false;
        return MessageDigest.isEqual(Base64.getDecoder().decode(parts[3]), derive(password, Base64.getDecoder().decode(parts[2]), iterations));
    }

    private byte[] derive(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("Password hashing is unavailable", exception);
        } finally {
            spec.clearPassword();
        }
    }
}
