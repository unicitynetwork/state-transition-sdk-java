package org.unicitylabs.sdk.crypto.secp256k1;

import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.junit.jupiter.api.Test;

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
        boolean isValid = SigningService.verifyWithPublicKey(hash, signature.getBytes(), publicKey);
        
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
}