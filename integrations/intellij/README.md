IntelliJ support is provided through the 
[LSP Support](https://plugins.jetbrains.com/plugin/10209-lsp-support) plugin
on the JetBrains Plugins Repository.
This plugin can be installed via the Plugin Manager. 
Open plugin settings `Ctrl+Alt+S` -> `Plugins` -> `Browse Repositories...`.
Search for LSP Support by gtache and install.

The LSP Support plugin gives IntelliJ the ability to interface with
one or more language servers.  To add support for Soar, go to the
LSP Support plugin settings (`Ctrl+Alt+S` -> `Languages & Frameworks` -> 
`Language Server Protocol` -> `Server Definitions`) and add an Executable definition.

```
    Executable
    Extension: soar
    Path: absolute_path_to_executable
```
Apply the settings and open a .soar file.

> This assumes that you have built the language server and that it can
> be invoked as an executable. By default, `gradle install` will
> create an executable at
> `./build/install/soar-language-server/bin/soar-language-server`. On
> Windows, use the version with the `.bat` extension.
