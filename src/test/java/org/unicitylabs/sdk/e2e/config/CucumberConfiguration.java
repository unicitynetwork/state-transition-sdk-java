package org.unicitylabs.sdk.e2e.config;

import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.Scenario;
import org.unicitylabs.sdk.e2e.context.TestContext;

/**
 * Cucumber lifecycle hooks. Cucumber's PicoContainer creates one instance per scenario and
 * injects the shared {@link TestContext}, ensuring the same context reaches every step class
 * that takes it as a constructor parameter.
 */
public class CucumberConfiguration {

  private final TestContext testContext;

  public CucumberConfiguration(TestContext testContext) {
    this.testContext = testContext;
  }

  @Before
  public void beforeScenario(Scenario scenario) {
    testContext.clearTestState();
    System.out.println("[BDD] >>> " + scenario.getName());
  }

  @After
  public void afterScenario(Scenario scenario) {
    System.out.println(
        "[BDD] <<< " + scenario.getName() + " — " + scenario.getStatus());
  }

  @After("@reset")
  public void fullReset() {
    testContext.reset();
  }
}
