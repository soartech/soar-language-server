Visual Studio Code support is provided through the Soar-IDE extension.

As the extension is currently not uploaded to the VSCode Marketplace, manual installation is required.

## Manual Installation
1. Open the extensions menu from the sidebar or `Ctrl+Shift+X`
2. Open the dropdown `...` in the top-right
3. Click `Install from VSIX...`
4. Locate `integrations/vscode/soar-ide-0.0.1.vsix`
5. Open settings and fill in `soar.languageServer.executablePath`

> This assumes that you have built the language server and that it can
> be invoked as an executable. By default, `gradle install` will
> create an executable at
> `./build/install/soar-language-server/bin/soar-language-server`. On
> Windows, use the version with the `.bat` extension.


## Packaging and Publishing Extension
1. Install Visual Studio Code Extensions npm package<br/>
`npm install -g vsce`
2. Change working directory to vscode extension<br/>
`cd integrations/vscode/soar-ide/`
3. Package Extension.  This will create a .vsix file that can be manually installed or uploaded to the marketplace.<br/>
`vsce package`
4. Publish Extension if already on VS Code MarketPlace
`vsce publish`
