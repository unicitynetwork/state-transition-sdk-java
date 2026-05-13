package org.unicitylabs.sdk.e2e.support;

import org.unicitylabs.sdk.predicate.Predicate;

import org.unicitylabs.sdk.crypto.secp256k1.SigningService;
import org.unicitylabs.sdk.predicate.builtin.SignaturePredicate;


/** Named user participating in the tree fixture. Immutable. */
public final class TreeUser {

  private final String name;
  private final SigningService signingService;
  private final SignaturePredicate predicate;
  private final Predicate address;

  TreeUser(String name, SigningService signingService) {
    this.name = name;
    this.signingService = signingService;
    this.predicate = SignaturePredicate.fromSigningService(signingService);
    this.address = this.predicate;
  }

  public static TreeUser generate(String name) {
    return new TreeUser(name, SigningService.generate());
  }

  public String getName() {
    return name;
  }

  public SigningService getSigningService() {
    return signingService;
  }

  public SignaturePredicate getPredicate() {
    return predicate;
  }

  public Predicate getAddress() {
    return address;
  }
}
