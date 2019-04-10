package com.soartech.soarls;

import java.io.PrintWriter;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;

public class App {
  public static void main(String[] args) {
    Server server = new Server();
    Launcher<LanguageClient> launcher =
        LSPLauncher.createServerLauncher(
            server, System.in, System.out, false, new PrintWriter(System.err));
    server.connect(launcher.getRemoteProxy());
    launcher.startListening();
  }
}
