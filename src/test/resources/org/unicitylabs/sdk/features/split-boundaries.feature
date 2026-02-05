@split-boundaries
Feature: Token Split Boundary Conditions
  As a developer using the Unicity SDK
  I want to verify split operations handle edge cases correctly
  So that invalid splits are rejected and valid splits produce correct results

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

  Scenario: Split token into equal halves preserves total value
    Given "Alice" mints a token with random coin data
    And each user have nametags prepared
    When "Alice" splits her token into halves for "Bob" as "Unmasked"
    Then both split tokens should be valid
    And the split token values should sum to the original value

  Scenario: Split token and transfer both halves to different users
    Given "Alice" mints a token with random coin data
    And each user have nametags prepared
    When "Alice" splits her token into halves for "Carol" as "Unmasked"
    And "Alice" transfers one split token to "Bob"
    And "Bob" finalizes all received tokens
    Then "Bob" should own the token successfully
    And "Carol" should own 1 tokens
