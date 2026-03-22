package org.unicitylabs.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.api.AggregatorClient;
import org.unicitylabs.sdk.api.CertificationData;
import org.unicitylabs.sdk.api.CertificationResponse;
import org.unicitylabs.sdk.api.CertificationStatus;
import org.unicitylabs.sdk.api.JsonRpcAggregatorClient;
import org.unicitylabs.sdk.crypto.hash.DataHash;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.api.jsonrpc.JsonRpcNetworkException;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.predicate.builtin.PayToPublicKeyPredicate;
import org.unicitylabs.sdk.transaction.Address;
import org.unicitylabs.sdk.transaction.MintTransaction;
import org.unicitylabs.sdk.transaction.TokenId;
import org.unicitylabs.sdk.transaction.TokenType;
import org.unicitylabs.sdk.util.HexConverter;

public class TestApiKeyIntegration {

  private static final String TEST_API_KEY = "test-api-key-12345";

  private MockAggregatorServer mockServer;
  private AggregatorClient clientWithApiKey;
  private AggregatorClient clientWithoutApiKey;

  private CertificationData certificationData;

  @BeforeEach
  void setUp() throws Exception {
    mockServer = new MockAggregatorServer();
    mockServer.setExpectedApiKey(TEST_API_KEY);
    mockServer.start();

    clientWithApiKey = new JsonRpcAggregatorClient(
        mockServer.getUrl(), TEST_API_KEY);
    clientWithoutApiKey = new JsonRpcAggregatorClient(mockServer.getUrl());

    SigningService signingService = new SigningService(
        HexConverter.decode("0000000000000000000000000000000000000000000000000000000000000001"));

    var transaction = MintTransaction.create(
            Address.fromPredicate(PayToPublicKeyPredicate.fromSigningService(signingService)),
            TokenId.generate(),
            TokenType.generate(),
            new byte[32]
    );
    certificationData = CertificationData.fromMintTransaction(transaction);
  }

  @AfterEach
  void tearDown() throws Exception {
    mockServer.shutdown();
  }

  @Test
  public void testSubmitCommitmentWithApiKey() throws Exception {
    CompletableFuture<CertificationResponse> future = clientWithApiKey.submitCertificationRequest(
        certificationData
    );

    CertificationResponse response = future.get(5, TimeUnit.SECONDS);
    assertEquals(CertificationStatus.SUCCESS, response.getStatus());

    RecordedRequest request = mockServer.takeRequest();
    assertEquals("Bearer " + TEST_API_KEY, request.getHeader("Authorization"));
  }

  @Test
  public void testSubmitCommitmentWithoutApiKeyThrowsUnauthorized() throws Exception {
    CompletableFuture<CertificationResponse> future = clientWithoutApiKey.submitCertificationRequest(
        certificationData
    );

    try {
      future.get(5, TimeUnit.SECONDS);
      fail("Expected UnauthorizedException to be thrown");
    } catch (Exception e) {
      assertInstanceOf(ExecutionException.class, e);
      assertInstanceOf(JsonRpcNetworkException.class, e.getCause());
      assertEquals("Network error [401] occurred: Unauthorized", e.getCause().getMessage());
    }

    RecordedRequest request = mockServer.takeRequest();
    assertNull(request.getHeader("Authorization"));
  }

  @Test
  public void testSubmitCommitmentWithWrongApiKeyThrowsUnauthorized() throws Exception {
    mockServer.setExpectedApiKey("different-api-key");

    CompletableFuture<CertificationResponse> future = clientWithApiKey.submitCertificationRequest(
        certificationData
    );

    try {
      future.get(5, TimeUnit.SECONDS);
      fail("Expected UnauthorizedException to be thrown");
    } catch (Exception e) {
      assertInstanceOf(ExecutionException.class, e);
      assertInstanceOf(JsonRpcNetworkException.class, e.getCause());
      assertEquals("Network error [401] occurred: Unauthorized", e.getCause().getMessage());
    }

    RecordedRequest request = mockServer.takeRequest();
    assertEquals("Bearer " + TEST_API_KEY, request.getHeader("Authorization"));
  }

  @Test
  public void testRateLimitExceeded() {
    mockServer.simulateRateLimitForNextRequest(30);

    CompletableFuture<CertificationResponse> future = clientWithApiKey.submitCertificationRequest(
        certificationData
    );

    try {
      future.get(5, TimeUnit.SECONDS);
      fail("Expected RateLimitExceededException to be thrown");
    } catch (Exception e) {
      assertInstanceOf(ExecutionException.class, e);
      assertInstanceOf(JsonRpcNetworkException.class, e.getCause());
      assertTrue(e.getCause().getMessage().contains("Network error [429] occurred: Too Many Requests"),
          e.getCause().getMessage());
    }
  }

  @Test
  public void testGetBlockHeightWorksWithoutApiKey() throws Exception {
    CompletableFuture<Long> future = clientWithoutApiKey.getBlockHeight();

    Long blockHeight = future.get(5, TimeUnit.SECONDS);
    assertNotNull(blockHeight);
    assertEquals(67890L, blockHeight);
  }

  @Test
  public void testGetBlockHeightAlsoWorksWithApiKey() throws Exception {
    CompletableFuture<Long> future = clientWithApiKey.getBlockHeight();

    Long blockHeight = future.get(5, TimeUnit.SECONDS);
    assertNotNull(blockHeight);
    assertEquals(67890L, blockHeight);
  }
}