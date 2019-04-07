[![Build Status](https://travis-ci.com/soartech/soar-language-server.svg?branch=master)](https://travis-ci.com/soartech/soar-language-server)
[![Build status](https://ci.appveyor.com/api/projects/status/odm1cx7f8phh99pw/branch/master?svg=true)](https://ci.appveyor.com/project/soartech/soar-language-server/branch/master)
[![Dependabot Status](https://api.dependabot.com/badges/status?host=github&repo=soartech/soar-language-server)](https://dependabot.com)

# Soar Language Server

This project provides editor/IDE support for the [Soar
language](https://soar.eecs.umich.edu/) via the [Language Server
Protocol](https://langserver.org/).

# Building

First, clone this repository:

```bash
$ git clone https://github.com/soartech/soar-language-server
$ cd soar-language-server
```

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
$ ./gradlew installDist
```

This will create an executable at
`./build/install/soar-language-server/bin/soar-language-server`.

# Clients

We don't yet have any officially supported plugins. Work in progress
plugins can be found in the [integrations](./integrations) directory.
