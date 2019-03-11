package com.soartech.soarls;

import com.soartech.soarls.Server;
import java.io.PrintWriter;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;

public class App {
    public static void main(String[] args) {
        LanguageServer server = new Server();
        Launcher<LanguageClient> launcher = LSPLauncher.createServerLauncher(
            server,
            System.in,
            System.out,
            false,
            new PrintWriter(System.err));
        launcher.startListening();
    }
}
