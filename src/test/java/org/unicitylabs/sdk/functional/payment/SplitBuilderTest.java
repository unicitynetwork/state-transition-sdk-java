package org.unicitylabs.sdk.functional.payment;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.unicitylabs.sdk.StateTransitionClient;
import org.unicitylabs.sdk.TestAggregatorClient;
import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.mtree.plain.SparseMerkleTree;
import org.unicitylabs.sdk.mtree.plain.SparseMerkleTreeRootNode;
import org.unicitylabs.sdk.mtree.sum.SparseMerkleSumTree;
import org.unicitylabs.sdk.mtree.sum.SparseMerkleSumTreeRootNode;
import org.unicitylabs.sdk.payment.SplitPaymentData;
import org.unicitylabs.sdk.payment.SplitReason;
import org.unicitylabs.sdk.payment.SplitReasonProof;
import org.unicitylabs.sdk.payment.SplitResult;
import org.unicitylabs.sdk.payment.TokenSplit;
import org.unicitylabs.sdk.payment.asset.Asset;
import org.unicitylabs.sdk.payment.asset.AssetId;
import org.unicitylabs.sdk.predicate.builtin.PayToPublicKeyPredicate;
import org.unicitylabs.sdk.predicate.builtin.PayToPublicKeyPredicateUnlockScript;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.transaction.Address;
import org.unicitylabs.sdk.transaction.Token;
import org.unicitylabs.sdk.transaction.TokenId;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;
import org.unicitylabs.sdk.utils.TokenUtils;

/**
 * Functional tests for minting and splitting tokens with proof verification.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class SplitBuilderTest {

  private StateTransitionClient client;
  private RootTrustBase trustBase;
  private PredicateVerifierService predicateVerifier;
  private Asset asset1;
  private Asset asset2;
  private Token splitToken;

  @BeforeAll
  public void setupFixture() throws Exception {
    TestAggregatorClient aggregatorClient = TestAggregatorClient.create();
    this.trustBase = aggregatorClient.getTrustBase();

    this.client = new StateTransitionClient(aggregatorClient);
    this.predicateVerifier = PredicateVerifierService.create(this.trustBase);

    SigningService signingService = SigningService.generate();
    PayToPublicKeyPredicate ownerPredicate = PayToPublicKeyPredicate.fromSigningService(signingService);

    this.asset1 = new Asset(new AssetId("ASSET_1".getBytes(StandardCharsets.UTF_8)), BigInteger.valueOf(500));
    this.asset2 = new Asset(new AssetId("ASSET_2".getBytes(StandardCharsets.UTF_8)), BigInteger.valueOf(500));

    this.splitToken = createSplitToken(
        this.client,
        signingService,
        ownerPredicate,
        Set.of(this.asset1, this.asset2),
        Set.of(this.asset1, this.asset2)
    );
  }

  /**
   * Verifies end-to-end mint, split, burn and validation flow.
   *
   * @throws Exception when async client interactions fail
   */
  @Test
  public void verifyTokenSplitIsSuccessful() throws Exception {
    SigningService signingService = SigningService.generate();
    PayToPublicKeyPredicate predicate = PayToPublicKeyPredicate.fromSigningService(signingService);

    Set<Asset> assets = Set.of(this.asset1, this.asset2);
    TestPaymentData paymentData = new TestPaymentData(assets);

    Token token = TokenUtils.mintToken(
        this.client,
        this.trustBase,
        this.predicateVerifier,
        Address.fromPredicate(predicate),
        paymentData.encode()
    );

    IllegalArgumentException exception = Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> TokenSplit.split(
            token,
            predicate,
            TestPaymentData::decode,
            Map.of(TokenId.generate(), Set.of(this.asset1))
        )
    );

    Assertions.assertEquals("Token and split tokens asset counts differ.", exception.getMessage());

    exception = Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> TokenSplit.split(
            token,
            predicate,
            TestPaymentData::decode,
            Map.of(
                TokenId.generate(),
                Set.of(this.asset1, new Asset(this.asset2.getId(), BigInteger.valueOf(400)))
            )
        )
    );

    Assertions.assertEquals("Token contained 500 AssetId{bytes=41535345545f32} assets, but tree has 400",
        exception.getMessage());

    exception = Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> TokenSplit.split(
            token,
            predicate,
            TestPaymentData::decode,
            Map.of(
                TokenId.generate(),
                Set.of(this.asset1, new Asset(this.asset2.getId(), BigInteger.valueOf(1500)))
            )
        )
    );

    Assertions.assertEquals("Token contained 500 AssetId{bytes=41535345545f32} assets, but tree has 1500",
        exception.getMessage());

    Map<TokenId, Set<Asset>> splitTokens = Map.of(
        TokenId.generate(), Set.of(this.asset1),
        TokenId.generate(), Set.of(this.asset2)
    );

    SplitResult result = TokenSplit.split(token, predicate, TestPaymentData::decode, splitTokens);

    Token burnToken = TokenUtils.transferToken(
        this.client,
        this.trustBase,
        this.predicateVerifier,
        token,
        result.getBurnTransaction(),
        PayToPublicKeyPredicateUnlockScript.create(result.getBurnTransaction(), signingService)
    );

    for (Entry<TokenId, Set<Asset>> entry : splitTokens.entrySet()) {
      List<SplitReasonProof> proofs = result.getProofs().get(entry.getKey());
      Assertions.assertNotNull(proofs);

      Token splitToken = TokenUtils.mintToken(
          this.client,
          this.trustBase,
          this.predicateVerifier,
          entry.getKey(),
          Address.fromPredicate(predicate),
          new TestSplitPaymentData(
              entry.getValue(),
              SplitReason.create(
                  burnToken,
                  proofs
              )
          ).encode()
      );

      Assertions.assertEquals(
          VerificationStatus.OK,
          splitToken.verify(this.trustBase, this.predicateVerifier).getStatus()
      );
      Assertions.assertEquals(VerificationStatus.OK,
          TokenSplit.verify(
              Token.fromCbor(splitToken.toCbor()),
              TestSplitPaymentData::decode,
              this.trustBase,
              this.predicateVerifier
          ).getStatus());
    }
  }

  @Test
  public void verifyFailsWhenTokenIsNull() {
    assertNpe("Token cannot be null",
        () -> TokenSplit.verify(null, TestSplitPaymentData::decode, this.trustBase, this.predicateVerifier));
  }

  @Test
  public void verifyFailsWhenDeserializerIsNull() {
    assertNpe("Payment data deserializer cannot be null",
        () -> TokenSplit.verify(this.splitToken, null, this.trustBase, this.predicateVerifier));
  }

  @Test
  public void verifyFailsWhenTrustBaseIsNull() {
    assertNpe("Trust base cannot be null",
        () -> TokenSplit.verify(this.splitToken, TestSplitPaymentData::decode, null, this.predicateVerifier));
  }

  @Test
  public void verifyFailsWhenPredicateVerifierIsNull() {
    assertNpe("Predicate verifier cannot be null",
        () -> TokenSplit.verify(this.splitToken, TestSplitPaymentData::decode, this.trustBase, null));
  }

  @Test
  public void verifyFailsWhenAssetsAreMissing() {
    VerificationResult<VerificationStatus> result = verifyWithData(
        this.splitToken,
        new TestSplitPaymentData(null, TestSplitPaymentData.decode(this.splitToken.getGenesis().getData()).getReason())
    );

    assertFailWithMessage(result, "Assets data is missing.");
  }

  @Test
  public void verifyFailsWhenReasonIsMissing() {
    VerificationResult<VerificationStatus> result = verifyWithData(
        this.splitToken,
        new TestSplitPaymentData(Set.of(this.asset1), null)
    );

    assertFailWithMessage(result, "Reason is missing.");
  }

  @Test
  public void verifyFailsWhenBurnTokenVerificationFails() {
    List<byte[]> payloadData = CborDeserializer.decodeArray(this.splitToken.getGenesis().getData());
    List<byte[]> reasonData = CborDeserializer.decodeArray(payloadData.get(1));
    List<byte[]> reasonTokenData = CborDeserializer.decodeArray(reasonData.get(0));
    List<byte[]> transactions = CborDeserializer.decodeArray(reasonTokenData.get(1));
    List<byte[]> certifiedTransfer = CborDeserializer.decodeArray(transactions.get(0));
    List<byte[]> transfer = CborDeserializer.decodeArray(certifiedTransfer.get(0));

    // Corrupt burn transaction recipient address so burn token verification fails.
    byte[] invalidRecipient = new byte[32];
    invalidRecipient[0] = 1;
    transfer.set(2, Address.fromBytes(invalidRecipient).toCbor());

    certifiedTransfer.set(0, encodeArray(transfer));
    transactions.set(0, encodeArray(certifiedTransfer));
    reasonTokenData.set(1, encodeArray(transactions));
    reasonData.set(0, encodeArray(reasonTokenData));
    payloadData.set(1, encodeArray(reasonData));
    byte[] payload = encodeArray(payloadData);

    VerificationResult<VerificationStatus> result = verifyWithPayload(this.splitToken, payload);
    assertFailWithMessage(result, "Burn token verification failed.");
    Assertions.assertFalse(result.getResults().isEmpty());
  }

  @Test
  public void verifyFailsWhenAssetAndProofCountsDiffer() {
    VerificationResult<VerificationStatus> result = verifyWithData(
        this.splitToken,
        new TestSplitPaymentData(Set.of(this.asset1),
            TestSplitPaymentData.decode(this.splitToken.getGenesis().getData()).getReason())
    );

    assertFailWithMessage(result, "Total amount of assets differ in token and proofs.");
  }

  @Test
  public void verifyFailsWhenAssetEntryIsNull() {
    Set<Asset> invalidAssets = new NonUniqueAssetSet(Arrays.asList(null, this.asset1));
    VerificationResult<VerificationStatus> result = verifyWithData(
        this.splitToken,
        new TestSplitPaymentData(invalidAssets,
            TestSplitPaymentData.decode(this.splitToken.getGenesis().getData()).getReason())
    );

    assertFailWithMessage(result, "Asset data is missing.");
  }

  @Test
  public void verifyFailsWhenAssetIdsAreDuplicated() {
    Asset duplicate = new Asset(this.asset1.getId(), this.asset1.getValue().add(BigInteger.ONE));
    Set<Asset> duplicatedAssets = new NonUniqueAssetSet(List.of(this.asset1, duplicate));

    VerificationResult<VerificationStatus> result = verifyWithData(
        this.splitToken,
        new TestSplitPaymentData(duplicatedAssets,
            TestSplitPaymentData.decode(this.splitToken.getGenesis().getData()).getReason())
    );

    assertFailWithMessage(result,
        String.format("Duplicate asset id %s found in asset data.", this.asset1.getId()));
  }

  @Test
  public void verifyFailsWhenAggregationPathVerificationFails() throws Exception {
    SplitPaymentData splitPaymentData = TestSplitPaymentData.decode(this.splitToken.getGenesis().getData());
    SplitReason splitReason = splitPaymentData.getReason();
    List<SplitReasonProof> proofs = new ArrayList<>(splitReason.getProofs());
    SplitReasonProof proof = proofs.get(0);
    SparseMerkleTreeRootNode aggregationRoot = new SparseMerkleTree(HashAlgorithm.SHA256).calculateRoot();

    proofs.set(
        0,
        SplitReasonProof.create(
            proof.getAssetId(),
            aggregationRoot.getPath(proof.getAssetId().toBitString().toBigInteger()),
            proof.getAssetTreePath()
        )
    );

    byte[] payload = new TestSplitPaymentData(
        splitPaymentData.getAssets(),
        SplitReason.create(splitReason.getToken(), proofs)
    ).encode();

    VerificationResult<VerificationStatus> result = verifyWithPayload(this.splitToken, payload);

    assertFailWithMessage(result,
        String.format("Aggregation path verification failed for asset: %s", proof.getAssetId()));
  }

  @Test
  public void verifyFailsWhenAssetTreePathVerificationFails() throws Exception {
    SplitPaymentData splitPaymentData = TestSplitPaymentData.decode(this.splitToken.getGenesis().getData());
    SplitReason splitReason = splitPaymentData.getReason();
    List<SplitReasonProof> proofs = new ArrayList<>(splitReason.getProofs());
    SplitReasonProof proof = proofs.get(0);

    SparseMerkleSumTreeRootNode assetTreeRoot = new SparseMerkleSumTree(HashAlgorithm.SHA256).calculateRoot();

    SplitReasonProof mutated = SplitReasonProof.create(
        proof.getAssetId(),
        proof.getAggregationPath(),
        assetTreeRoot.getPath(this.splitToken.getId().toBitString().toBigInteger())
    );
    proofs.set(0, mutated);

    byte[] payload = new TestSplitPaymentData(
        splitPaymentData.getAssets(),
        SplitReason.create(splitReason.getToken(), proofs)
    ).encode();

    VerificationResult<VerificationStatus> result = verifyWithPayload(this.splitToken, payload);

    assertFailWithMessage(result,
        String.format("Asset tree path verification failed for token:  %s", this.splitToken.getId()));
  }

  @Test
  public void verifyFailsWhenProofsUseDifferentAssetTrees() throws Exception {
    SplitPaymentData splitPaymentData = TestSplitPaymentData.decode(this.splitToken.getGenesis().getData());
    SplitReason splitReason = splitPaymentData.getReason();
    List<SplitReasonProof> proofs = new ArrayList<>(splitReason.getProofs());
    SplitReasonProof secondProof = proofs.get(1);

    SparseMerkleTree aggregationTree = new SparseMerkleTree(HashAlgorithm.SHA256);
    aggregationTree.addLeaf(
        secondProof.getAssetId().toBitString().toBigInteger(),
        secondProof.getAssetTreePath().getRootHash().getImprint()
    );
    SparseMerkleTreeRootNode otherAggregationRoot = aggregationTree.calculateRoot();

    SplitReasonProof mutated = SplitReasonProof.create(
        secondProof.getAssetId(),
        otherAggregationRoot.getPath(secondProof.getAssetId().toBitString().toBigInteger()),
        secondProof.getAssetTreePath()
    );
    proofs.set(1, mutated);

    byte[] payload = new TestSplitPaymentData(
        splitPaymentData.getAssets(),
        SplitReason.create(splitReason.getToken(), proofs)
    ).encode();

    VerificationResult<VerificationStatus> result = verifyWithPayload(this.splitToken, payload);
    assertFailWithMessage(result, "Current proof is not derived from the same asset tree as other proofs.");
  }

  @Test
  public void verifyFailsWhenAssetTreeRootDoesNotMatchAggregationLeaf() throws Exception {
    SplitPaymentData splitPaymentData = TestSplitPaymentData.decode(this.splitToken.getGenesis().getData());
    SplitReason splitReason = splitPaymentData.getReason();
    List<SplitReasonProof> proofs = new ArrayList<>(splitReason.getProofs());
    SplitReasonProof proof = proofs.get(0);

    SparseMerkleSumTree assetTree = new SparseMerkleSumTree(HashAlgorithm.SHA256);
    assetTree.addLeaf(
        this.splitToken.getId().toBitString().toBigInteger(),
        new SparseMerkleSumTree.LeafValue(
            proof.getAssetId().getBytes(),
            proof.getAssetTreePath().getSteps().get(0).getValue().add(BigInteger.ONE)
        )
    );

    SplitReasonProof mutated = SplitReasonProof.create(
        proof.getAssetId(),
        proof.getAggregationPath(),
        assetTree.calculateRoot().getPath(this.splitToken.getId().toBitString().toBigInteger())
    );
    proofs.set(0, mutated);

    byte[] payload = new TestSplitPaymentData(
        splitPaymentData.getAssets(),
        SplitReason.create(splitReason.getToken(), proofs)
    ).encode();

    VerificationResult<VerificationStatus> result = verifyWithPayload(this.splitToken, payload);
    assertFailWithMessage(result, "Asset tree root does not match aggregation path leaf.");
  }

  @Test
  public void verifyFailsWhenProofAssetIdIsMissingFromAssetData() {
    SplitPaymentData splitPaymentData = TestSplitPaymentData.decode(this.splitToken.getGenesis().getData());
    SplitReason splitReason = splitPaymentData.getReason();
    List<SplitReasonProof> proofs = List.of(splitReason.getProofs().get(0));
    Set<Asset> assets = splitPaymentData.getAssets().stream()
        .filter(asset -> !asset.getId().equals(proofs.get(0).getAssetId()))
        .collect(Collectors.toSet());
    byte[] payload = new TestSplitPaymentData(
        assets,
        SplitReason.create(splitReason.getToken(), proofs)
    ).encode();

    VerificationResult<VerificationStatus> result = verifyWithPayload(this.splitToken, payload);
    assertFailWithMessage(result,
        String.format("Asset id %s not found in asset data.", proofs.get(0).getAssetId()));
  }

  @Test
  public void verifyFailsWhenAssetAmountDoesNotMatchLeafAmount() {
    SplitPaymentData splitPaymentData = TestSplitPaymentData.decode(this.splitToken.getGenesis().getData());
    SplitReason splitReason = splitPaymentData.getReason();
    List<Asset> assets = new ArrayList<>(splitPaymentData.getAssets());
    Asset asset = assets.get(0);
    Asset modified = new Asset(asset.getId(), asset.getValue().add(BigInteger.ONE));
    assets.set(0, modified);

    byte[] payload = new TestSplitPaymentData(Set.copyOf(assets), splitReason).encode();

    VerificationResult<VerificationStatus> result = verifyWithPayload(this.splitToken, payload);
    assertFailWithMessage(result,
        String.format("Asset amount for asset id %s does not match asset tree leaf.", asset.getId()));
  }

  @Test
  public void verifyFailsWhenAggregationRootDoesNotMatchBurnPredicate() throws Exception {
    SplitPaymentData splitPaymentData = TestSplitPaymentData.decode(this.splitToken.getGenesis().getData());
    SplitReason splitReason = splitPaymentData.getReason();
    List<SplitReasonProof> proofs = new ArrayList<>(splitReason.getProofs());
    SplitReasonProof proof = proofs.get(0);

    SparseMerkleTree aggregationTree = new SparseMerkleTree(HashAlgorithm.SHA256);
    aggregationTree.addLeaf(
        proof.getAssetId().toBitString().toBigInteger(),
        proof.getAssetTreePath().getRootHash().getImprint()
    );
    SparseMerkleTreeRootNode aggregationRoot = aggregationTree.calculateRoot();

    SplitReasonProof mutated = SplitReasonProof.create(
        proof.getAssetId(),
        aggregationRoot.getPath(proof.getAssetId().toBitString().toBigInteger()),
        proof.getAssetTreePath()
    );
    proofs.set(0, mutated);

    byte[] payload = new TestSplitPaymentData(
        splitPaymentData.getAssets(),
        SplitReason.create(splitReason.getToken(), proofs)
    ).encode();

    VerificationResult<VerificationStatus> result = verifyWithPayload(this.splitToken, payload);
    assertFailWithMessage(result, "Aggregation path root does not match burn predicate.");
  }

  private Token createSplitToken(
      StateTransitionClient client,
      SigningService signingService,
      PayToPublicKeyPredicate ownerPredicate,
      Set<Asset> sourceAssets,
      Set<Asset> outputAssets
  ) throws Exception {
    Token sourceToken = TokenUtils.mintToken(
        client,
        this.trustBase,
        this.predicateVerifier,
        Address.fromPredicate(ownerPredicate),
        new TestPaymentData(sourceAssets).encode()
    );

    TokenId outputTokenId = TokenId.generate();
    SplitResult split = TokenSplit.split(
        sourceToken,
        ownerPredicate,
        TestPaymentData::decode,
        Map.of(outputTokenId, outputAssets)
    );

    Token burnToken = TokenUtils.transferToken(
        client,
        this.trustBase,
        this.predicateVerifier,
        sourceToken,
        split.getBurnTransaction(),
        PayToPublicKeyPredicateUnlockScript.create(split.getBurnTransaction(), signingService)
    );

    return TokenUtils.mintToken(
        client,
        this.trustBase,
        this.predicateVerifier,
        outputTokenId,
        Address.fromPredicate(ownerPredicate),
        new TestSplitPaymentData(
            outputAssets,
            SplitReason.create(burnToken, split.getProofs().get(outputTokenId))
        ).encode()
    );
  }

  private VerificationResult<VerificationStatus> verify(Token token) {
    return TokenSplit.verify(
        Token.fromCbor(token.toCbor()),
        TestSplitPaymentData::decode,
        this.trustBase,
        this.predicateVerifier
    );
  }

  private VerificationResult<VerificationStatus> verifyWithData(Token token, SplitPaymentData paymentData) {
    return TokenSplit.verify(
        Token.fromCbor(token.toCbor()),
        ignored -> paymentData,
        this.trustBase,
        this.predicateVerifier
    );
  }

  private VerificationResult<VerificationStatus> verifyWithPayload(Token token, byte[] payload) {
    return this.verify(withPayload(token, payload));
  }

  private Token withPayload(Token token, byte[] payload) {
    List<byte[]> tokenData = CborDeserializer.decodeArray(token.toCbor());
    List<byte[]> genesis = CborDeserializer.decodeArray(tokenData.get(0));
    List<byte[]> mint = CborDeserializer.decodeArray(genesis.get(0));
    List<byte[]> aux = CborDeserializer.decodeArray(mint.get(2));

    aux.set(1, CborSerializer.encodeByteString(payload));
    mint.set(2, encodeArray(aux));
    genesis.set(0, encodeArray(mint));
    tokenData.set(0, encodeArray(genesis));

    return Token.fromCbor(encodeArray(tokenData));
  }

  private void assertFailWithMessage(VerificationResult<VerificationStatus> result, String message) {
    Assertions.assertEquals(VerificationStatus.FAIL, result.getStatus());
    Assertions.assertEquals(message, result.getMessage());
  }

  private void assertNpe(String message, Runnable callback) {
    NullPointerException error = Assertions.assertThrows(NullPointerException.class, callback::run);
    Assertions.assertEquals(message, error.getMessage());
  }

  private byte[] encodeArray(List<byte[]> data) {
    return CborSerializer.encodeArray(data.toArray(new byte[0][]));
  }

  private static final class NonUniqueAssetSet extends AbstractSet<Asset> {

    private final List<Asset> items;

    private NonUniqueAssetSet(List<Asset> items) {
      this.items = new ArrayList<>(items);
    }

    @Override
    public Iterator<Asset> iterator() {
      return this.items.iterator();
    }

    @Override
    public int size() {
      return this.items.size();
    }
  }
}
