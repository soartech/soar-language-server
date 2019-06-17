import * as path from 'path';
import { workspace, ExtensionContext } from 'vscode';
import {
	LanguageClient,
	LanguageClientOptions,
	ServerOptions,
} from 'vscode-languageclient';

let client: LanguageClient;

export function activate(context: ExtensionContext) {
    const serverEnabled = workspace.getConfiguration('soar').get<boolean>('languageServer.enabled');
    if (!serverEnabled) {
        return;
    }

    // Use the executable that the user has configured, or default to
    // the one that is packaged with the plugin.
    const serverCommand = workspace.getConfiguration('soar')
        .get<string>('languageServer.executablePath')
        || path.resolve(context.extensionPath, 'soar-language-server', 'bin', correctScriptName('soar-language-server'));

    let debugOptions = {};

    let serverOptions: ServerOptions = {
        run: { command: serverCommand },
        debug: { command: serverCommand, options: debugOptions }
    };

    let clientOptions: LanguageClientOptions = {
        documentSelector: ['soar', 'tcl'],
    };

    client = new LanguageClient(
        'soar',
        'Soar Language Support',
        serverOptions,
        clientOptions);

    client.start();
}

export function deactivate(): Thenable<void> | undefined {
    if (!client) {
        return undefined;
    }
    return client.stop();
}

function correctScriptName(binname: string): string {
    return binname + ((process.platform === 'win32') ? '.bat' : '');
}
