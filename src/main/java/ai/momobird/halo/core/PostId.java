package ai.momobird.halo.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;

/**
 * A stable numeric id for a post, derived from its Halo {@code metadata.name}.
 *
 * <p>MomoBird's public podcast API and the player only accept numeric post ids, so the id is the
 * first 52 bits of the name's SHA-256 written in decimal: at most 16 digits, inside the
 * safe-integer range. The algorithm is byte for byte the one in hexo-momobird-ai
 * ({@code lib/post-id.js}), minus its backslash-to-slash step that only applies to file paths.
 */
public final class PostId {

    private static final int HEX_CHARS = 13;

    private PostId() {
    }

    public static String of(String name) {
        String normalized = Normalizer.normalize(name == null ? "" : name, Normalizer.Form.NFC);
        byte[] digest = sha256(normalized.getBytes(StandardCharsets.UTF_8));
        String hex = HexFormat.of().formatHex(digest).substring(0, HEX_CHARS);
        return Long.toString(Long.parseLong(hex, 16));
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
