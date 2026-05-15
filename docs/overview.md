# Repository Overview

The repository is laid out as an Apache Ant build of the Tomcat Servlet/JSP
container. The top-level structure is:

- `java/` - Tomcat source code (server, container, connectors, utilities).
- `test/` - unit and integration test sources.
- `webapps/` - bundled web applications shipped with Tomcat (including the
  `docs` and `examples` webapps).
- `modules/` - additional Tomcat modules built alongside the core.
- `conf/` - default server configuration files (e.g. `server.xml`,
  `web.xml`, `catalina.properties`) used in the produced distribution.
- `bin/` - shell and batch scripts used by the build/distribution
  (`startup`, `shutdown`, `catalina`, etc.).
- `res/` - resources used by the build (installers, packaging assets,
  miscellaneous files).
- `build.xml` - the Ant build script that drives compilation, packaging and
  the `deploy` target documented in `BUILDING.txt`.
- `build.properties.default` - documented build properties; copy/override
  via a local `build.properties`.
- `catalog-info.yaml` - Backstage catalog metadata for this repository.

Top-level reference documents in the repository:

- `README.md` - high-level project description.
- `BUILDING.txt` - full build instructions (summarized in [Setup](setup.md)).
- `RUNNING.txt` - full run instructions (summarized in [Setup](setup.md)).
- `CONTRIBUTING.md` - how to contribute patches and pull requests.
- `RELEASE-NOTES` - notes for the current release line.
- `NOTICE`, `LICENSE`, `KEYS` - Apache Software Foundation legal and
  release-signing assets.
