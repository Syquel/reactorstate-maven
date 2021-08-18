# reactorstate-maven
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=Syquel_reactorstate-maven&metric=alert_status)](https://sonarcloud.io/dashboard?id=Syquel_reactorstate-maven) 
![CI Workflow](https://github.com/Syquel/reactorstate-maven/workflows/CI%20Workflow/badge.svg)
[![Maven Central](https://img.shields.io/maven-central/v/de.syquel.maven.reactorstate/reactorstate-maven-project.svg?label=Maven%20Central)](https://search.maven.org/search?q=g:de.syquel.maven.reactorstate)

`reactorstate-maven` is a collection of tools to enable incremental Maven builds in development and CI / CD environments.  
It introduces and reuses state between Maven builds to improve build and development workflows.

## Table of Contents
* [Motivation](#motivation)
  * [CI / CD Environment](#ci--cd-environment)
  * [Local Development Environment](#local-development-environment)
* [Usage](#usage)
  * [reactorstate-maven-plugin](#reactorstate-maven-plugin)
  * [reactorstate-maven-extension](#reactorstate-maven-extension)
    * [Local Usage](#local-usage)
    * [Project-wide Usage](#project-wide-usage)
* [Further Reading](#further-reading)

## Motivation
### CI / CD Environment
Usually it is required to build Maven projects and their assets such as JavaDoc and the Maven Site in one go:  
```bash
mvn clean verify site
```

But in most cases it is desired to execute only a subset of Maven lifecycle steps, e.g. dependent on the Git branch and whether it is a release or snapshot build.  
Therefore, a better approach would be to be able to invoke Maven build steps incrementally in the following manner:  
```bash
mvn verify
mvn sonar-maven-plugin:sonar

# only executed on the development branch and Git tags
mvn site

# only executed on a Git tag
mvn gpg:sign

# only executed on a Git tag
mvn deploy:deploy
```
Unfortunately the Maven build for these commands will fail in a multi-module project, because Maven does not remember previous runs
and is therefore not able to find generated artifacts such as generated Jars in subsequent executions.

This may be circumvented by installing the Maven project to the local Maven repository first by utilizing `mvn install` instead of `mvn verify`,
but that may prove fatal in not entirely isolated build environments where other builds might rely on such unstable artifacts.

### Local Development Environment
In local development environments with Maven sub-modules with inter-module dependencies it is required to rebuild the whole Maven project
instead of only the sub-module a developer is currently working on and actually wants to build:  
```bash
mvn test
```
This usually takes time and a lot of other resources which may be spent in a better manner.

This again may be circumvented by installing the Maven project to the local Maven repository first, but at the cost at polluting it and
most probably causing issues in the future.  

The preferred approach would be to be able to execute Maven lifecycle steps directly on a Maven sub-module without having to execute it on the whole project:  
```bash
cd mymodule2
mvn test
```
IDEs like Eclipse and IntelliJ IDEA execute Maven commands for submodules in this manner and
cannot build those submodules because of missing dependencies on other modules in the same project.

## Usage
`reactorstate-maven` consists of the Maven Core Extension `reactorstate-maven-extension`, the Maven Plugin `reactorstate-maven-plugin`,
and the underlying library `reactorstate-common`.

### reactorstate-maven-plugin
The `reactorstate-maven-plugin` is the recommended solution for **CI / CD environments** to save and restore states of Maven builds between lifecycle executions:  
```bash
mvn verify reactorstate:save
mvn reactorstate:restore sonar-maven-plugin:sonar reactorstate:save
mvn reactorstate:restore site reactorstate:save
mvn reactorstate:restore gpg:sign reactorstate:save
mvn reactorstate:restore deploy:deploy
```

Declaring the plugin in the `pom.xml` of your Maven project is discouraged to prevent inconsistencies.

### reactorstate-maven-extension
The `reactorstate-maven-extension` is the recommended solution for **local development environments** to automatically enable building of Maven sub-modules.  
This Maven Core Extension additionally provides the same functionality as the `reactorstate-maven-plugin` automatically without having to invoke it explicitly.  

#### Local Usage
To utilize the `reactorstate-maven-extension` only locally download the shaded version via:  
```bash
mvn dependency:get -Dartifact=de.syquel.maven.reactorstate:reactorstate-maven-extension:1.0-SNAPSHOT:jar:shaded
```
You may then either copy it to your local Maven Extension directory located at `${MAVEN_HOME}/lib/ext/`  
or set the Maven property `maven.ext.class.path=${PATH_TO_THE_EXTENSION}` globally as environment variable or directly in your IDE.

#### Project-wide Usage
To utilize the `reactorstate-maven-extension` automatically in the project for all developers you may add it as a Maven Core extension within the project.  
It must be added to the file `.mvn/extension.xml` within your Maven project directory:
```xml
<extensions xmlns="http://maven.apache.org/EXTENSIONS/1.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
           xsi:schemaLocation="http://maven.apache.org/EXTENSIONS/1.0.0 http://maven.apache.org/xsd/core-extensions-1.0.0.xsd">
    <extension>
        <groupId>de.syquel.maven.reactorstate</groupId>
        <artifactId>reactorstate-maven-extension</artifactId>
        <version>1.0-SNAPSHOT</version>
    </extension>
</extensions>
```

Declaring the extension in the `pom.xml` of your Maven project is not possible, because it must be executed before the project is actually being read by Maven.

## Further Reading
[Documentation](https://reactorstate.syquel.de)
