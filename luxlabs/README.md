# LuxLabs Spring Boot 1.5.22.1.RELEASE

This is an internal compatibility fork of upstream `v1.5.22.RELEASE`, commit `34c62cc05919259cf75b925f88465638d1744b75`, maintained at https://github.com/luxlabs/spring-boot on branch `release/1.5.22.1`. It targets Tomcat `9.0.123` and Java 8 or later. The integration fixture requires JDK 11.

The runtime changes are confined to `spring-boot` and `spring-boot-autoconfigure`: use `setAcceptCount`, use `setCompressibleMimeType`, and preserve Tomcat's default cipher configuration when `server.ssl.ciphers` is unset or empty. The Boot 1.5 defaults for maximum connections (10000) and trusted internal proxy networks are deliberately retained; the newer Tomcat defaults are not substituted. The corresponding upstream tests now assert this retained contract. The BOM selects Tomcat `9.0.123` and Servlet API `4.0.1`; an older Servlet API shadows Tomcat's API and breaks error dispatch with `NoSuchMethodError: HttpServletRequest.getHttpServletMapping`.

The release profile builds `spring-boot-dependencies`, `spring-boot-parent`, the two runtime modules and the `spring-boot-test` and internal `spring-boot-test-support` modules required to compile their tests. These are built from the upstream source because the upstream test-support binary and boot-test test jar are not published in Central. The unchanged starters, test-auto-configuration library, actuator, processors and Maven plugin retain the published upstream `1.5.22.RELEASE` coordinates in the BOM. The duplicate Checkstyle declaration in the original parent is consolidated, preserving both validation executions.

This fork version is not an official Spring release and does not imply remediation of Spring Boot or Spring Framework vulnerabilities. Security tracking must retain the upstream `1.5.22.RELEASE` lineage. The unchanged BOM dependencies need their own application-specific security management.

## Build and validate

Use JDK 8 for the legacy source build. The verified local toolchain is Maven 3.10.0 with Temurin 8.0.504; the source and target settings remain those of the upstream project. Use a writable Maven repository dedicated to this build and the existing repository only as a read-only tail when using Maven's chained local repository support.

From the repository root, run `mvn -B -ntp -Pluxlabs-tomcat9 -Dtest=TomcatEmbeddedServletContainerFactoryTests,Tomcat9SslDefaultsTests,ServerPropertiesTests -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false install`. No test or static check is disabled. The Tomcat release profile removes the alternative Undertow Servlet 3.1 provider from the Surefire classpath, so that it cannot shadow the managed Servlet 4 API. This selects the Tomcat factory tests (including inherited HTTP, SSL, session, compression and error dispatch tests), the cipher regression tests and the server property tests. It does not run the entire upstream multi-container/auto-configuration suite.

After installation, switch `JAVA_HOME` to JDK 11 and run `python3 luxlabs/verify-lifecycle.py --run-dir /absolute/new/fixture-directory --repo-local /absolute/build/maven-cache --repo-tail /absolute/read-only/maven-cache`. The destination must not exist. This starts four actual Boot application contexts and verifies default/custom HTTP and TLS settings, property binding, customizer ordering, compression, cookies, sessions, header limits and access logging. Network connections are limited to the fixture's loopback ports. Generated passwords and keys are disposable fixture data.

`--classpath-file` can instead supply an extracted application classpath to run the same fixture on the actual application libraries. This fixture uses explicit Boot web auto-configuration: it does not certify application-wide component discovery, database setup, SSO or business processes.

## Publish and consume

Publish only after the source build and lifecycle checks pass. Use the Nexus Maven server ID `maven-releases` and the release repository `https://nexus.mitobit.com/repository/maven-releases/`. Credentials belong in Maven settings, never in this repository. Check all new coordinates for absence before upload; do not replace an existing release. The release set contains the seven reactor POMs, the four compiled jars and source jars, and the `spring-boot` and `spring-boot-test` test jars needed by the auto-configuration test dependency.

Consumers can import the fork BOM, or explicitly manage `spring-boot` and `spring-boot-autoconfigure` at `1.5.22.1.RELEASE`, all embedded Tomcat artifacts at `9.0.123`, and `javax.servlet:javax.servlet-api` at `4.0.1`. An application inheriting an older BOM must override the concrete dependency-management entries: changing a property in the application is insufficient when the parent has already resolved the old BOM values. Keep the existing application-specific security overrides and inspect the packaged classpath for old Boot, Tomcat and Servlet jars.

Roll back by reverting the application's dependency-management change and rebuilding with its previous known-working dependency set. Do not combine unpatched Boot 1.5.22 with Tomcat 9 as a rollback.
