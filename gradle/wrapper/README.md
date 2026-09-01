# Verified Gradle bootstrap

ApexTuner pins Gradle **9.5.0**. This package does not contain a generated `gradle-wrapper.jar`; instead `gradlew` and `gradlew.bat` perform a first-run bootstrap of the official Gradle 9.5.0 binary distribution.

Security properties:

- download URL: `https://services.gradle.org/distributions/gradle-9.5.0-bin.zip`;
- expected SHA-256: `553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746`;
- the archive is rejected before extraction if the checksum does not match;
- the same checksum is pinned in `gradle-wrapper.properties` as `distributionSha256Sum`;
- if a canonical Gradle 9.5.0 `gradle-wrapper.jar` is added, the project validator accepts it only when its official SHA-256 is `497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7`.

Android Studio can import the project normally and use the Gradle version declared by the project. For command-line builds, use `./gradlew` (macOS/Linux) or `gradlew.bat` (Windows).

If a canonical Gradle Wrapper JAR is required by an organization, regenerate it from a trusted Gradle 9.5.0 installation with `gradle wrapper --gradle-version 9.5.0 --distribution-type bin`; keep the same `distributionSha256Sum`.
