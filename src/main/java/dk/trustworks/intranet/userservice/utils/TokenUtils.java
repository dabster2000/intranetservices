package dk.trustworks.intranet.userservice.utils;

import dk.trustworks.intranet.userservice.model.Role;
import dk.trustworks.intranet.userservice.model.User;
import io.smallrye.jwt.build.Jwt;
import io.smallrye.jwt.build.JwtClaimsBuilder;
import org.eclipse.microprofile.jwt.Claims;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class TokenUtils {

    private TokenUtils() {
        // no-op: utility class
    }

    /**
     * Utility method to generate a JWT string from a JSON resource file that is signed by the privateKey.pem
     * test resource key, possibly with invalid fields.
     *
     * @param timeClaims - used to return the exp, iat, auth_time claims
     * @return the JWT string
     * @throws Exception on parse failure
     */
    public static String generateTokenString(User user, Map<String, Long> timeClaims)
            throws Exception {
        PrivateKey pk = readPrivateKey("/privateKey.pem");
        return generateTokenString(user, pk, "/privateKey.pem", timeClaims);
    }

    public static String generateTokenString(User user, PrivateKey privateKey, String kid, Map<String, Long> timeClaims) {

        long currentTimeInSecs = currentTimeInSecs();
        long exp = timeClaims != null && timeClaims.containsKey(Claims.exp.name())
                ? timeClaims.get(Claims.exp.name())
                : currentTimeInSecs + 300000;

        JwtClaimsBuilder claims = Jwt.claims()
                .issuer("https://trustworks.dk")
                .preferredUserName(user.getUsername())
                .issuedAt(currentTimeInSecs)
                .expiresAt(exp)
                .groups(Role.findByUseruuid(user.getUuid()).stream().map(role -> role.getRole().toString()).collect(Collectors.toSet()));


        return claims.jws().keyId(kid).sign(privateKey);
    }

    public static String generateSystemUserTokenString(User user, Map<String, Long> timeClaims, List<Role> roles)
            throws Exception {
        PrivateKey pk = readPrivateKey("/privateKey.pem");
        return generateSystemUserTokenString(user, pk, "/privateKey.pem", timeClaims, roles);
    }

    public static String generateSystemUserTokenString(User user, PrivateKey privateKey, String kid, Map<String, Long> timeClaims, List<Role> roles) {

        long currentTimeInSecs = currentTimeInSecs();
        long exp = timeClaims != null && timeClaims.containsKey(Claims.exp.name())
                ? timeClaims.get(Claims.exp.name())
                : currentTimeInSecs + 300000;

        JwtClaimsBuilder claims = Jwt.claims()
                .issuer("https://trustworks.dk")
                .preferredUserName(user.getUsername())
                .issuedAt(currentTimeInSecs)
                .expiresAt(exp)
                .groups(roles.stream().map(role -> role.getRole().toString()).collect(Collectors.toSet()));


        return claims.jws().keyId(kid).sign(privateKey);
    }

    /**
     * Read a PEM encoded private key from the classpath
     *
     * @param pemResName - key file resource name
     * @return PrivateKey
     * @throws Exception on decode failure
     */
    public static PrivateKey readPrivateKey(final String pemResName) throws Exception {
        try (InputStream contentIS = TokenUtils.class.getResourceAsStream(pemResName)) {
            byte[] tmp = new byte[4096];
            assert contentIS != null;
            int length = contentIS.read(tmp);
            return decodePrivateKey(new String(tmp, 0, length, StandardCharsets.UTF_8));
        }
    }

    /**
     * Generate a new RSA keypair.
     *
     * @param keySize - the size of the key
     * @return KeyPair
     * @throws NoSuchAlgorithmException on failure to load RSA key generator
     */
    public static KeyPair generateKeyPair(final int keySize) throws NoSuchAlgorithmException {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(keySize);
        return keyPairGenerator.genKeyPair();
    }

    /**
     * Decode a PEM encoded private key string to an RSA PrivateKey
     *
     * @param pemEncoded - PEM string for private key
     * @return PrivateKey
     * @throws Exception on decode failure
     */
    public static PrivateKey decodePrivateKey(final String pemEncoded) throws Exception {
        byte[] encodedBytes = toEncodedBytes(pemEncoded);

        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(encodedBytes);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return kf.generatePrivate(keySpec);
    }

    /**
     * Decode a PEM encoded public key string to an RSA PublicKey
     *
     * @param pemEncoded - PEM string for private key
     * @return PublicKey
     * @throws Exception on decode failure
     */
    public static PublicKey decodePublicKey(String pemEncoded) throws Exception {
        byte[] encodedBytes = toEncodedBytes(pemEncoded);

        X509EncodedKeySpec spec = new X509EncodedKeySpec(encodedBytes);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return kf.generatePublic(spec);
    }

    /**
     * Validates a JWT token
     *
     * @param token - the JWT token to validate
     * @return boolean indicating if the token is valid
     */
    public static boolean validateToken(String token) {
        try {
            // Read the public key for verification
            PublicKey publicKey = readPublicKey("/publicKey.pem");

            // Prepare to verify the signature
            String[] tokenParts = token.split("\\.");
            if (tokenParts.length != 3) {
                return false;
            }

            // Data that was signed (header and payload)
            String signedData = tokenParts[0] + "." + tokenParts[1];

            // Decode the signature
            byte[] signatureBytes = Base64.getUrlDecoder().decode(tokenParts[2]);

            // Create a signature instance for verification
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(publicKey);
            signature.update(signedData.getBytes(StandardCharsets.UTF_8));

            // Verify the signature
            boolean signatureValid = signature.verify(signatureBytes);
            if (!signatureValid) {
                return false;
            }

            // Verify token is not expired
            String payload = new String(Base64.getUrlDecoder().decode(tokenParts[1]));
            jakarta.json.JsonObject payloadJson = jakarta.json.Json.createReader(
                    new java.io.StringReader(payload)).readObject();

            if (payloadJson.containsKey("exp")) {
                long exp = payloadJson.getJsonNumber("exp").longValue();
                if (exp < currentTimeInSecs()) {
                    return false; // Token is expired
                }
            }

            // Token is valid
            return true;
        } catch (Exception e) {
            // Any exception during validation means the token is invalid
            return false;
        }
    }

    /**
     * Read a PEM encoded public key from the classpath
     *
     * @param pemResName - key file resource name
     * @return PublicKey
     * @throws Exception on decode failure
     */
    public static PublicKey readPublicKey(final String pemResName) throws Exception {
        try (InputStream contentIS = TokenUtils.class.getResourceAsStream(pemResName)) {
            byte[] tmp = new byte[4096];
            assert contentIS != null;
            int length = contentIS.read(tmp);
            return decodePublicKey(new String(tmp, 0, length, StandardCharsets.UTF_8));
        }
    }

    private static byte[] toEncodedBytes(final String pemEncoded) {
        final String normalizedPem = removeBeginEnd(pemEncoded);
        return Base64.getDecoder().decode(normalizedPem);
    }

    private static String removeBeginEnd(String pem) {
        pem = pem.replaceAll("-----BEGIN (.*)-----", "");
        pem = pem.replaceAll("-----END (.*)----", "");
        pem = pem.replaceAll("\r\n", "");
        pem = pem.replaceAll("\n", "");
        return pem.trim();
    }

    /**
     * @return the current time in seconds since epoch
     */
    public static int currentTimeInSecs() {
        long currentTimeMS = System.currentTimeMillis();
        return (int) (currentTimeMS / 1000);
    }

}
