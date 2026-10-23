@split
Feature: Token Split - Value Boundary Validation

  Background:
    Given a mock aggregator is running
    And Alice has a minted token with 2 payment assets worth 100 and 200

  Scenario: Split where total exceeds original value fails
    When Alice tries to split with values exceeding the original totals
    Then the split fails with TokenAssetValueMismatchError

  Scenario: Split where total is less than original value fails
    When Alice tries to split with values less than the original totals
    Then the split fails with TokenAssetValueMismatchError

  Scenario: Split with minimum asset value of 1 is accepted
    When Alice tries to split with minimum values of 1 and the remainder
    Then the split validation succeeds
