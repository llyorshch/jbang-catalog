# jbang-catalog

JBang catalog for `llyorshch`.

## mvnrun

Runs standalone Maven plugin goals without installing Maven.

```bash
jbang mvnrun@llyorshch help:evaluate -Dexpression=java.version -q -DforceStdout
jbang mvnrun@llyorshch org.apache.maven.plugins:maven-dependency-plugin:3.8.1:get -Dartifact=org.slf4j:slf4j-api:2.0.13
jbang mvnrun@llyorshch --maven-version 3.9.15 help:help
```

On first use it downloads Maven from Maven Central, verifies its SHA-512 and caches it under
JBang's cache directory in `mvnrun/`. Set `MVNRUN_REPO_URL` to download Maven from another repository.

Goals that need a project, such as `compile`, need a `pom.xml` in the current directory.

## Tests

```bash
./test.sh "$PWD/mvnrun.java" /path/to/empty/work/dir
```

The work directory is deleted and recreated. Tests need network access, `curl` and `python3`.
