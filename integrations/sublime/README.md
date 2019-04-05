# Soar Language Server for Sublime

Sublime support is provided through the
[LSP](https://packagecontrol.io/packages/LSP) package, and builds on
top of the [Soar
Tools](https://packagecontrol.io/packages/Soar%20Tools) package. Both
can be installed via [Package Control](https://packagecontrol.io/):

## Installation

Execute the following commands in the command palette (`ctrl+shift+p`
or `cmd+shift+p`):

1. Clone this repository and build the language server (see
   instructions in the root README).
1. `Package Control: Install Package` > `LSP`
1. `Package Control: Install Package` > `Soar Tools`

## Configuration

The LSP package is pre-configured for a number of language servers. To
add support for Soar, enter `Preferences: LSP Settings` in the command
palette. This will show the default settings in a read-only panel on
the left, and an editable panel for user settings on the right. Add
the following to your user settings, replacing the `command` field
with the path to your language server executable:

```json
{
    "clients": {
        "soarls": {
            "command": ["path/to/soar-language-server"],
            "languageId": "soar",
            "scopes": ["source.soar", "source.tcl"],
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

## Usage

Now you can open a Soar file and execute `LSP: Enable Language Server
in Project`, and you're all done.
