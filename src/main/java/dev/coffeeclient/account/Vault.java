package dev.coffeeclient.account;

import dev.coffeeclient.core.CoffeeClient;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts account tokens and proxy passwords at rest (AES-256-GCM). The key lives in the user's home
 * folder ({@code ~/.coffeeclient/vault.key}), outside the game folder, so sharing or uploading the
 * instance's config does not hand out logins (after alt manager). Anyone with access to your
 * user account can still read both files.
 */
public final class Vault {
	private static final String PREFIX = "v1:";
	private static final int IV_BYTES = 12, TAG_BITS = 128;
	private static final SecureRandom RANDOM = new SecureRandom();
	private static byte[] key;
	private static Path file = Path.of(System.getProperty("user.home"), ".coffeeclient", "vault.key");

	private Vault() {
	}

	public static Path keyFile() {
		return file;
	}

	/** Tests: keep the key in another file and forget the cached key. */
	static synchronized void useKeyFile(Path path) {
		file = path;
		key = null;
	}

	/** {@code plain} encrypted as text for a JSON file; empty stays empty. */
	public static String seal(String plain) {
		if (plain == null || plain.isEmpty()) return "";
		try {
			byte[] iv = new byte[IV_BYTES];
			RANDOM.nextBytes(iv);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(TAG_BITS, iv));
			byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
			byte[] out = new byte[iv.length + sealed.length];
			System.arraycopy(iv, 0, out, 0, iv.length);
			System.arraycopy(sealed, 0, out, iv.length, sealed.length);
			return PREFIX + Base64.getEncoder().encodeToString(out);
		} catch (GeneralSecurityException | IOException e) {
			throw new IllegalStateException("Could not encrypt", e);
		}
	}

	/** The plain text of {@link #seal}'s output, or null when it cannot be read (other key, damaged). */
	public static String open(String sealed) {
		if (sealed == null || sealed.isEmpty()) return "";
		if (!sealed.startsWith(PREFIX)) return null;
		try {
			byte[] data = Base64.getDecoder().decode(sealed.substring(PREFIX.length()));
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(TAG_BITS, data, 0, IV_BYTES));
			return new String(cipher.doFinal(data, IV_BYTES, data.length - IV_BYTES), StandardCharsets.UTF_8);
		} catch (GeneralSecurityException | IOException | IllegalArgumentException e) {
			CoffeeClient.LOG.warn("[Vault] Could not decrypt a stored secret: {}", e.toString());
			return null;
		}
	}

	private static synchronized byte[] key() throws IOException {
		if (key != null) return key;
		if (Files.isRegularFile(file)) {
			byte[] stored = storedKey(file);
			if (stored.length == 32) return key = stored;
			// Set aside, not deleted: a key restored from it can still open the saved logins.
			Path aside = file.resolveSibling(file.getFileName() + ".bad-" + System.currentTimeMillis());
			Files.move(file, aside);
			CoffeeClient.LOG.warn("[Vault] {} is damaged; moved it to {} and made a new key (saved logins must be added again)", file, aside.getFileName());
		}
		byte[] fresh = new byte[32];
		RANDOM.nextBytes(fresh);
		Files.createDirectories(file.getParent());
		// Written beside the key and moved into place, so a crash mid-write never leaves an empty key behind.
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.deleteIfExists(tmp);
		try {
			// Owner-only from the first byte, not chmodded afterwards.
			Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
		} catch (UnsupportedOperationException e) {
			// Windows: the home folder is already private to the user.
			Files.createFile(tmp);
		}
		Files.writeString(tmp, Base64.getEncoder().encodeToString(fresh));
		try {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		}
		return key = fresh;
	}

	/** The key in the file. The MIME decoder skips stray characters, so a key with a typo still opens; unreadable gives empty. */
	private static byte[] storedKey(Path file) throws IOException {
		try {
			return Base64.getMimeDecoder().decode(Files.readString(file));
		} catch (IllegalArgumentException e) {
			return new byte[0];
		}
	}
}
