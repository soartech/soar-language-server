# Soar-IDE

This extension as support for soar language through the use of the Language Server Protocol.  Usage requires the Soar Language Server to function.

## Features

Support of the Language Server Protocol for use with the Soar Language Server.

## Requirements

This assumes that you have built the language server and that it can
be invoked as an executable. By default, `gradle install` will
create an executable at
`./build/install/soar-language-server/bin/soar-language-server`. On
Windows, use the version with the `.bat` extension.

## Extension Settings

This extension contributes the following settings:

* `soar.langaugeServer.enable`: enable/disable language server support for `.soar` files
* `soar.languageServer.executablePath`: Absolute path to the Soar Language Server executable
* `soar.trace.server`: Traces the communication between VS Code and the language server
* `soar.maxNumberOfProblems`: Controls the maximum number of problems produced by the server.

## Known Issues



## Release Notes


### 0.0.1

Initial version with Soar Language Server LSP Support.
