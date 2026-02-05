@predicate-matrix
Feature: Predicate Type Combination Matrix
  As a developer using the Unicity SDK
  I want to verify all combinations of transfer types work correctly
  So that mixed predicate chains are fully supported

  Background:
    Given the aggregator URL is configured
    And trust-base.json is set
    And the state transition client is initialized
    And the following users are set up with their signing services
      | name  |
      | Alice |
      | Bob   |
      | Carol |
      | Dave  |

  Scenario Outline: Two-hop transfer with <firstTransferType> then <secondTransferType>
    Given "Alice" mints a token with random coin data
    And each user have nametags prepared
    When "Alice" transfers the token to "Bob" using <firstTransferType>
    And "Bob" finalizes all received tokens
    Then "Bob" should own the token successfully
    When "Bob" transfers the token to "Carol" using <secondTransferType>
    And "Carol" finalizes all received tokens
    Then "Carol" should own the token successfully
    And the token for "Carol" should have 2 transactions in its history

    Examples:
      | firstTransferType     | secondTransferType    |
      | a proxy address       | a proxy address       |
      | a proxy address       | an unmasked predicate |
      | an unmasked predicate | a proxy address       |
      | an unmasked predicate | an unmasked predicate |

  Scenario Outline: Three-hop mixed predicate chain
    Given "Alice" mints a token with random coin data
    And each user have nametags prepared
    When "Alice" transfers the token to "Bob" using <hop1>
    And "Bob" finalizes all received tokens
    When "Bob" transfers the token to "Carol" using <hop2>
    And "Carol" finalizes all received tokens
    When "Carol" transfers the token to "Dave" using <hop3>
    And "Dave" finalizes all received tokens
    Then "Dave" should own the token successfully

    Examples:
      | hop1                  | hop2                  | hop3                  |
      | a proxy address       | an unmasked predicate | a proxy address       |
      | an unmasked predicate | a proxy address       | an unmasked predicate |
