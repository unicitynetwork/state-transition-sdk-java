package org.unicitylabs.sdk.e2e.steps;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cucumber.java.en.Given;
import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.predicate.Predicate;
import org.unicitylabs.sdk.e2e.context.TestContext;
import org.unicitylabs.sdk.predicate.builtin.SignaturePredicate;


public class UserSteps {

  private final TestContext context;

  public UserSteps(TestContext context) {
    this.context = context;
  }

  @Given("{word} has a signing key")
  public void userHasASigningKey(String userName) {
    SigningService signing = SigningService.generate();
    SignaturePredicate predicate = SignaturePredicate.create(signing.getPublicKey());
    Predicate address = predicate;

    context.getUserSigningServices().put(userName, signing);
    context.getUserPredicates().put(userName, predicate);
    context.getUserAddresses().put(userName, address);
  }

  @Given("users {string}, {string} and {string} each have a signing key")
  public void threeUsersHaveSigningKeys(String a, String b, String c) {
    for (String name : new String[] {a, b, c}) {
      userHasASigningKey(name);
    }
    assertTrue(context.getUserSigningServices().size() >= 3);
  }

  @Given("{word} is a registered user")
  public void userIsARegisteredUser(String userName) {
    userHasASigningKey(userName);
  }

  @Given("a user with a signing key")
  public void aUserWithASigningKey() {
    userHasASigningKey("Alice");
    context.setCurrentUser("Alice");
  }
}
