package settings.gui;

import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.ui.TextBrowseFolderListener;
import settings.SoarState;

import javax.swing.*;

public class SettingsForm {
    private final SoarState state = SoarState.getInstance();

    private com.intellij.openapi.ui.TextFieldWithBrowseButton serverPathInput;
    private JPanel rootPanel;

    public SettingsForm() {
        serverPathInput.addBrowseFolderListener(
                new TextBrowseFolderListener(
                        new FileChooserDescriptor(true, false, true, true, true, false).withShowHiddenFiles(true)));
        reset();
    }

    public JPanel getRootPanel() {
        return rootPanel;
    }

    public boolean isModified() {
        return !state.languageServerExecutablePath.equals(serverPathInput.getText());
    }

    public void apply() {
        state.setLanguageServerExecutablePath(serverPathInput.getText());
    }

    public void reset() {
        serverPathInput.setText(state.getLanguageServerExecutablePath());
    }
}
