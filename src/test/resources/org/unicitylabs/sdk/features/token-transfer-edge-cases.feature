@edge-cases
Feature: Token Transfer Edge Cases

  Background:
    Given a mock aggregator is running
    And Alice has a signing key
    And Alice has a minted token

  Scenario: Owner transfers token to themselves
    When Alice transfers the current token to Alice
    Then the current token verifies successfully
    And the current token is owned by Alice

  Scenario: Stale token object cannot be reused after transfer
    Given Bob has a signing key
    When Alice transfers the current token to Bob
    And Alice tries to submit a transfer of the stale token to Bob
    Then the certification response status is "STATE_ID_EXISTS"
