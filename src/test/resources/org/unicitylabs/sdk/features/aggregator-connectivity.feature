@aggregator-connectivity
Feature: Aggregator Connectivity and Basic Operations
  As a developer using the Unicity SDK
  I want to verify connectivity with the aggregator
  So that I can ensure the system is operational

  Background:
    Given the aggregator URL is configured
    And the trust base is set
    And the aggregator client is initialized

  Scenario: Verify aggregator connectivity
    When I request the current block height
    Then the block height should be returned
    And the block height should be greater than 0

  Scenario Outline: Submit commitment with performance validation
    Given a random secret of <secretLength> bytes
    And a state hash from <stateLength> bytes of random data
    And transaction data "<txData>"
    When I submit a commitment with the generated data
    Then the commitment should be submitted successfully
    And the submission should complete in less than <maxDuration> milliseconds

    Examples:
      | secretLength | stateLength | txData                      | maxDuration |
      | 32          | 32          | test commitment performance | 5000        |
      | 16          | 24          | simple test data           | 3000        |

  Scenario Outline: Parallel mint commitments with inclusion proof verification
    Given I configure <threadsCount> threads with <commitmentsPerThread> commitments each
    When I submit all mint commitments concurrently
    Then all mint commitments should receive inclusion proofs within <timeoutSeconds> seconds

    Examples:
      | threadsCount | commitmentsPerThread | timeoutSeconds |
      | 1            | 10                   | 120            |
      | 5            | 10                   | 120            |
      | 10           | 10                   | 120            |
      | 20           | 10                   | 120            |
      | 40           | 10                   | 120            |
      | 80           | 10                   | 120            |