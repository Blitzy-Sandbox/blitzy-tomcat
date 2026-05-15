# Setup

This page summarizes how to build and run Apache Tomcat from this source tree.
For full detail consult `BUILDING.txt` and `RUNNING.txt` at the root of the
repository.

## Building From Source

If you only need to run Tomcat you do not need to build it - a binary
distribution is available. Build from source when you want to modify or test
the codebase.

### 1. Install a Java Development Kit (JDK)

Install a JDK appropriate for the Tomcat version you are building (see
`BUILDING.txt` for the exact minimum version). Set the `JAVA_HOME` environment
variable to the JDK install directory.

Note: Tomcat includes a private copy of Apache Commons DBCP 2. DBCP's JDBC
interfaces frequently change in non-backwards compatible ways between Java SE
versions, so the build may fail on a Java version newer than the one listed
in `BUILDING.txt`.

### 2. Install Apache Ant

Install the Ant version required by `BUILDING.txt` or later from
<https://ant.apache.org/bindownload.cgi>. Set `ANT_HOME` to the install
directory and add `${ant.home}/bin` to your `PATH`.

### 3. Configure The Build

Create a `build.properties` file in the source root. At minimum set
`base.path` to a directory (outside the source tree) into which build
dependencies will be downloaded:

    # Default Base Path for Dependent Packages
    base.path=/home/me/some-place-to-download-to

The default value of `base.path` downloads dependencies under
`${user.home}/tomcat-build-libs`.

### 4. Run The Build

From the source root:

    ant

This invokes the `deploy` target in `build.xml`. On success a usable Tomcat
installation is produced under `output/build`, and the Tomcat documentation is
generated under `output/build/webapps/docs`.

Do not run the build as `root` - building and running Tomcat does not require
root privileges.

## Running Tomcat

Tomcat requires a Java Standard Edition Runtime Environment (JRE) at the
minimum version listed in `RUNNING.txt`. A full JDK works as well.

### Environment Variables

- `CATALINA_HOME` (required) - root of the binary distribution.
- `CATALINA_BASE` (optional) - root of an active configuration. Defaults to `CATALINA_HOME`.
- `JRE_HOME` or `JAVA_HOME` (one required) - location of the JRE or JDK.
- `CATALINA_OPTS` (optional) - extra Java options for the Tomcat process.
- `JAVA_OPTS` (optional) - options applied to start and stop commands.

The recommended place for these is a `setenv` script at
`$CATALINA_BASE/bin/setenv.sh` (or `setenv.bat` on Windows).

### Start Tomcat

On *nix:

    $CATALINA_HOME/bin/startup.sh

On Windows:

    %CATALINA_HOME%\bin\startup.bat

After startup the default web applications are reachable on
<http://localhost:8080/>.
