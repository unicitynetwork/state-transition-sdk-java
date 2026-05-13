@token-transfer
Feature: Token lifecycle — mint, transfer, verify
  Basic v2 token flow against a mock aggregator. Mirrors the TypeScript SDK's
  CommonTestFlow.testTransferFlow: Alice mints, transfers to Bob, Bob transfers
  to Carol; every hop must verify.

  Background:
    Given a mock aggregator is running
    And Alice has a signing key
    And Bob has a signing key
    And Carol has a signing key

  Scenario: Alice mints a token and it verifies
    When Alice mints a token
    Then the current token verifies successfully
    And the current token is owned by Alice

  Scenario: Alice transfers a minted token to Bob
    Given Alice has a minted token
    When Alice transfers the current token to Bob
    Then the current token verifies successfully
    And the current token is owned by Bob

  Scenario: Alice -> Bob -> Carol transfer chain
    Given Alice has a minted token
    When Alice transfers the current token to Bob
    And Bob transfers the current token to Carol
    Then the current token verifies successfully
    And the current token is owned by Carol
