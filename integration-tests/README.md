# liflig-logging integration tests

We want to test that our library works as expected with different SLF4J implementations, and that
Logback is not added to the classpath unless we explicitly add it as a dependency.

To achieve this, we include a wholly separate Maven project here under `integration-tests/`, with
submodules for various SLF4J logger implementations. We run these tests as follows:

1. In the `pre-integration-test` phase, the top-level `liflig-logging` POM installs the library JAR
   in a local Maven repository at `integration-tests/local-maven-repo`
   - `integration-tests/pom.xml` declares the `local-maven-repo` repository, so it can depend on the
     freshly built `liflig-logging` JAR
2. Then, in the `integration-test` phase, the `liflig-logging` POM executes
   `mvn clean test -f=integration-tests`
   - This runs tests from `integration-tests/` and all submodules
