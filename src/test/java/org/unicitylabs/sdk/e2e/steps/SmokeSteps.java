package org.unicitylabs.sdk.e2e.steps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.unicitylabs.sdk.e2e.context.TestContext;

public class SmokeSteps {

  private final TestContext context;

  public SmokeSteps(TestContext context) {
    this.context = context;
  }

  @Given("a fresh test context")
  public void aFreshTestContext() {
    assertNull(context.getCurrentUser(), "current user should be null at scenario start");
    assertNull(context.getCurrentToken(), "current token should be null at scenario start");
  }

  @When("I remember the current user as {string}")
  public void iRememberTheCurrentUserAs(String user) {
    context.setCurrentUser(user);
  }

  @Then("the test context reports the current user as {string}")
  public void theTestContextReportsTheCurrentUserAs(String user) {
    assertEquals(user, context.getCurrentUser());
  }
}
