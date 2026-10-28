package ai.momobird.halo.core;

/**
 * The outcome of comparing Halo's external address with MomoBird's source address.
 *
 * @param ok           the two share scheme, host and port
 * @param siteOrigin   the origin of Halo's external address, or null when it is unset or unusable
 * @param sourceOrigin the origin of the source address, or null when it is unset or unusable
 * @param reason       a sentence for the admin page when {@code ok} is false; null otherwise
 */
public record OriginCheck(boolean ok, String siteOrigin, String sourceOrigin, String reason) {
}
