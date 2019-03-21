[![Build Status](https://travis-ci.com/soartech/soar-language-server.svg?branch=master)](https://travis-ci.com/soartech/soar-language-server)

# Soar Language Server

This project provides editor/IDE support for the [Soar
language](https://soar.eecs.umich.edu/) via the [Language Server
Protocol](https://langserver.org/).

# Building

We currently rely on a fork of JSoar which we include as a git
submodule, which must be initialized like so:

```bash
# The first time you clone this repository:
$ git submodule update --init

# If you pull changes that move JSoar to a new commit:
$ git submodule update
```

After the initial setup, the language server can be built with
Gradle. The Gradle wrapper script is included:

```bash
$ ./gradlew test
$ ./gradlew install
```

This will create an executable at
`./build/install/soar-language-server/bin/soar-language-server`.

# Clients

We don't yet have any officially supported plugins. Work in progress
plugins can be found in the [integrations](./integrations) directory.
