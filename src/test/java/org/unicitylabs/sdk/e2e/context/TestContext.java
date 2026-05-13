package org.unicitylabs.sdk.e2e.context;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.unicitylabs.sdk.StateTransitionClient;
import org.unicitylabs.sdk.api.AggregatorClient;
import org.unicitylabs.sdk.api.CertificationResponse;
import org.unicitylabs.sdk.api.bft.RootTrustBase;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.predicate.Predicate;
import org.unicitylabs.sdk.predicate.verification.PredicateVerifierService;
import org.unicitylabs.sdk.transaction.Token;
import org.unicitylabs.sdk.transaction.verification.MintJustificationVerifierService;
import org.unicitylabs.sdk.unicityid.UnicityIdToken;

/**
 * Shared scenario state for Cucumber step classes. A single instance is constructed per scenario
 * by Cucumber's PicoContainer and injected into every step class via constructor parameter.
 *
 * <p>Scope: scenario. {@link org.unicitylabs.sdk.e2e.config.CucumberConfiguration} clears
 * transient state in a {@code @Before} hook and does a full reset on scenarios tagged
 * {@code @reset}.
 */
public class TestContext {

  private AggregatorClient aggregatorClient;
  private StateTransitionClient client;
  private RootTrustBase trustBase;
  private PredicateVerifierService predicateVerifier;
  private MintJustificationVerifierService mintJustificationVerifier;
  private List<AggregatorClient> aggregatorClients = new ArrayList<>();
  private List<String> aggregatorUrls = new ArrayList<>();

  private final Map<String, SigningService> userSigningServices = new HashMap<>();
  private final Map<String, Predicate> userPredicates = new HashMap<>();
  private final Map<String, Predicate> userAddresses = new HashMap<>();
  private final Map<String, List<Token>> userTokens = new HashMap<>();

  private Token currentToken;
  private String currentUser;
  private Exception lastError;
  private Long blockHeight;
  private long lastOperationDurationMs;

  // Phase 2 scratch — CBOR roundtrip + corrupted-import assertions.
  private byte[] exportedTokenCbor;
  private Token importedToken;

  // Phase 2 raw-submit scratch — captures the latest CertificationResponse
  // from a manual submission (i.e. not via TokenUtils which throws on non-SUCCESS).
  private CertificationResponse lastCertificationResponse;

  // Phase 5 scratch — snapshot of the freshly minted source token so multi-hop
  // chain scenarios can assert "same ID/type as the original" after transfers.
  private Token originalToken;

  // Cross-step access to the latest split's child tokens, so split-related
  // step classes other than SplitSteps can read them.
  private final List<Token> splitChildren = new ArrayList<>();

  // Phase C — registered nametag tokens, keyed by user name.
  private final Map<String, UnicityIdToken> userNametags = new HashMap<>();
  // Captures the addressing method used by the most recent address-aware mint
  // or transfer ("pubkey" / "nametag"), for assertions.
  private String addressingMethod;

  public AggregatorClient getAggregatorClient() {
    return aggregatorClient;
  }

  public void setAggregatorClient(AggregatorClient aggregatorClient) {
    this.aggregatorClient = aggregatorClient;
  }

  public StateTransitionClient getClient() {
    return client;
  }

  public void setClient(StateTransitionClient client) {
    this.client = client;
  }

  public RootTrustBase getTrustBase() {
    return trustBase;
  }

  public void setTrustBase(RootTrustBase trustBase) {
    this.trustBase = trustBase;
  }

  public PredicateVerifierService getPredicateVerifier() {
    return predicateVerifier;
  }

  public void setPredicateVerifier(PredicateVerifierService predicateVerifier) {
    this.predicateVerifier = predicateVerifier;
  }

  public MintJustificationVerifierService getMintJustificationVerifier() {
    return mintJustificationVerifier;
  }

  public void setMintJustificationVerifier(MintJustificationVerifierService mjv) {
    this.mintJustificationVerifier = mjv;
  }

  public List<AggregatorClient> getAggregatorClients() {
    return aggregatorClients;
  }

  public void setAggregatorClients(List<AggregatorClient> aggregatorClients) {
    this.aggregatorClients = aggregatorClients;
  }

  public List<String> getAggregatorUrls() {
    return aggregatorUrls;
  }

  public void setAggregatorUrls(List<String> aggregatorUrls) {
    this.aggregatorUrls = aggregatorUrls;
  }

  public Map<String, SigningService> getUserSigningServices() {
    return userSigningServices;
  }

  public Map<String, Predicate> getUserPredicates() {
    return userPredicates;
  }

  public Map<String, Predicate> getUserAddresses() {
    return userAddresses;
  }

  public Map<String, List<Token>> getUserTokens() {
    return userTokens;
  }

  public void addUserToken(String user, Token token) {
    userTokens.computeIfAbsent(user, k -> new ArrayList<>()).add(token);
  }

  public Token getCurrentToken() {
    return currentToken;
  }

  public void setCurrentToken(Token currentToken) {
    this.currentToken = currentToken;
  }

  public String getCurrentUser() {
    return currentUser;
  }

  public void setCurrentUser(String currentUser) {
    this.currentUser = currentUser;
  }

  public Exception getLastError() {
    return lastError;
  }

  public void setLastError(Exception lastError) {
    this.lastError = lastError;
  }

  public Long getBlockHeight() {
    return blockHeight;
  }

  public void setBlockHeight(Long blockHeight) {
    this.blockHeight = blockHeight;
  }

  public long getLastOperationDurationMs() {
    return lastOperationDurationMs;
  }

  public void setLastOperationDurationMs(long lastOperationDurationMs) {
    this.lastOperationDurationMs = lastOperationDurationMs;
  }

  public byte[] getExportedTokenCbor() {
    return exportedTokenCbor;
  }

  public void setExportedTokenCbor(byte[] exportedTokenCbor) {
    this.exportedTokenCbor = exportedTokenCbor;
  }

  public Token getImportedToken() {
    return importedToken;
  }

  public void setImportedToken(Token importedToken) {
    this.importedToken = importedToken;
  }

  public CertificationResponse getLastCertificationResponse() {
    return lastCertificationResponse;
  }

  public void setLastCertificationResponse(CertificationResponse lastCertificationResponse) {
    this.lastCertificationResponse = lastCertificationResponse;
  }

  public Token getOriginalToken() {
    return originalToken;
  }

  public void setOriginalToken(Token originalToken) {
    this.originalToken = originalToken;
  }

  public List<Token> getSplitChildren() {
    return splitChildren;
  }

  public Map<String, UnicityIdToken> getUserNametags() {
    return userNametags;
  }

  public String getAddressingMethod() {
    return addressingMethod;
  }

  public void setAddressingMethod(String addressingMethod) {
    this.addressingMethod = addressingMethod;
  }

  /** Clears transient per-scenario state but keeps long-lived clients and trust base. */
  public void clearTestState() {
    aggregatorUrls = new ArrayList<>();
    userSigningServices.clear();
    userPredicates.clear();
    userAddresses.clear();
    userTokens.clear();
    currentToken = null;
    currentUser = null;
    lastError = null;
    blockHeight = null;
    lastOperationDurationMs = 0L;
    exportedTokenCbor = null;
    importedToken = null;
    lastCertificationResponse = null;
    originalToken = null;
    splitChildren.clear();
    userNametags.clear();
    addressingMethod = null;
  }

  /** Full reset, including clients and trust base. Used by scenarios tagged {@code @reset}. */
  public void reset() {
    clearTestState();
    aggregatorClient = null;
    client = null;
    trustBase = null;
    predicateVerifier = null;
    mintJustificationVerifier = null;
    aggregatorClients = new ArrayList<>();
  }
}
