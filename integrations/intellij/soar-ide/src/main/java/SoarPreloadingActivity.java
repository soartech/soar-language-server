import com.intellij.openapi.application.PreloadingActivity;
import com.intellij.openapi.progress.ProgressIndicator;
import org.jetbrains.annotations.NotNull;
import com.github.gtache.lsp.client.languageserver.serverdefinition.LanguageServerDefinition;
import com.github.gtache.lsp.client.languageserver.serverdefinition.ExeLanguageServerDefinition;


public class SoarPreloadingActivity extends PreloadingActivity {

    public SoarPreloadingActivity() {
        super();
    }

    @Override
    public void preload(@NotNull ProgressIndicator indicator) {
        LanguageServerDefinition.register(new ExeLanguageServerDefinition(
                "soar",
                "",
                new String[0]));
    }
}
