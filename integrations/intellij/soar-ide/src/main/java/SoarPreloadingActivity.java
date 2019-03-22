
import com.intellij.openapi.application.PreloadingActivity;
import com.intellij.openapi.progress.ProgressIndicator;
import org.jetbrains.annotations.NotNull;
import settings.SoarState;


public class SoarPreloadingActivity extends PreloadingActivity {

    public SoarPreloadingActivity() {
        super();
    }

    @Override
    public void preload(@NotNull ProgressIndicator indicator) {
        SoarState settings = SoarState.getInstance();
        settings.registerServer(true);
    }
}
