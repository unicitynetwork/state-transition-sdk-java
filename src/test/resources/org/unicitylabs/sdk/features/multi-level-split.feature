@multi-level-split
Feature: Multi-Level Token Split Tree
  As a developer using the Unicity SDK
  I want to verify that split tokens can be further split and transferred
  So that multi-level token trees maintain integrity

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

  Scenario: Split then transfer split parts to different users
    Given "Alice" mints a token with random coin data
    And each user have nametags prepared
    When "Alice" splits her token into halves for "Carol" as "Unmasked"
    And "Alice" transfers one split token to "Bob"
    And "Bob" finalizes all received tokens
    Then "Bob" should own the token successfully
    When "Bob" transfers the token to "Dave" using an unmasked predicate
    And "Dave" finalizes all received tokens
    Then "Dave" should own the token successfully

  Scenario: Double-spend prevention on split tokens
    Given "Alice" mints a token with random coin data
    And each user have nametags prepared
    When "Alice" splits her token into halves for "Carol" as "Unmasked"
    And "Alice" transfers one split token to "Bob"
    And "Bob" finalizes all received tokens
    Then "Bob" should own the token successfully
    When "Alice" attempts to double-spend the split token to "Dave"
    Then the double-spend attempt should be rejected
