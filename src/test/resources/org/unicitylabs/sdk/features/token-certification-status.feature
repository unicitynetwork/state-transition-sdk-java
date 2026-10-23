@certification-status
Feature: Certification Status Handling
  Error statuses returned by the aggregator on malformed or unauthorized requests.

  Background:
    Given a mock aggregator is running

  Scenario: Transfer signed with wrong key is rejected by aggregator
    Given Alice has a minted token
    And Bob is a registered user
    When Alice creates a transfer to Bob signed with the wrong key
    Then the certification response status is "SIGNATURE_VERIFICATION_FAILED"

  @forgiving
  Scenario: Duplicate mint is detected via inclusion proof mismatch
    # Uses ForgivingTestAggregatorClient so both submissions return SUCCESS;
    # detection lives at inclusion-proof verification where the leaf's hash
    # doesn't match the second tx's hash.
    Given a forgiving aggregator is running
    And a user with a signing key
    When the user submits a mint request for a specific token ID
    And the user submits a second mint request for the same token ID
    Then the first aggregator response is "SUCCESS"
    And the second aggregator response is "SUCCESS"
    But the inclusion proof verification rejects the second mint with "TRANSACTION_HASH_MISMATCH"
