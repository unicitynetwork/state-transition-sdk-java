package org.unicitylabs.sdk.crypto.secp256k1;

import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.crypto.signers.HMacDSAKCalculator;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECParameterSpec;
import org.bouncycastle.math.ec.ECCurve;
import org.bouncycastle.math.ec.ECPoint;
import org.unicitylabs.sdk.crypto.hash.DataHash;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Default signing service.
 */
public class SigningService {

  private static final String CURVE_NAME = "secp256k1";
  private static final ECParameterSpec EC_SPEC = ECNamedCurveTable.getParameterSpec(CURVE_NAME);
  private static final ECDomainParameters EC_DOMAIN_PARAMETERS = new ECDomainParameters(
          EC_SPEC.getCurve(),
          EC_SPEC.getG(),
          EC_SPEC.getN(),
          EC_SPEC.getH()
  );

  /** Signatures are over a raw 32-byte digest; any other length is rejected, as in Rust. */
  private static final int DIGEST_LENGTH = 32;

  private static final BigInteger HALF_ORDER = EC_DOMAIN_PARAMETERS.getN().shiftRight(1);

  private final ECPrivateKeyParameters privateKey;
  private final byte[] publicKey;

  /**
   * Signing service constructor.
   *
   * @param privateKey private key bytes.
   */
  public SigningService(byte[] privateKey) {
    if (privateKey == null) {
      throw new IllegalArgumentException("privateKey cannot be null");
    }

    BigInteger privateKeyAsBigInt = new BigInteger(1, privateKey);
    if (privateKeyAsBigInt.compareTo(BigInteger.ONE) < 0
            || privateKeyAsBigInt.compareTo(EC_SPEC.getN()) >= 0) {
      throw new IllegalArgumentException("Invalid private key: must be in range [1, N)");
    }

    // Calculate public key
    ECPoint q = EC_SPEC.getG().multiply(privateKeyAsBigInt);
    this.publicKey = q.getEncoded(true); // compressed format
    this.privateKey = new ECPrivateKeyParameters(
            privateKeyAsBigInt,
            EC_DOMAIN_PARAMETERS
    );
  }

  /**
   * Get public key.
   *
   * @return public key bytes
   */
  public byte[] getPublicKey() {
    return Arrays.copyOf(this.publicKey, this.publicKey.length);
  }

  /**
   * Get signing algorithm.
   *
   * @return algorithm name
   */
  public String getAlgorithm() {
    return "secp256k1";
  }

  /**
   * Generate a random private key.
   *
   * @return private key bytes
   */
  public static byte[] generatePrivateKey() {
    SecureRandom random = new SecureRandom();
    byte[] privateKey = new byte[32];
    random.nextBytes(privateKey);
    return privateKey;
  }

  /**
   * Generate a signing service instance with a randomly generated private key.
   *
   * @return signing service instance
   */
  public static SigningService generate() {
    return new SigningService(SigningService.generatePrivateKey());
  }

  /**
   * Sign data hash.
   *
   * @param hash data hash
   * @return signature
   */
  public Signature sign(DataHash hash) {
    ECDSASigner signer = new ECDSASigner(new HMacDSAKCalculator(new SHA256Digest()));
    signer.init(true, this.privateKey);

    BigInteger[] signature = signer.generateSignature(hash.getData());
    BigInteger r = signature[0];
    BigInteger s = signature[1];

    // Ensure s is in the lower half of the order (malleability fix)
    if (s.compareTo(HALF_ORDER) > 0) {
      s = EC_DOMAIN_PARAMETERS.getN().subtract(s);
    }

    byte[] signatureBytes = new byte[64];
    System.arraycopy(toFixedLength(r, 32), 0, signatureBytes, 0, 32);
    System.arraycopy(toFixedLength(s, 32), 0, signatureBytes, 32, 32);

    // Calculate recovery ID
    int recoveryId = 0;
    for (int i = 0; i < 4; i++) {
      try {
        ECPoint recovered = recoverFromSignature(i, r, s, hash.getData());
        if (recovered != null && Arrays.equals(recovered.getEncoded(true), publicKey)) {
          recoveryId = i;
          break;
        }
      } catch (Exception ex) {
        // Try next recovery ID
      }
    }

    return new Signature(signatureBytes, recoveryId);
  }

  /**
   * Verify signature and data hash.
   *
   * @param hash      data hash
   * @param signature signature
   * @return true if successful
   */
  public boolean verify(DataHash hash, Signature signature) {
    return SigningService.verifyWithPublicKey(hash, signature, this.publicKey);
  }

  /**
   * Verify secp256k1 signature against the given public key.
   *
   * @param hash      data hash
   * @param signature compact signature bytes
   * @param publicKey public key
   * @return true if successful
   */
  public static boolean verify(DataHash hash, byte[] signature, byte[] publicKey) {
    return SigningService.verify(hash.getData(), signature, publicKey);
  }

  /**
   * Verify secp256k1 signature against the given public key and data hash bytes.
   *
   * @param hash      hash bytes
   * @param signature compact signature bytes
   * @param publicKey public key
   * @return true if successful
   */
  public static boolean verify(byte[] hash, byte[] signature, byte[] publicKey) {
    if (hash == null || hash.length != DIGEST_LENGTH || !SigningService.isCanonical(signature)) {
      return false;
    }

    ECPoint pubPoint = EC_SPEC.getCurve().decodePoint(publicKey);
    ECPublicKeyParameters pubKey = new ECPublicKeyParameters(pubPoint, EC_DOMAIN_PARAMETERS);

    ECDSASigner verifier = new ECDSASigner();
    verifier.init(false, pubKey);

    // Extract r and s from compact signature (first 64 bytes)
    BigInteger r = new BigInteger(1, Arrays.copyOfRange(signature, 0, 32));
    BigInteger s = new BigInteger(1, Arrays.copyOfRange(signature, 32, 64));

    return verifier.verifySignature(hash, r, s);
  }

  /**
   * Check that compact signature scalars are canonical: {@code 1 <= r < n} and
   * {@code 1 <= s <= n/2} (low-S). The high-S twin of a signature verifies under the same key, so
   * it is rejected to keep a single encoding per signature, as the Rust and JS SDKs do.
   *
   * @param signature compact signature bytes, at least the 64 bytes {@code r || s}
   * @return true if the signature is 64 or more bytes and its scalars are canonical
   */
  public static boolean isCanonical(byte[] signature) {
    if (signature == null || signature.length < 64) {
      return false;
    }

    BigInteger r = new BigInteger(1, Arrays.copyOfRange(signature, 0, 32));
    BigInteger s = new BigInteger(1, Arrays.copyOfRange(signature, 32, 64));
    return r.signum() > 0 && r.compareTo(EC_DOMAIN_PARAMETERS.getN()) < 0
            && s.signum() > 0 && s.compareTo(HALF_ORDER) <= 0;
  }

  /**
   * Verify a recoverable signature against an expected public key. Unlike {@link #verify(DataHash,
   * byte[], byte[])}, this binds the signature's recovery byte: the public key is recovered from
   * the signature and must equal {@code publicKey}, so a tampered recovery byte fails verification.
   *
   * @param hash      data hash
   * @param signature recoverable signature
   * @param publicKey expected compressed public key
   * @return true if the signature verifies and the recovered public key matches {@code publicKey}
   */
  public static boolean verifyWithPublicKey(DataHash hash, Signature signature, byte[] publicKey) {
    byte[] recoveredPublicKey = SigningService.recoverPublicKey(hash, signature);
    return recoveredPublicKey != null && Arrays.equals(publicKey, recoveredPublicKey);
  }


  private byte[] toFixedLength(BigInteger value, int length) {
    byte[] bytes = value.toByteArray();
    if (bytes.length == length) {
      return bytes;
    }

    byte[] result = new byte[length];
    if (bytes.length > length) {
      // Remove leading zero if present
      System.arraycopy(bytes, bytes.length - length, result, 0, length);
    } else {
      // Pad with zeros
      System.arraycopy(bytes, 0, result, length - bytes.length, bytes.length);
    }
    return result;
  }

  /**
   * Recover public key from signature for a specific recovery ID.
   */
  private static ECPoint recoverFromSignature(
          int recId,
          BigInteger r,
          BigInteger s,
          byte[] message
  ) {
    if (recId < 0 || recId > 3) {
      return null;
    }

    BigInteger n = EC_DOMAIN_PARAMETERS.getN();
    BigInteger x = r;

    if (recId >= 2) {
      x = x.add(n);
    }

    ECCurve curve = EC_DOMAIN_PARAMETERS.getCurve();
    if (x.compareTo(curve.getField().getCharacteristic()) >= 0) {
      return null;
    }

    // Calculate y from x
    ECPoint y = SigningService.decompressKey(x, (recId & 1) == 1, curve);
    if (y == null) {
      return null;
    }

    // Verify R is on curve and has order n
    if (!y.isValid() || !y.multiply(n).isInfinity()) {
      return null;
    }

    BigInteger e = new BigInteger(1, message);

    // Calculate public key: Q = r^-1 * (s*R - e*G)
    ECPoint point1 = y.multiply(s);
    ECPoint point2 = EC_DOMAIN_PARAMETERS.getG().multiply(e);
    return point1
            .subtract(point2)
            .multiply(r.modInverse(n));
  }


  /**
   * Decompress a compressed public key point.
   */
  private static ECPoint decompressKey(BigInteger x, boolean ybit, ECCurve curve) {
    try {
      byte[] compEnc = new byte[33];
      compEnc[0] = (byte) (ybit ? 0x03 : 0x02);
      byte[] xbytes = x.toByteArray();
      if (xbytes.length > 32) {
        System.arraycopy(xbytes, xbytes.length - 32, compEnc, 1, 32);
      } else {
        System.arraycopy(xbytes, 0, compEnc, 33 - xbytes.length, xbytes.length);
      }
      return curve.decodePoint(compEnc);
    } catch (Exception e) {
      return null;
    }
  }

  /**
   * Recover the public key from the signature's recovery byte and verify the signature against
   * {@code hash}. The recovered key defines the signer's identity; no expected key is supplied.
   *
   * @param hash      data hash
   * @param signature recoverable signature
   * @return true if successful
   */
  public static boolean verifyWithRecoveredPublicKey(DataHash hash, Signature signature) {
    byte[] recoveredPublicKey = SigningService.recoverPublicKey(hash, signature);
    return recoveredPublicKey != null;
  }

  /**
   * Recover the compressed public key that produced {@code signature} over {@code hash}, using the
   * signature's recovery byte.
   *
   * @param hash      data hash
   * @param signature recoverable signature
   * @return recovered compressed public key, or {@code null} if the signature is not recoverable
   */
  private static byte[] recoverPublicKey(DataHash hash, Signature signature) {
    try {
      if (hash.getData().length != DIGEST_LENGTH
              || !SigningService.isCanonical(signature.getBytes())) {
        return null;
      }

      BigInteger r = new BigInteger(1, Arrays.copyOfRange(signature.getBytes(), 0, 32));
      BigInteger s = new BigInteger(1, Arrays.copyOfRange(signature.getBytes(), 32, 64));

      ECPoint recovered = recoverFromSignature(signature.getRecovery(), r, s, hash.getData());
      if (recovered == null || recovered.isInfinity() || !recovered.isValid()) {
        return null;
      }

      return recovered.getEncoded(true);
    } catch (Exception e) {
      return null;
    }
  }
}