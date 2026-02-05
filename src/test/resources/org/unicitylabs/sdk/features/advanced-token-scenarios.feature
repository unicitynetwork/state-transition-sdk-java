@advanced-token
Feature: Advanced Token Scenarios
  As a developer using the Unicity SDK
  I want to test complex token operations and edge cases
  So that I can ensure the system handles advanced scenarios correctly

  Background:
    Given the aggregator URL is configured
    And trust-base.json is set
    And the state transition client is initialized
    And the following users are set up with their signing services
      | Alice |
      | Bob   |
      | Carol |
      | Dave |

  @performance
  @reset
  Scenario Outline: Bulk token operations performance
    Given <userCount> users are configured for bulk operations
    When each user mints <tokensPerUser> tokens simultaneously
    And all tokens are verified in parallel
    Then all <totalTokens> tokens should be created successfully
    And the operation should complete within <maxDuration> seconds
    And the success rate should be at least <minSuccessRate>%

    Examples:
      | userCount | tokensPerUser | totalTokens | maxDuration | minSuccessRate |
      | 5         | 10            | 50          | 30          | 95             |
      | 10        | 5             | 50          | 25          | 90             |

  @edge-cases
  @reset
  Scenario Outline: Token transfer chain with validation
    Given "Alice" mints a token with <coinValue> coin value
    When the token is transferred through the chain of existing users
    And each transfer includes custom data validation
    Then the final token should maintain original properties
    And the transfer chain should have <expectedChainSize> participants from "<startUser>" to "<endUser>"
    And the token should have <expectedTransfers> transfers in history

    Examples:
      | coinValue | expectedTransfers | expectedChainSize | startUser | endUser |
      | 1000      | 3                 | 4                 | Alice     | Dave    |
      | 5000      | 3                 | 4                 | Alice     | Dave    |

  @nametag-scenarios
  @reset
  Scenario Outline: Complex name tag token interactions
    Given "Alice" creates <tokenCount> tokens
    Given "Bob" creates nametags for each token
    When "Alice" transfers tokens to each of "Bob" nametags
    And "Bob" finalizes all received tokens
    And "Bob" consolidates all received tokens
    Then "Bob" should own <tokenCount> tokens
    And all "Bob" nametag tokens should remain valid
    And proxy addressing should work for all "Bob" name tags

    Examples:
      | tokenCount |
      | 3           |
      | 5           |

#  @splitting-scenarios
#  Scenario Outline: Multi-level token splitting
#    Given Carol owns a token worth <originalValue> coins
#    When the token is split into <firstSplit> tokens
#    And one of the resulting tokens is split again into <secondSplit> tokens
#    Then the total number of tokens should be <totalTokens>
#    And the total coin value should equal the original <originalValue>
#    And all tokens should be independently transferable
#
#    Examples:
#      | originalValue | firstSplit | secondSplit | totalTokens |
#      | 10000        | 3          | 2           | 4           |
#      | 20000        | 4          | 3           | 6           |
#
#  @concurrency
#  Scenario: Concurrent operations on same token
#    Given "Alice" owns a token
#    When "Alice" attempts to transfer the token to both "Bob" and "Carol" simultaneously
#    Then only one transfer should succeed
#    And the other transfer should be rejected
#    And the token should belong to exactly one recipient
#    And no tokens should be duplicated
#
#  @data-integrity
#  Scenario Outline: Large custom data handling
#    Given a token with custom data of size <dataSize> bytes
#    When the token is transferred with the large custom data
#    Then the transfer should <expectation>
#    And the data integrity should be maintained
#    And the system performance should remain acceptable
#
#    Examples:
#      | dataSize | expectation |
#      | 1024    | succeed     |
#      | 10240   | succeed     |
#      | 102400  | succeed     |
#
#  @mixed-predicates
#  Scenario: Mixed predicate type interactions
#    Given "Alice" uses a "masked" predicate
#    And "Bob" uses a "unmasked" predicate
#    And "Carol" uses a name tag token
#    When tokens are transferred between all users in various combinations
#    Then all transfers should work correctly regardless of predicate types
#    And token verification should pass for all predicate combinations
#    And the system should handle predicate conversions properly