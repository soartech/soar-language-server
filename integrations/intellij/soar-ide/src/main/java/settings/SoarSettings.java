package settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;
import settings.gui.SettingsForm;

import javax.swing.*;

public class SoarSettings implements Configurable {

    private static SettingsForm settingsGUI;
    private static SoarSettings instance;

    public static SoarSettings getInstance() {
        if (instance == null) {
            instance = new SoarSettings();
        }
        return instance;
    }

    @Nls(capitalization = Nls.Capitalization.Title)
    @Override
    public String getDisplayName() {
        return "Soar IDE";
    }

    @Nullable
    @Override
    public String getHelpTopic() {
        return "settings.SoarSettings";
    }

    @Nullable
    @Override
    public JComponent createComponent() {
        settingsGUI = new SettingsForm();
        return settingsGUI.getRootPanel();
    }

    @Override
    public boolean isModified() {
        return settingsGUI.isModified();
    }

    @Override
    public void apply() throws ConfigurationException {
        settingsGUI.apply();
    }

    @Override
    public void reset() {
        settingsGUI.reset();
    }

    @Override
    public void disposeUIResources() {
        settingsGUI = null;
    }
}
