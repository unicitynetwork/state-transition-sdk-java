package org.unicitylabs.sdk.crypto.secp256k1;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;

import static org.junit.jupiter.api.Assertions.*;

public class SigningServiceTest {

  @Test
  public void testGeneratePrivateKey() {
    byte[] privateKey = SigningService.generatePrivateKey();

    assertNotNull(privateKey);
    assertEquals(32, privateKey.length);

    // Test that we can create a signing service with it
    SigningService service = new SigningService(privateKey);
    assertNotNull(service.getPublicKey());
    assertEquals(33, service.getPublicKey().length); // Compressed public key
  }

  @Test
  public void testSignAndVerify() {
    byte[] privateKey = SigningService.generatePrivateKey();
    SigningService service = new SigningService(privateKey);

    // Create a test hash
    DataHash hash = new DataHash(HashAlgorithm.SHA256, new byte[32]);

    // Sign the hash
    Signature signature = service.sign(hash);

    assertNotNull(signature);
    assertEquals(64, signature.getBytes().length);

    // Verify the signature
    boolean isValid = service.verify(hash, signature);

    assertTrue(isValid);
  }

  @Test
  public void testVerifyWithPublicKey() {
    byte[] privateKey = SigningService.generatePrivateKey();
    SigningService service = new SigningService(privateKey);
    byte[] publicKey = service.getPublicKey();

    // Create a test hash
    DataHash hash = new DataHash(HashAlgorithm.SHA256, new byte[32]);

    // Sign the hash
    Signature signature = service.sign(hash);

    // Verify with public key
    boolean isValid = SigningService.verify(hash, signature.getBytes(), publicKey);

    assertTrue(isValid);
  }

  @Test
  public void testInvalidSignature() {
    byte[] privateKey = SigningService.generatePrivateKey();
    SigningService service = new SigningService(privateKey);

    // Create a test hash
    DataHash hash = new DataHash(HashAlgorithm.SHA256, new byte[32]);

    // Create an invalid signature
    byte[] invalidSig = new byte[64];
    Signature signature = new Signature(invalidSig, 0);

    // Verify the signature
    boolean isValid = service.verify(hash, signature);

    assertFalse(isValid);
  }

  private static final BigInteger N = new BigInteger(
      "fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141", 16);

  private static byte[] scalar(BigInteger value) {
    byte[] bytes = new byte[32];
    byte[] raw = value.toByteArray();
    int length = Math.min(32, raw.length);
    System.arraycopy(raw, raw.length - length, bytes, 32 - length, length);
    return bytes;
  }

  private static Signature withScalars(BigInteger r, BigInteger s, int recovery) {
    byte[] bytes = new byte[64];
    System.arraycopy(scalar(r), 0, bytes, 0, 32);
    System.arraycopy(scalar(s), 0, bytes, 32, 32);
    return new Signature(bytes, recovery);
  }

  private static Signature highSTwin(Signature signature, int recovery) {
    byte[] bytes = signature.getBytes();
    return withScalars(new BigInteger(1, Arrays.copyOfRange(bytes, 0, 32)),
        N.subtract(new BigInteger(1, Arrays.copyOfRange(bytes, 32, 64))), recovery);
  }

  @Test
  public void testLowSIsAccepted() {
    SigningService service = new SigningService(SigningService.generatePrivateKey());
    DataHash hash = new DataHash(HashAlgorithm.SHA256, new byte[32]);
    Signature signature = service.sign(hash);

    assertTrue(SigningService.isCanonical(signature.getBytes()));
    assertTrue(service.verify(hash, signature));
    assertTrue(SigningService.verifyWithPublicKey(hash, signature, service.getPublicKey()));
    assertTrue(SigningService.verify(hash, signature.getBytes(), service.getPublicKey()));
  }

  @Test
  public void testHighSTwinIsRejectedForBothRecoveryBytes() {
    SigningService service = new SigningService(SigningService.generatePrivateKey());
    DataHash hash = new DataHash(HashAlgorithm.SHA256, new byte[32]);
    Signature signature = service.sign(hash);

    for (int recovery : new int[] {signature.getRecovery(), signature.getRecovery() ^ 1}) {
      Signature twin = highSTwin(signature, recovery);
      assertFalse(SigningService.isCanonical(twin.getBytes()));
      assertFalse(service.verify(hash, twin));
      assertFalse(SigningService.verifyWithPublicKey(hash, twin, service.getPublicKey()));
      assertFalse(SigningService.verifyWithRecoveredPublicKey(hash, twin));
      assertFalse(SigningService.verify(hash, twin.getBytes(), service.getPublicKey()));
    }
  }

  @Test
  public void testOutOfRangeScalarsAreRejected() {
    SigningService service = new SigningService(SigningService.generatePrivateKey());
    DataHash hash = new DataHash(HashAlgorithm.SHA256, new byte[32]);
    Signature signature = service.sign(hash);
    BigInteger r = new BigInteger(1, Arrays.copyOfRange(signature.getBytes(), 0, 32));
    BigInteger s = new BigInteger(1, Arrays.copyOfRange(signature.getBytes(), 32, 64));

    List<Signature> invalid = List.of(
        withScalars(BigInteger.ZERO, s, signature.getRecovery()),
        withScalars(r, BigInteger.ZERO, signature.getRecovery()),
        withScalars(N, s, signature.getRecovery()),
        withScalars(r, N, signature.getRecovery()),
        withScalars(r, BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE), signature.getRecovery()));
    for (Signature candidate : invalid) {
      assertFalse(SigningService.isCanonical(candidate.getBytes()));
      assertFalse(service.verify(hash, candidate));
      assertFalse(SigningService.verifyWithPublicKey(hash, candidate, service.getPublicKey()));
      assertFalse(SigningService.verify(hash, candidate.getBytes(), service.getPublicKey()));
    }
  }

  @Test
  public void testDigestLongerThan32BytesIsRejected() {
    SigningService service = new SigningService(SigningService.generatePrivateKey());
    byte[] long64 = new byte[64];
    Arrays.fill(long64, (byte) 0x42);
    // a signature over int(d) mod n, which recovery would otherwise accept for the 64-byte digest
    byte[] reduced = scalar(new BigInteger(1, long64).mod(N));
    Signature signature = service.sign(new DataHash(HashAlgorithm.SHA256, reduced));
    DataHash longHash = new DataHash(HashAlgorithm.SHA512, long64);

    assertFalse(service.verify(longHash, signature));
    assertFalse(SigningService.verifyWithPublicKey(longHash, signature, service.getPublicKey()));
    assertFalse(SigningService.verifyWithRecoveredPublicKey(longHash, signature));
    assertFalse(SigningService.verify(longHash, signature.getBytes(), service.getPublicKey()));
    assertFalse(SigningService.verify(new byte[31], signature.getBytes(), service.getPublicKey()));
  }
}
