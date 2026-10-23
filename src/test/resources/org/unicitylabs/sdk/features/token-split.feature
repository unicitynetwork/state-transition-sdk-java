@split
Feature: Token Split
  A token owner splits a token with 2 payment assets into 2 new tokens.

  Background:
    Given a mock aggregator is running
    And Alice has a minted token with 2 payment assets

  Scenario: Successfully split a token into multiple parts
    When Alice splits the token into 2 new tokens
    Then the burn transaction succeeds
    And 2 split tokens are minted

  Scenario: Split tokens are individually verifiable
    When Alice splits the token into 2 new tokens
    Then each split token passes TokenSplit verification

  Scenario: Split fails when asset count does not match
    When Alice tries to split with only 1 asset instead of 2
    Then the split fails with TokenAssetCountMismatchError

  Scenario: Split fails when asset ID is missing
    When Alice tries to split with a wrong asset ID
    Then the split fails with TokenAssetMissingError

  Scenario: Split fails when asset value is incorrect
    When Alice tries to split with incorrect asset values
    Then the split fails with TokenAssetValueMismatchError
