package org.unicitylabs.sdk.functional.payment;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.unicitylabs.sdk.StateTransitionClient;
import org.unicitylabs.sdk.TestAggregatorClient;
import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.crypto.hash.HashAlgorithm;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.payment.PaymentData;
import org.unicitylabs.sdk.payment.SplitMintJustification;
import org.unicitylabs.sdk.payment.SplitMintJustificationVerifier;
import org.unicitylabs.sdk.payment.SplitAssetProof;
import org.unicitylabs.sdk.payment.SplitResult;
import org.unicitylabs.sdk.payment.TokenSplit;
import org.unicitylabs.sdk.payment.asset.Asset;
import org.unicitylabs.sdk.payment.asset.AssetId;
import org.unicitylabs.sdk.predicate.builtin.PayToPublicKeyPredicate;
import org.unicitylabs.sdk.predicate.builtin.PayToPublicKeyPredicateUnlockScript;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.serializer.cbor.CborDeserializer;
import org.unicitylabs.sdk.serializer.cbor.CborSerializer;
import org.unicitylabs.sdk.smt.plain.SparseMerkleTree;
import org.unicitylabs.sdk.smt.plain.SparseMerkleTreeRootNode;
import org.unicitylabs.sdk.smt.sum.SparseMerkleSumTree;
import org.unicitylabs.sdk.smt.sum.SparseMerkleSumTreeRootNode;
import org.unicitylabs.sdk.transaction.Token;
import org.unicitylabs.sdk.transaction.TokenId;
import org.unicitylabs.sdk.transaction.TokenType;
import org.unicitylabs.sdk.transaction.verification.MintJustificationVerifierService;
import org.unicitylabs.sdk.util.verification.VerificationResult;
import org.unicitylabs.sdk.util.verification.VerificationStatus;
import org.unicitylabs.sdk.utils.TokenUtils;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Unit tests for the failure branches of {@link SplitMintJustificationVerifier}. Each test drives
 * one specific reject path inside the verifier by handing it a corrupted or mismatched fixture.
 * The verifier is invoked directly; integration through the dispatcher and {@link Token#verify}
 * is covered by {@link SplitBuilderTest}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class SplitMintJustificationVerifierTest {

  private RootTrustBase trustBase;
  private PredicateVerifierService predicateVerifier;
  private MintJustificationVerifierService mintJustificationVerifier;
  private SplitMintJustificationVerifier splitMintJustificationVerifier;
  private Asset asset1;
  private Asset asset2;
  private Token splitToken;
  private SplitMintJustification splitJustification;

  @BeforeAll
  public void setupFixture() throws Exception {
    TestAggregatorClient aggregatorClient = TestAggregatorClient.create();
    this.trustBase = aggregatorClient.getTrustBase();

    StateTransitionClient client = new StateTransitionClient(aggregatorClient);
    this.predicateVerifier = PredicateVerifierService.create(this.trustBase);

    this.splitMintJustificationVerifier = new SplitMintJustificationVerifier(
            this.trustBase, this.predicateVerifier, TestPaymentData::decode);
    this.mintJustificationVerifier = new MintJustificationVerifierService();
    this.mintJustificationVerifier.register(this.splitMintJustificationVerifier);

    SigningService signingService = SigningService.generate();
    PayToPublicKeyPredicate ownerPredicate = PayToPublicKeyPredicate.fromSigningService(signingService);

    this.asset1 = new Asset(new AssetId("ASSET_1".getBytes(StandardCharsets.UTF_8)), BigInteger.valueOf(500));
    this.asset2 = new Asset(new AssetId("ASSET_2".getBytes(StandardCharsets.UTF_8)), BigInteger.valueOf(500));

    Set<Asset> assets = Set.of(this.asset1, this.asset2);

    Token sourceToken = TokenUtils.mintToken(
            client,
            this.trustBase,
            this.predicateVerifier,
            this.mintJustificationVerifier,
            ownerPredicate,
            null,
            new TestPaymentData(assets).encode()
    );

    TokenId outputTokenId = TokenId.generate();
    SplitResult split = TokenSplit.split(
            sourceToken,
            TestPaymentData::decode,
            Map.of(outputTokenId, assets)
    );

    Token burnToken = TokenUtils.transferToken(
            client,
            this.trustBase,
            this.predicateVerifier,
            sourceToken,
            split.getBurnTransaction(),
            PayToPublicKeyPredicateUnlockScript.create(split.getBurnTransaction(), signingService)
    );

    this.splitJustification = SplitMintJustification.create(
            burnToken,
            new LinkedHashSet<>(split.getProofs().get(outputTokenId))
    );

    this.splitToken = TokenUtils.mintToken(
            client,
            this.trustBase,
            this.predicateVerifier,
            this.mintJustificationVerifier,
            outputTokenId,
            TokenType.generate(),
            ownerPredicate,
            this.splitJustification.toCbor(),
            new TestPaymentData(assets).encode()
    );
  }

  @Test
  public void verifyFailsWhenTransactionIsNull() {
    assertNpe("transaction cannot be null",
            () -> this.splitMintJustificationVerifier.verify(null, this.mintJustificationVerifier));
  }

  @Test
  public void verifyFailsWhenDeserializerIsNull() {
    assertNpe("decodePaymentData cannot be null",
            () -> new SplitMintJustificationVerifier(this.trustBase, this.predicateVerifier, null));
  }

  @Test
  public void verifyFailsWhenTrustBaseIsNull() {
    assertNpe("trustBase cannot be null",
            () -> new SplitMintJustificationVerifier(null, this.predicateVerifier, TestPaymentData::decode));
  }

  @Test
  public void verifyFailsWhenPredicateVerifierIsNull() {
    assertNpe("predicateVerifier cannot be null",
            () -> new SplitMintJustificationVerifier(this.trustBase, null, TestPaymentData::decode));
  }

  @Test
  public void verifyFailsWhenJustificationIsMissing() {
    VerificationResult<VerificationStatus> result = verifyWith(null, originalDataBytes());
    assertFailWithMessage(result, "Transaction has no justification.");
  }

  @Test
  public void verifyFailsWhenAssetsAreMissing() {
    VerificationResult<VerificationStatus> result = verifyWithPaymentData(
            this.splitJustification.toCbor(), paymentDataOf(null));
    assertFailWithMessage(result, "Assets data is missing.");
  }

  @Test
  public void verifyFailsWhenBurnTokenVerificationFails() {
    byte[] corruptedJustification = corruptBurnTokenInJustification(this.splitJustification.toCbor());

    VerificationResult<VerificationStatus> result = verifyWith(corruptedJustification, originalDataBytes());
    assertFailWithMessage(result, "Burn token verification failed.");
    Assertions.assertFalse(result.getResults().isEmpty());
  }

  @Test
  public void verifyFailsWhenAssetAndProofCountsDiffer() {
    byte[] data = new TestPaymentData(Set.of(this.asset1)).encode();

    VerificationResult<VerificationStatus> result = verifyWith(this.splitJustification.toCbor(), data);
    assertFailWithMessage(result, "Total amount of assets differ in token and proofs.");
  }

  @Test
  public void verifyFailsWhenAssetEntryIsNull() {
    Set<Asset> invalidAssets = new NonUniqueAssetSet(Arrays.asList(null, this.asset1));

    VerificationResult<VerificationStatus> result = verifyWithPaymentData(
            this.splitJustification.toCbor(), paymentDataOf(invalidAssets));
    assertFailWithMessage(result, "Asset data is missing.");
  }

  @Test
  public void verifyFailsWhenAssetIdsAreDuplicated() {
    Asset duplicate = new Asset(this.asset1.getId(), this.asset1.getValue().add(BigInteger.ONE));
    Set<Asset> duplicatedAssets = new NonUniqueAssetSet(List.of(this.asset1, duplicate));

    VerificationResult<VerificationStatus> result = verifyWithPaymentData(
            this.splitJustification.toCbor(), paymentDataOf(duplicatedAssets));
    assertFailWithMessage(result,
            String.format("Duplicate asset id %s found in asset data.", this.asset1.getId()));
  }

  @Test
  public void verifyFailsWhenAggregationPathVerificationFails() throws Exception {
    List<SplitAssetProof> proofs = new ArrayList<>(this.splitJustification.getProofs());
    SplitAssetProof proof = proofs.get(0);
    SparseMerkleTreeRootNode aggregationRoot = new SparseMerkleTree(HashAlgorithm.SHA256).calculateRoot();

    proofs.set(
            0,
            SplitAssetProof.create(
                    proof.getAssetId(),
                    aggregationRoot.getPath(proof.getAssetId().toBitString().toBigInteger()),
                    proof.getAssetTreePath()
            )
    );

    SplitMintJustification mutated = SplitMintJustification.create(
            this.splitJustification.getToken(), new LinkedHashSet<>(proofs));

    VerificationResult<VerificationStatus> result = verifyWith(mutated.toCbor(), originalDataBytes());
    assertFailWithMessage(result,
            String.format("Aggregation path verification failed for asset: %s", proof.getAssetId()));
  }

  @Test
  public void verifyFailsWhenAssetTreePathVerificationFails() throws Exception {
    List<SplitAssetProof> proofs = new ArrayList<>(this.splitJustification.getProofs());
    SplitAssetProof proof = proofs.get(0);

    SparseMerkleSumTreeRootNode assetTreeRoot = new SparseMerkleSumTree(HashAlgorithm.SHA256).calculateRoot();

    SplitAssetProof mutatedProof = SplitAssetProof.create(
            proof.getAssetId(),
            proof.getAggregationPath(),
            assetTreeRoot.getPath(this.splitToken.getId().toBitString().toBigInteger())
    );
    proofs.set(0, mutatedProof);

    SplitMintJustification mutated = SplitMintJustification.create(
            this.splitJustification.getToken(), new LinkedHashSet<>(proofs));

    VerificationResult<VerificationStatus> result = verifyWith(mutated.toCbor(), originalDataBytes());
    assertFailWithMessage(result,
            String.format("Asset tree path verification failed for token:  %s", this.splitToken.getId()));
  }

  @Test
  public void verifyFailsWhenProofsUseDifferentAssetTrees() throws Exception {
    List<SplitAssetProof> proofs = new ArrayList<>(
            SplitMintJustification.fromCbor(this.splitJustification.toCbor()).getProofs());
    SplitAssetProof lastProof = proofs.get(proofs.size() - 1);

    SparseMerkleTree aggregationTree = new SparseMerkleTree(HashAlgorithm.SHA256);
    aggregationTree.addLeaf(
            lastProof.getAssetId().toBitString().toBigInteger(),
            lastProof.getAssetTreePath().getRootHash().getImprint()
    );
    SparseMerkleTreeRootNode otherAggregationRoot = aggregationTree.calculateRoot();

    proofs.set(proofs.size() - 1, SplitAssetProof.create(
            lastProof.getAssetId(),
            otherAggregationRoot.getPath(lastProof.getAssetId().toBitString().toBigInteger()),
            lastProof.getAssetTreePath()
    ));

    SplitMintJustification mutated = SplitMintJustification.create(
            this.splitJustification.getToken(), new LinkedHashSet<>(proofs));

    VerificationResult<VerificationStatus> result = verifyWith(mutated.toCbor(), originalDataBytes());
    assertFailWithMessage(result, "Current proof is not derived from the same asset tree as other proofs.");
  }

  @Test
  public void verifyFailsWhenAssetTreeRootDoesNotMatchAggregationLeaf() throws Exception {
    List<SplitAssetProof> proofs = new ArrayList<>(this.splitJustification.getProofs());
    SplitAssetProof proof = proofs.get(0);

    SparseMerkleSumTree assetTree = new SparseMerkleSumTree(HashAlgorithm.SHA256);
    assetTree.addLeaf(
            this.splitToken.getId().toBitString().toBigInteger(),
            new SparseMerkleSumTree.LeafValue(
                    proof.getAssetId().getBytes(),
                    proof.getAssetTreePath().getSteps().get(0).getValue().add(BigInteger.ONE)
            )
    );

    SplitAssetProof mutatedProof = SplitAssetProof.create(
            proof.getAssetId(),
            proof.getAggregationPath(),
            assetTree.calculateRoot().getPath(this.splitToken.getId().toBitString().toBigInteger())
    );
    proofs.set(0, mutatedProof);

    SplitMintJustification mutated = SplitMintJustification.create(
            this.splitJustification.getToken(), new LinkedHashSet<>(proofs));

    VerificationResult<VerificationStatus> result = verifyWith(mutated.toCbor(), originalDataBytes());
    assertFailWithMessage(result, "Asset tree root does not match aggregation path leaf.");
  }

  @Test
  public void verifyFailsWhenProofAssetIdIsMissingFromAssetData() {
    List<SplitAssetProof> proofs = List.of(this.splitJustification.getProofs().get(0));
    PaymentData originalPaymentData = TestPaymentData.decode(originalDataBytes());
    Set<Asset> assets = originalPaymentData.getAssets().stream()
            .filter(asset -> !asset.getId().equals(proofs.get(0).getAssetId()))
            .collect(Collectors.toSet());

    SplitMintJustification mutated = SplitMintJustification.create(
            this.splitJustification.getToken(), new LinkedHashSet<>(proofs));
    byte[] data = new TestPaymentData(assets).encode();

    VerificationResult<VerificationStatus> result = verifyWith(mutated.toCbor(), data);
    assertFailWithMessage(result,
            String.format("Asset id %s not found in asset data.", proofs.get(0).getAssetId()));
  }

  @Test
  public void verifyFailsWhenAssetAmountDoesNotMatchLeafAmount() {
    PaymentData originalPaymentData = TestPaymentData.decode(originalDataBytes());
    List<Asset> assets = new ArrayList<>(originalPaymentData.getAssets());
    Asset asset = assets.get(0);
    Asset modified = new Asset(asset.getId(), asset.getValue().add(BigInteger.ONE));
    assets.set(0, modified);

    byte[] data = new TestPaymentData(Set.copyOf(assets)).encode();

    VerificationResult<VerificationStatus> result = verifyWith(this.splitJustification.toCbor(), data);
    assertFailWithMessage(result,
            String.format("Asset amount for asset id %s does not match asset tree leaf.", asset.getId()));
  }

  @Test
  public void verifyFailsWhenAggregationRootDoesNotMatchBurnPredicate() throws Exception {
    List<SplitAssetProof> originalProofs = new ArrayList<>(this.splitJustification.getProofs());

    SparseMerkleTree aggregationTree = new SparseMerkleTree(HashAlgorithm.SHA256);
    for (SplitAssetProof proof : originalProofs) {
      aggregationTree.addLeaf(
              proof.getAssetId().toBitString().toBigInteger(),
              proof.getAssetTreePath().getRootHash().getImprint()
      );
    }
    aggregationTree.addLeaf(
            new BigInteger(1, "extra-leaf-marker".getBytes(StandardCharsets.UTF_8)),
            new byte[]{0x01}
    );
    SparseMerkleTreeRootNode aggregationRoot = aggregationTree.calculateRoot();

    List<SplitAssetProof> mutatedProofs = new ArrayList<>();
    for (SplitAssetProof proof : originalProofs) {
      mutatedProofs.add(SplitAssetProof.create(
              proof.getAssetId(),
              aggregationRoot.getPath(proof.getAssetId().toBitString().toBigInteger()),
              proof.getAssetTreePath()
      ));
    }

    SplitMintJustification mutated = SplitMintJustification.create(
            this.splitJustification.getToken(), new LinkedHashSet<>(mutatedProofs));

    VerificationResult<VerificationStatus> result = verifyWith(mutated.toCbor(), originalDataBytes());
    assertFailWithMessage(result, "Aggregation path root does not match burn predicate.");
  }

  private byte[] originalDataBytes() {
    return this.splitToken.getGenesis().getData().orElseThrow();
  }

  private VerificationResult<VerificationStatus> verifyWith(byte[] justification, byte[] data) {
    Token modified = withJustificationAndData(this.splitToken, justification, data);
    return this.splitMintJustificationVerifier.verify(modified.getGenesis(), this.mintJustificationVerifier);
  }

  private VerificationResult<VerificationStatus> verifyWithPaymentData(byte[] justification,
                                                                       PaymentData paymentData) {
    Token modified = withJustificationAndData(this.splitToken, justification, originalDataBytes());
    SplitMintJustificationVerifier verifier = new SplitMintJustificationVerifier(
            this.trustBase, this.predicateVerifier, ignored -> paymentData);
    return verifier.verify(modified.getGenesis(), this.mintJustificationVerifier);
  }

  private Token withJustificationAndData(Token token, byte[] justification, byte[] data) {
    CborDeserializer.CborTag tokenTag = CborDeserializer.decodeTag(token.toCbor());
    List<byte[]> tokenData = CborDeserializer.decodeArray(tokenTag.getData());

    List<byte[]> certifiedGenesis = CborDeserializer.decodeArray(tokenData.get(1));

    CborDeserializer.CborTag mintTag = CborDeserializer.decodeTag(certifiedGenesis.get(0));
    List<byte[]> mint = CborDeserializer.decodeArray(mintTag.getData());

    mint.set(4, CborSerializer.encodeNullable(justification, CborSerializer::encodeByteString));
    mint.set(5, CborSerializer.encodeNullable(data, CborSerializer::encodeByteString));

    certifiedGenesis.set(0, CborSerializer.encodeTag(mintTag.getTag(), encodeArray(mint)));
    tokenData.set(1, encodeArray(certifiedGenesis));

    return Token.fromCbor(CborSerializer.encodeTag(tokenTag.getTag(), encodeArray(tokenData)));
  }

  private byte[] corruptBurnTokenInJustification(byte[] justificationBytes) {
    CborDeserializer.CborTag justificationTag = CborDeserializer.decodeTag(justificationBytes);
    List<byte[]> reasonData = CborDeserializer.decodeArray(justificationTag.getData());

    CborDeserializer.CborTag tokenTag = CborDeserializer.decodeTag(reasonData.get(0));
    List<byte[]> tokenData = CborDeserializer.decodeArray(tokenTag.getData());
    List<byte[]> transactions = CborDeserializer.decodeArray(tokenData.get(2));
    List<byte[]> certifiedTransfer = CborDeserializer.decodeArray(transactions.get(0));

    CborDeserializer.CborTag transferTag = CborDeserializer.decodeTag(certifiedTransfer.get(0));
    List<byte[]> transfer = CborDeserializer.decodeArray(transferTag.getData());

    byte[] differentNonce = new byte[32];
    differentNonce[0] = 1;
    transfer.set(2, CborSerializer.encodeByteString(differentNonce));

    certifiedTransfer.set(0, CborSerializer.encodeTag(transferTag.getTag(), encodeArray(transfer)));
    transactions.set(0, encodeArray(certifiedTransfer));
    tokenData.set(2, encodeArray(transactions));
    reasonData.set(0, CborSerializer.encodeTag(tokenTag.getTag(), encodeArray(tokenData)));
    return CborSerializer.encodeTag(justificationTag.getTag(), encodeArray(reasonData));
  }

  private static PaymentData paymentDataOf(Set<Asset> assets) {
    return new PaymentData() {
      @Override
      public Set<Asset> getAssets() {
        return assets;
      }

      @Override
      public byte[] encode() {
        return new byte[0];
      }
    };
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

  static final class NonUniqueAssetSet extends AbstractSet<Asset> {

    private final List<Asset> items;

    NonUniqueAssetSet(List<Asset> items) {
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
