/* --------------------------------------------------------------------------------------------
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 * ------------------------------------------------------------------------------------------ */
import * as path from 'path';
import * as vscode from "vscode";
import * as fs from "fs";
import * as child_process from "child_process";

import {
	LanguageClient,
	LanguageClientOptions,
	ServerOptions,
    RevealOutputChannelOn,
} from 'vscode-languageclient';

let client: LanguageClient;
let outputChannel: vscode.OutputChannel;

export async function activate(context: vscode.ExtensionContext): Promise<void> {
    configureLanguage();
    loadConfigs(context);
    vscode.workspace.onDidChangeConfiguration(() => {
        loadConfigs(context);
    });
}

function loadConfigs(context: vscode.ExtensionContext) {
    if (client != null) return;

    const serverEnabled = vscode.workspace.getConfiguration("soar").get("languageServer.enabled");
    const executablePath = vscode.workspace.getConfiguration("soar").get<string>("languageServer.executablePath");

    if (serverEnabled && executablePath)
        activateLanguageServer(context, executablePath);
    else {
        console.info("Skipping language server activation since 'soar.languageServer.enabled' is false");
    }
}

export function deactivate(): void {}

function configureLanguage(): void {
    vscode.languages.setLanguageConfiguration("soar", {
        // indentationRules: {
        //     // ^(.*\*/)?\s*\}.*$
        //     decreaseIndentPattern: /^(.*\*\/)?\s*\}.*$/,
        //     // ^.*\{[^}"']*$
        //     increaseIndentPattern: /^.*\{[^}"']*$/
        // },
        // wordPattern: /(-?\d*\.\d\w*)|([^\`\~\!\@\#\%\^\&\*\(\)\-\=\+\[\{\]\}\\\|\;\:\'\"\,\.\<\>\/\?\s]+)/g,
        // onEnterRules: [
        //     {
        //         // e.g. /** | */
        //         beforeText: /^\s*\/\*\*(?!\/)([^\*]|\*(?!\/))*$/,
        //         afterText: /^\s*\*\/$/,
        //         action: { indentAction: vscode.IndentAction.IndentOutdent, appendText: ' * ' }
        //     },
        //     {
        //         // e.g. /** ...|
        //         beforeText: /^\s*\/\*\*(?!\/)([^\*]|\*(?!\/))*$/,
        //         action: { indentAction: vscode.IndentAction.None, appendText: ' * ' }
        //     },
        //     {
        //         // e.g.  * ...|
        //         beforeText: /^(\t|(\ \ ))*\ \*(\ ([^\*]|\*(?!\/))*)?$/,
        //         action: { indentAction: vscode.IndentAction.None, appendText: '* ' }
        //     },
        //     {
        //         // e.g.  */|
        //         beforeText: /^(\t|(\ \ ))*\ \*\/\s*$/,
        //         action: { indentAction: vscode.IndentAction.None, removeText: 1 }
        //     },
        //     {
        //         // e.g.  *-----*/|
        //         beforeText: /^(\t|(\ \ ))*\ \*[^/]*\*\/\s*$/,
        //         action: { indentAction: vscode.IndentAction.None, removeText: 1 }
        //     }
        // ]
    });
}

async function activateLanguageServer(context: vscode.ExtensionContext, executablePath: string) {
    // LOG.info('Activating Soar language server...');
    let barItem = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left);
    context.subscriptions.push(barItem);
    barItem.text = '$(sync) Activating Soar language server...';
    barItem.show();

    let javaExecutablePath = findJavaExecutable('java');

    if (javaExecutablePath == null) {
        vscode.window.showErrorMessage("Couldn't locate java in $JAVA_HOME or $PATH");
        barItem.dispose();
        return;
    }

    // Options to control the language client
    let clientOptions: LanguageClientOptions = {
        // Register the server for java documents
        documentSelector: ['soar'],
        synchronize: {
            // Synchronize the setting section 'kotlin' to the server
            // NOTE: this currently doesn't do anything
            configurationSection: 'soar',
            // Notify the server about file changes to 'javaconfig.json' files contain in the workspace
            // TODO this should be registered from the language server side
            fileEvents: [
                vscode.workspace.createFileSystemWatcher('**/*.soar'),
            ]
        },
        outputChannelName: 'Soar',
        revealOutputChannelOn: RevealOutputChannelOn.Never
    }
    
    let args: string[];
    args = [];

    // TODO: Implement
    // Ensure that start script can be executed
    // if (isOSUnixoid()) {
    //     child_process.exec("chmod +x " + startScriptPath);
    // }

    let serverOptions: ServerOptions = {
        command: executablePath,
        args: args,
        options: { cwd: vscode.workspace.rootPath }
    }

    // LOG.info("Launching {} with args {}", startScriptPath, args.join(' '));

    // Create the language client and start the client.
    client = new LanguageClient('soar', 'Soar Language Server', serverOptions, clientOptions);
    let languageClientDisposable = client.start();

    // Push the disposable to the context's subscriptions so that the
    // client can be deactivated on extension deactivation
    context.subscriptions.push(languageClientDisposable);
    await client.onReady();
    
    barItem.dispose();
}

function findJavaExecutable(rawBinname: string): string {
	let binname = correctBinname(rawBinname);

	// First search java.home setting
    let userJavaHome = vscode.workspace.getConfiguration('java').get('home') as string;

	if (userJavaHome != null) {
        // LOG.debug("Looking for Java in java.home (settings): {}", userJavaHome);

        let candidate = findJavaExecutableInJavaHome(userJavaHome, binname);

        if (candidate != null)
            return candidate;
	}

	// Then search each JAVA_HOME
    let envJavaHome = process.env['JAVA_HOME'];

	if (envJavaHome) {
        // LOG.debug("Looking for Java in JAVA_HOME (environment variable): {}", envJavaHome);

        let candidate = findJavaExecutableInJavaHome(envJavaHome, binname);

        if (candidate != null)
            return candidate;
	}

	// Then search PATH parts
	if (process.env['PATH'] && process.env['PATH'] !== undefined) {
        let env_path = process.env['PATH'];
        // LOG.debug("Looking for Java in PATH");
        if (env_path !== undefined) {
            let pathparts = env_path.split(path.delimiter);
            for (let i = 0; i < pathparts.length; i++) {
                let binpath = path.join(pathparts[i], binname);
                if (fs.existsSync(binpath)) {
                    return binpath;
                }
            }
        }
        
	}

    // Else return the binary name directly (this will likely always fail downstream)
    // LOG.debug("Could not find Java, will try using binary name directly");
	return binname;
}

function correctBinname(binname: string): string {
    return binname + ((process.platform === 'win32') ? '.exe' : '');
}

function correctScriptName(binname: string): string {
    return binname + ((process.platform === 'win32') ? '.bat' : '');
}

function findJavaExecutableInJavaHome(javaHome: string, binname: string): string | null {
    let workspaces = javaHome.split(path.delimiter);

    for (let i = 0; i < workspaces.length; i++) {
        let binpath = path.join(workspaces[i], 'bin', binname);

        if (fs.existsSync(binpath))
            return binpath;
    }

    return null;
}
