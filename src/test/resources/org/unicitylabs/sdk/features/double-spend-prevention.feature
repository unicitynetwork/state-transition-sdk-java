@forgiving
Feature: Double Spend Prevention
  Exercises the forgiving-aggregator model: both submissions are accepted
  (mock no-ops the second leaf) and detection lives at inclusion-proof
  verification, where the second transaction's hash mismatches the recorded
  leaf.

  Background:
    Given a forgiving aggregator is running
    And Alice has a signing key
    And Alice has a minted token
    And Bob has a signing key

  Scenario: Double-spend attempt is detected via inclusion proof
    When Alice submits a valid transfer to Bob
    And Alice submits a second transfer of the same token
    Then the first aggregator response is "SUCCESS"
    And the second aggregator response is "SUCCESS"
    But the inclusion proof verification rejects the second transfer with "TRANSACTION_HASH_MISMATCH"
