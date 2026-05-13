package org.unicitylabs.sdk.e2e;

import io.cucumber.junit.platform.engine.Constants;
import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;

@Suite
@IncludeEngines("cucumber")
@SelectPackages("org.unicitylabs.sdk.features")
@ConfigurationParameter(
    key = Constants.GLUE_PROPERTY_NAME,
    value = "org.unicitylabs.sdk.e2e.steps,"
        + "org.unicitylabs.sdk.e2e.steps.shared,"
        + "org.unicitylabs.sdk.e2e.config")
@ConfigurationParameter(
    key = Constants.PLUGIN_PROPERTY_NAME,
    value = "pretty,"
        + "html:build/cucumber-reports/cucumber.html,"
        + "json:build/cucumber-reports/cucumber.json")
@ConfigurationParameter(
    key = Constants.EXECUTION_DRY_RUN_PROPERTY_NAME,
    value = "false")
@ConfigurationParameter(
    key = Constants.PLUGIN_PUBLISH_QUIET_PROPERTY_NAME,
    value = "true")
// Default tag filter — excludes nametag-gated scenarios (blocked until UnicityId
// lands), load/perf scenarios, and work-in-progress. Overridable by passing
// -Dcucumber.filter.tags=<expr> on the command line; system properties take
// precedence over @ConfigurationParameter defaults per JUnit Platform spec.
@ConfigurationParameter(
    key = Constants.FILTER_TAGS_PROPERTY_NAME,
    value = "not @slow and not @wip and not @ignore "
        + "and not @bft-shard-only and not @multi-shard-only "
        + "and not @pending-src-cleanup "
        + "and not @stateful and not @fresh-aggregator")
public class CucumberTestRunner {
}
