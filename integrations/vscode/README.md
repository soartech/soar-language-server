# Soar Language Support for VSCode

This extension provides support for the Soar language through the use
of the Language Server Protocol.

## Features

- **Tcl expansion**: The extension will create a file called
  `~tcl-expansion.soar`. Whenever the cursor is on a command that
  defines a production (such as `sp` or a Tcl macro that internall
  generates productions), this file will be updated with the raw Soar
  code that is generated.
- **Go to definition**: For Tcl variables and Tcl procedures.
- **Find references**: For Tcl variables and Tcl procedures.
- **Hover for Tcl variable values**
- **Hover for Tcl procedure documentation**: Hovering over a Tcl proc
  call will show the comments immediately preceding its definition.
- **Error and warning reporting**: All code is actually executed in an
  internal JSoar instance. If this produces any errors or warnings,
  these are captured and displayed within the IDE. The IDE attempts to
  continue past errors so that the analysis is as complete as
  possible.
- **Rename**: Tcl variables can be renamed, and the known instances
  are all updated.
- **Autocomplete**: A list of Tcl procs will be shown when typing
  top-level commands or `[`. A list of Tcl variables is shown after
  typing a `$`.
- **Code Folding**: Comments, rules, and Tcl procs can be folded.
- **Syntax highlighting**: Provided by Bryan DeGrendel's
  [Soar](https://marketplace.visualstudio.com/items?itemName=bdegrend.soar)
  extension.

## Project Setup

In order for the Soar LSP to know how to load your agent, you need to
create a `soarAgents.json` file at the root of your workspace. This
file defines the agents that are present in the workspace, and which
one to use as the default. A default is needed because there is
currently no way to resolve conflicts for things like "Go to
Definition" when two different agents define the same variable.

Here's an example:

```json
{
    "entryPoints": [
        {
            "path": "agent1/load.soar",
            "name": "agent1"
        },
        {
            "path": "agent2/load.soar",
            "name": "agent2"
        }
    ],
    "active": "agent1",
    "rhsFunctions": ["force-learn"]
}

```

The `entryPoints` list defines the name and start file for each
agent. There should be at least one entry in here. The `active` field
is the name of the agent to use as the default. This should match the
name of one of the entries in the `entryPoint` list.

The `rhsFunctions` list is optional. This is a list of right hand side
functions that should not produce warnings even though they are not
defined by default.

## Extension Settings

This extension contributes the following settings:

* `soar.langaugeServer.enable`: enable/disable language server support for `.soar` files
* `soar.languageServer.executablePath`: Absolute path to the Soar Language Server executable
* `soar.trace.server`: Traces the communication between VS Code and the language server
* `soar.maxNumberOfProblems`: Controls the maximum number of problems produced by the server.
