@authorization
Feature: Token Ownership Authorization
  As a developer using the Unicity SDK
  I want to verify that only token owners can perform operations
  So that unauthorized transfers and operations are prevented

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

  Scenario Outline: Non-owner <attacker> cannot transfer token owned by <owner>
    Given "<owner>" mints a token with random coin data
    And each user have nametags prepared
    When "<attacker>" attempts to transfer "<owner>"'s token to "<recipient>" using a proxy address
    Then the unauthorized transfer should fail

    Examples:
      | owner | attacker | recipient |
      | Alice | Bob      | Carol     |
      | Alice | Carol    | Dave      |
      | Bob   | Alice    | Carol     |
      | Bob   | Carol    | Dave      |

  Scenario Outline: Previous owner cannot reclaim transferred token via <transferType>
    Given "Alice" mints a token with random coin data
    And each user have nametags prepared
    When "Alice" transfers the token to "Bob" using <transferType>
    And "Bob" finalizes all received tokens
    Then "Bob" should own the token successfully
    When "Alice" attempts to transfer "Bob"'s token to "Carol" using a proxy address
    Then the unauthorized transfer should fail

    Examples:
      | transferType          |
      | a proxy address       |
      | an unmasked predicate |

  Scenario: Non-owner cannot transfer token received by another user
    Given "Alice" mints a token with random coin data
    And each user have nametags prepared
    When "Alice" transfers the token to "Bob" using a proxy address
    And "Bob" finalizes all received tokens
    Then "Bob" should own the token successfully
    When "Carol" attempts to transfer "Bob"'s token to "Dave" using a proxy address
    Then the unauthorized transfer should fail
