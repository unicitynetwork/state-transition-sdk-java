@verification
Feature: Token Verification Result Details

  Background:
    Given a mock aggregator client is set up

  Scenario: Minted token verification result contains genesis and transfer rules
    Given Alice has a signing key
    And Alice has a minted token
    When the token is verified against the trust base
    Then the verification result has rule "TokenVerification" with status "OK"
    And the verification result contains 2 sub-results
    And sub-result 1 has rule "CertifiedMintTransactionVerificationRule" with status "OK"
    And sub-result 2 has rule "TokenTransferVerification" with status "OK"

  Scenario: Transferred token verification result includes transfer sub-entries
    Given Alice has a signing key
    And Bob is a registered user
    And Alice has a minted token
    When Alice transfers the token to Bob
    And the transferred token is verified against the trust base
    Then the verification result has rule "TokenVerification" with status "OK"
    And the transfer verification sub-result contains 1 entry
