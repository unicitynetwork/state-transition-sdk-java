package org.unicitylabs.sdk.crypto.secp256k1;

import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.crypto.hash.DataHasher;
import org.unicitylabs.sdk.util.HexConverter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test signature recovery functionality
 */
public class SignatureRecoveryTest {
    
    @Test
    void testSignatureRecoveryId() {
        // Create a signing service with a known private key
        byte[] privateKey = HexConverter.decode("c85ef7d79691fe79573b1a7064c19c1a9819ebdbd1faaab1a8ec92344438aaf4");
        SigningService signingService = new SigningService(privateKey);
        
        // Create test data and hash it
        byte[] testData = "Hello, Unicity!".getBytes();
        DataHasher hasher = new DataHasher(HashAlgorithm.SHA256);
        hasher.update(testData);
        DataHash hash = hasher.digest();
        
        // Sign the hash
        Signature signature = signingService.sign(hash);
        
        // Verify recovery ID is 0 or 1
        assertTrue(signature.getRecovery() == 0 || signature.getRecovery() == 1, 
                  "Recovery ID should be 0 or 1, got: " + signature.getRecovery());
        
        // Verify signature with known public key
        byte[] publicKey = signingService.getPublicKey();
        assertTrue(SigningService.verifyWithPublicKey(hash, signature.getBytes(), publicKey));
    }
    
    @Test
    void testPublicKeyRecovery() {
        // Create a signing service with a known private key
        byte[] privateKey = HexConverter.decode("c85ef7d79691fe79573b1a7064c19c1a9819ebdbd1faaab1a8ec92344438aaf4");
        SigningService signingService = new SigningService(privateKey);
        byte[] expectedPublicKey = signingService.getPublicKey();
        
        // Create test data and hash it
        byte[] testData = "Test public key recovery".getBytes();
        DataHasher hasher = new DataHasher(HashAlgorithm.SHA256);
        hasher.update(testData);
        DataHash hash = hasher.digest();
        
        // Sign the hash
        Signature signature = signingService.sign(hash);
        
        // Verify signature using recovered public key
        assertTrue(SigningService.verifySignatureWithRecoveredPublicKey(hash, signature),
                  "Signature verification with recovered public key should succeed");
    }
    
    @Test
    void testSignatureFormatCompliance() {
        // Test with the exact values from TypeScript test
        String transactionHashHex = "0000d6035b65700f0af73cc62a580eb833c20f40aaee460087f5fb43ebb3c047f1d4";
        String signatureHex = "301c7f19d5e0a7e350012ab7bbaf26a0152a751eec06d18563f96bcf06d2380e7de7ce6cebb8c11479d1bd9c463c3ba47396b5f815c552b344d430b0d011a2e701";
        String expectedPublicKeyHex = "02bf8d9e7687f66c7fce1e98edbc05566f7db740030722cf6cf62aca035c5035ea";
        
        // Parse the signature
        byte[] sigBytes = HexConverter.decode(signatureHex);
        assertEquals(65, sigBytes.length, "Signature should be 65 bytes");
        
        // Extract components
        int recoveryId = sigBytes[64] & 0xFF;
        
        // Create signature object
        byte[] sigOnly = new byte[64];
        System.arraycopy(sigBytes, 0, sigOnly, 0, 64);
        Signature signature = new Signature(sigOnly, recoveryId);
        
        // Parse hash
        DataHash transactionHash = DataHash.fromImprint(HexConverter.decode(transactionHashHex));
        
        // Verify using recovered public key
        assertTrue(SigningService.verifySignatureWithRecoveredPublicKey(transactionHash, signature),
                  "Should verify with recovered public key");
    }
}