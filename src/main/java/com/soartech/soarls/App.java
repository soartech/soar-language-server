package com.soartech.soarls;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;

public class App {
  public static void main(String[] args) {
    Server server = new Server();
    Launcher<LanguageClient> launcher =
        LSPLauncher.createServerLauncher(
            server, exitOnClose(System.in), System.out, false, new PrintWriter(System.err));
    server.connect(launcher.getRemoteProxy());
    launcher.startListening();
  }

  /**
   * Wrap an InputStream such that the process will exit when the stream closes. This works around
   * an issue where some clients will detach the language server process when they shut down,
   * instead of killing it.
   */
  static InputStream exitOnClose(InputStream delegate) {
    return new InputStream() {
      @Override
      public int read() throws IOException {
        return exitIfNegative(delegate.read());
      }

      int exitIfNegative(int result) {
        if (result < 0) {
          System.err.println("System.in has closed; exiting");
          System.exit(0);
        }
        return result;
      }
    };
  }
}
