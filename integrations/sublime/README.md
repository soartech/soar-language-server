Sublime support is provided through the
[LSP](https://packagecontrol.io/packages/LSP) package, and builds on
top of the [Soar
Tools](https://packagecontrol.io/packages/Soar%20Tools) package. Both
can be installed via [Package Control](https://packagecontrol.io/) -
open the command palette (`ctrl+shift+p` or `cmd+shift+p`) and enter
`Package Control: Install Package`.

The LSP package is pre-configured for a number of language servers. To
add support for Soar, enter `Preferences: LSP Settings` in the command
palette and add the following to the configuration for the LSP
package:

```json
{
    "clients" {
        "soarls": {
            "command": ["path/to/soar-language-server"],
            "languageId": "soar",
            "scopes": ["source.soar"],
            "syntaxes": ["soar"]
        }
    }
}
```

> This assumes that you have built the language server and that it can
> be invoked as an executable. By default, `gradle install` will
> create an executable at
> `./build/install/soar-language-server/bin/soar-language-server`. On
> Windows, use the version with the `.bat` extension.


Now you can open a Soar file and execute `LSP: Enable Language Server
in Project`, and you're all done.
