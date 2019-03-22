package settings;

import com.github.gtache.lsp.client.LanguageClientImpl;
import com.github.gtache.lsp.client.connection.ProcessStreamConnectionProvider;
import com.github.gtache.lsp.client.connection.StreamConnectionProvider;
import com.github.gtache.lsp.client.languageserver.serverdefinition.ExeLanguageServerDefinition;
import com.github.gtache.lsp.client.languageserver.serverdefinition.LanguageServerDefinition;
import com.intellij.ide.plugins.PluginManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.extensions.PluginDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import scala.collection.JavaConverters;
import scala.collection.Map;
import scala.collection.convert.Wrappers;

import java.io.File;
import java.util.HashMap;
import java.util.Set;


@State(
        name = "settings.SoarState",
        storages = {
                @Storage("soarIDE.xml")
        }
)
public class SoarState implements PersistentStateComponent<SoarState> {

    public String languageServerExecutablePath;

    public SoarState() {
        languageServerExecutablePath = "";
    }


    public static SoarState getInstance() {
        return ServiceManager.getService(SoarState.class);
    }

    @Nullable
    @Override
    public SoarState getState() {
        return this;
    }

    @Override
    public void loadState(@NotNull SoarState state) {
        XmlSerializerUtil.copyBean(state, this);
    }

    public String getLanguageServerExecutablePath() {
        return languageServerExecutablePath;
    }

    public void setLanguageServerExecutablePath(String languageServerExecutablePath) {
        this.languageServerExecutablePath = languageServerExecutablePath;
        registerServer();
    }

    public void registerServer() {
        if(!checkIfExecutableExists()) return;

        System.out.println("Register Server");
        stopServers();
        LanguageServerDefinition.register(new ExeLanguageServerDefinition(
                "soar",
                this.languageServerExecutablePath,
                new String[0]));
    }

    private boolean checkIfExecutableExists() {
        File executableFile = new File(this.languageServerExecutablePath);
        if (!executableFile.exists() || executableFile.isDirectory()) {
            ApplicationManager.getApplication().invokeLater(() ->
                    Messages.showErrorDialog(
                            String.format("Server Executable Path: '%s' is incorrect.\nPlease specify correct executable path in Soar IDE Settings", this.languageServerExecutablePath),
                            "Soar Language Server Error"));
            return false;
        }

        askUserToRestart();
        return true;
    }

    private void askUserToRestart() {
        ApplicationManager.getApplication().invokeLater(() -> {
            int result = Messages.showOkCancelDialog("Due to current limitations of the plugin, the soar language server and client can only be started during plugin startup.\nShall we restart Intellij now?",
                    "Intellij IDE Restart Required", "Restart", "Cancel", null);
            if (result == Messages.OK) {
                ApplicationManager.getApplication().restart();
            }
        });
    }

    private void stopServers() {
        Set<LanguageServerDefinition> test = JavaConverters.setAsJavaSet(LanguageServerDefinition.getAllDefinitions());
        for (LanguageServerDefinition def : test) {
            Map<String, StreamConnectionProvider> test1 = def.com$github$gtache$lsp$client$languageserver$serverdefinition$LanguageServerDefinition$$streamConnectionProviders();
            for (String key : JavaConverters.setAsJavaSet(test1.keySet())) {
                def.removeMappedExtension("soar");
                def.stop(key);
            }
        }
    }

    private void reloadWorkspace() {
        ApplicationManager.getApplication().invokeLater(() ->
            VirtualFileManager.getInstance().syncRefresh());
    }
}
