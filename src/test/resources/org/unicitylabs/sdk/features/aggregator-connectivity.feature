@aggregator-connectivity
Feature: Aggregator connectivity
  Basic client + aggregator wiring. Proves the SDK can reach the aggregator,
  request block height, and round-trip a single mint certification.

  Background:
    Given a mock aggregator is running

  # Tagged @hermetic-only because the bft-shard subscription proxy gates
  # JSON-RPC calls on stateId/shardId — getBlockHeight() carries neither.
  # This scenario only runs against the in-process StrictTestAggregatorClient.
  @hermetic-only
  Scenario: Block height round-trip
    When I request the current block height
    Then a block height is returned

  Scenario: Single mint is accepted by the aggregator
    Given Alice has a signing key
    When Alice mints a token
    Then the current token verifies successfully
