package org.unicitylabs.sdk.api.bft;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.unicitylabs.sdk.api.HttpTrustBaseSource;
import org.unicitylabs.sdk.api.NetworkId;
import org.unicitylabs.sdk.api.bft.trustbase.MemoryTrustBaseStore;
import org.unicitylabs.sdk.api.bft.trustbase.ReleasePin;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseCacheKey;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseEntry;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseHistory;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseResponseWire;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseSession;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseSessionOptions;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseUpdateOptions;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseUpdater;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseUpdateTarget;
import org.unicitylabs.sdk.api.bft.verification.NoTrustBaseEntryResult;
import org.unicitylabs.sdk.api.bft.verification.OperationUnicityCertificateVerifier;
import org.unicitylabs.sdk.api.bft.verification.UnicityCertificateFailure;
import org.unicitylabs.sdk.api.bft.verification.UnicityCertificateVerificationResult;
import org.unicitylabs.sdk.api.bft.verification.UnicityCertificateVerifier;
import org.unicitylabs.sdk.api.jsonrpc.SdkVersionHeader;
import org.unicitylabs.sdk.util.verification.VerificationStatus;

/**
 * An offline wallet with a token whose certificate is of an epoch it has not heard of: verification
 * says so with the typed no-entry result, one update over HTTP fetches exactly the missing epoch, and
 * the next verification succeeds, also after a restart with no network. Synthetic signed epochs (the
 * live networks do not sign V2 entries yet; see the real-seal tests for what they do show).
 */
class OfflineWalletFutureEpochTest {

  private static final TestChain.Authority EPOCH1 = new TestChain.Authority();
  private static final TestChain.Authority EPOCH2 =
      new TestChain.Authority(NetworkId.LOCAL, 2, 10, 1);

  private MockWebServer server;

  @BeforeEach
  void start() throws IOException {
    this.server = new MockWebServer();
    this.server.start();
  }

  @AfterEach
  void stop() throws IOException {
    this.server.shutdown();
  }

  private static UnicityCertificateVerificationResult verify(TrustBaseHistory history,
      UnicityCertificate certificate) {
    OperationUnicityCertificateVerifier verifier = (OperationUnicityCertificateVerifier)
        new UnicityCertificateVerifier().beginOperation(null);
    return verifier.verifyCertificate(history, certificate);
  }

  private MockResponse pageOfEpoch2(TrustBaseEntry epoch2) {
    byte[] page = TrustBaseResponseWire.encode(new TrustBaseResponseWire.Fields(2,
        epoch2.entryId(), Collections.singletonList(epoch2.encode()), 2,
        NetworkId.LOCAL.getId(), null, 0));
    return new MockResponse().setBody(new Buffer().write(page));
  }

  @Test
  void anOldTokenStillVerifiesAndANewEpochIsFetchedOnceThenKnownOffline() throws Exception {
    TestChain.Sealed oldSeal = TestChain.seal(EPOCH1, 7, 5, TestChain.fourShards(1,
        TestChain.TIMESTAMP));
    TestChain.Sealed newSeal = TestChain.seal(EPOCH2, 7, 11, TestChain.fourShards(1,
        TestChain.TIMESTAMP));
    TrustBaseEntry epoch2 = EPOCH1.trustBaseThrough(EPOCH2).getTip().get();

    TrustBaseHistory history = EPOCH1.trustBase();
    HttpTrustBaseSource source = new HttpTrustBaseSource(this.server.url("/").toString(), null,
        true, null, SdkVersionHeader.of("wallet/1"));

    // the token of epoch 1 verifies with no network at all
    assertEquals(VerificationStatus.OK, verify(history, oldSeal.certificate("00")).getStatus());
    assertEquals(0, this.server.getRequestCount());

    // a token of epoch 2: the typed, retryable failure; the signatures were not even looked at
    UnicityCertificateVerificationResult unknown = verify(history, newSeal.certificate("00"));
    assertEquals(UnicityCertificateFailure.NO_TRUST_BASE_ENTRY, unknown.getFailure());
    assertNotNull(NoTrustBaseEntryResult.find(unknown));

    // one update over HTTP, then at most one more verification
    this.server.enqueue(pageOfEpoch2(epoch2));
    UnicityCertificateVerificationResult result = TrustBaseUpdater.verifyWithUpdate(
        TrustBaseUpdateTarget.of(history), List.of(source),
        TrustBaseUpdateOptions.defaults().withBackoffMs(0),
        () -> verify(history, newSeal.certificate("00")),
        r -> NoTrustBaseEntryResult.find(r) != null);
    assertEquals(VerificationStatus.OK, result.getStatus());
    assertEquals(1, this.server.getRequestCount(), "exactly one GET");
    RecordedRequest request = this.server.takeRequest(1, TimeUnit.SECONDS);
    assertEquals("/api/v1/trustbases", request.getRequestUrl().encodedPath());
    assertEquals("2", request.getRequestUrl().queryParameter("from"),
        "only the epoch after the held tip is asked for");
    assertEquals("wallet/1", request.getHeader("X-SDK-Version"));

    // the old token still verifies, and so does the new one: no further request
    assertEquals(VerificationStatus.OK, verify(history, oldSeal.certificate("00")).getStatus());
    assertEquals(VerificationStatus.OK, verify(history, newSeal.certificate("00")).getStatus());
    assertEquals(1, this.server.getRequestCount());
  }

  @Test
  void aSessionFetchesSavesAndAfterARestartVerifiesTheNewEpochOffline() throws Exception {
    TestChain.Sealed newSeal = TestChain.seal(EPOCH2, 7, 11, TestChain.fourShards(1,
        TestChain.TIMESTAMP));
    TrustBaseEntry epoch2 = EPOCH1.trustBaseThrough(EPOCH2).getTip().get();
    ReleasePin pin = ReleasePin.fromBundle(EPOCH1.trustBase().toBundle());
    MemoryTrustBaseStore store = new MemoryTrustBaseStore();
    TrustBaseSessionOptions options = TrustBaseSessionOptions.of(Runnable::run);

    TrustBaseSession first = TrustBaseSession.openPrivate("wallet", pin, store, options).get()
        .getSession();
    assertEquals(UnicityCertificateFailure.NO_TRUST_BASE_ENTRY, verifyView(first,
        newSeal.certificate("00")).getFailure());

    this.server.enqueue(pageOfEpoch2(epoch2));
    HttpTrustBaseSource source = new HttpTrustBaseSource(this.server.url("/").toString(), null,
        true, null, SdkVersionHeader.sdkDefault());
    assertEquals(1, first.refresh(List.of(source),
        TrustBaseUpdateOptions.defaults().withBackoffMs(0)).get().intValue());
    assertEquals(VerificationStatus.OK,
        verifyView(first, newSeal.certificate("00")).getStatus());
    assertFalse(first.isDirty(), "the refresh saved the new epoch");
    assertArrayEquals(first.exportBundle(), store.get(TrustBaseCacheKey.of(pin.getNetworkId(),
        pin.getGenesisEntryId(), "wallet")));

    // a restart: a new session over the same store, and no network
    int requests = this.server.getRequestCount();
    TrustBaseSession restarted = TrustBaseSession.openPrivate("wallet", pin, store, options).get()
        .getSession();
    assertEquals(2, restarted.history().getTip().get().getEpoch());
    assertEquals(VerificationStatus.OK,
        verifyView(restarted, newSeal.certificate("00")).getStatus());
    assertEquals(requests, this.server.getRequestCount());
    assertNull(restarted.getConflict());
    assertTrue(restarted.history().entryForEpoch(1).isPresent());
  }

  private static UnicityCertificateVerificationResult verifyView(TrustBaseSession session,
      UnicityCertificate certificate) {
    OperationUnicityCertificateVerifier verifier = (OperationUnicityCertificateVerifier)
        new UnicityCertificateVerifier().beginOperation(null);
    return verifier.verifyCertificate(session.history(), certificate);
  }
}
