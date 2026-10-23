@smoke
Feature: Cucumber wiring smoke test
  Proves the BDD runner discovers features, resolves glue packages, and injects TestContext.
  Runs without an aggregator.

  Scenario: The test context is injected and writable
    Given a fresh test context
    When I remember the current user as "Alice"
    Then the test context reports the current user as "Alice"
