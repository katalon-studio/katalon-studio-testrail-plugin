package com.katalon.plugin.testrail;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.jface.resource.FontDescriptor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import com.katalon.platform.api.exception.CryptoException;
import com.katalon.platform.api.exception.InvalidDataTypeFormatException;
import com.katalon.platform.api.exception.ResourceException;
import com.katalon.platform.api.preference.PluginPreference;
import com.katalon.platform.api.service.ApplicationManager;
import com.katalon.platform.api.ui.UISynchronizeService;
import com.katalon.plugin.components.HelpComposite;

import java.util.LinkedHashMap;
import java.util.Map;

public class TestRailPreferencePage extends PreferencePage implements TestRailComponent {

    private Button chckEnableIntegration;

    private Group grpAuthentication;

    private Text txtUsername;

    private Text txtPassword;

    private Text txtUrl;

    private Text txtProject;

    private Composite container;

    private Button btnTestConnection;

    private Label lblConnectionStatus;

    private Thread thread;

    private TestRailPropertyMapComposite propertyMapComposite;

    @Override
    protected Control createContents(Composite composite) {
        container = new Composite(composite, SWT.NONE);
        container.setLayout(new GridLayout(1, false));

        chckEnableIntegration = new Button(container, SWT.CHECK);
        chckEnableIntegration.setText("Using TestRail");

        Composite passEncryptComposite = new Composite(container, SWT.NONE);
        GridLayout glPassEncrypt = new GridLayout(2, false);
        passEncryptComposite.setLayout(glPassEncrypt);
        Label warningLbl = new Label(passEncryptComposite, SWT.NONE);
        warningLbl.setText(TestRailConstants.LBL_WARNING_PASSWORD);
        FontDescriptor fontDescriptor = FontDescriptor.createFrom(warningLbl.getFont());
        warningLbl.setFont(fontDescriptor.setStyle(SWT.ITALIC).createFont(warningLbl.getDisplay()));
        new HelpComposite(passEncryptComposite, TestRailConstants.LINK_PASSWORD_ENCRYPT);
        
        grpAuthentication = new Group(container, SWT.NONE);
        grpAuthentication.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        GridLayout glAuthentication = new GridLayout(2, false);
        glAuthentication.horizontalSpacing = 15;
        glAuthentication.verticalSpacing = 10;
        grpAuthentication.setLayout(glAuthentication);
        grpAuthentication.setText("Authentication");

        createLabel("URL");
        txtUrl = createTextbox();

        createLabel("Username");
        txtUsername = createTextbox();

        createLabel("Password");
        txtPassword = createPasswordTextbox();

        createLabel("Project");
        txtProject = createTextbox();

        btnTestConnection = new Button(grpAuthentication, SWT.PUSH);
        btnTestConnection.setText("Test Connection");
        btnTestConnection.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                testTestRailConnection(
                        txtUsername.getText(),
                        txtPassword.getText(),
                        txtUrl.getText(),
                        txtProject.getText()
                );
            }
        });

        lblConnectionStatus = new Label(grpAuthentication, SWT.WRAP);
        lblConnectionStatus.setText("");
        lblConnectionStatus.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, true, 1, 1));

        propertyMapComposite = new TestRailPropertyMapComposite(container);

        handleControlModifyEventListeners();
        initializeInput();
        
        return container;
    }

    private Text createTextbox() {
        Text text = new Text(grpAuthentication, SWT.BORDER);
        GridData gridData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        gridData.widthHint = 200;
        text.setLayoutData(gridData);
        return text;
    }

    private Text createPasswordTextbox(){
        Text text = new Text(grpAuthentication, SWT.PASSWORD | SWT.BORDER);
        GridData gridData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        gridData.widthHint = 200;
        text.setLayoutData(gridData);
        return text;
    }

    private void createLabel(String text) {
        Label label = new Label(grpAuthentication, SWT.NONE);
        label.setText(text);
        GridData gridData = new GridData(SWT.LEFT, SWT.TOP, false, false);
        label.setLayoutData(gridData);
    }

    private void testTestRailConnection(String username, String password, String url, String project) {
        btnTestConnection.setEnabled(false);
        lblConnectionStatus.setForeground(lblConnectionStatus.getDisplay().getSystemColor(SWT.COLOR_DARK_YELLOW));
        lblConnectionStatus.setText("Connecting...");
        lblConnectionStatus.requestLayout();
        thread = new Thread(() -> {
            try {
                // test connection here
                TestRailConnector connector = new TestRailConnector(url, username, password);
                connector.getProject(project);

                syncExec(() -> {
                    lblConnectionStatus
                            .setForeground(lblConnectionStatus.getDisplay().getSystemColor(SWT.COLOR_DARK_GREEN));
                    lblConnectionStatus.setText("Succeeded!");
                    lblConnectionStatus.requestLayout();
                });
            } catch (Exception e) {
                System.err.println("Cannot connect to TestRail.");
                e.printStackTrace(System.err);
                syncExec(() -> {
                    lblConnectionStatus
                            .setForeground(lblConnectionStatus.getDisplay().getSystemColor(SWT.COLOR_DARK_RED));
                    lblConnectionStatus.setText("Failed: " + e.getMessage());
                    lblConnectionStatus.requestLayout();
                });
            } finally {
                syncExec(() -> btnTestConnection.setEnabled(true));
            }
        });
        thread.start();
    }

    void syncExec(Runnable runnable) {
        if (lblConnectionStatus != null && !lblConnectionStatus.isDisposed()) {
            ApplicationManager.getInstance()
                    .getUIServiceManager()
                    .getService(UISynchronizeService.class)
                    .syncExec(runnable);
        }
    }

    private void handleControlModifyEventListeners() {
        chckEnableIntegration.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                boolean enabled = chckEnableIntegration.getSelection();
                recursiveSetEnabled(grpAuthentication, enabled);
                recursiveSetEnabled(propertyMapComposite, enabled);
            }
        });
    }

    public static void recursiveSetEnabled(Control ctrl, boolean enabled) {
        if (ctrl instanceof Composite) {
            Composite comp = (Composite) ctrl;
            for (Control c : comp.getChildren()) {
                recursiveSetEnabled(c, enabled);
                c.setEnabled(enabled);
            }
        } else {
            ctrl.setEnabled(enabled);
        }
    }

    @Override
    public boolean performOk() {
        try {
            PluginPreference pluginStore = getPluginStore();
            
            if (!super.isControlCreated()) {
                return super.performOk();
            }
            
            pluginStore.setBoolean(TestRailConstants.PREF_TESTRAIL_ENABLED, chckEnableIntegration.getSelection());
            pluginStore.setString(TestRailConstants.PREF_TESTRAIL_USERNAME, txtUsername.getText());
            pluginStore.setString(TestRailConstants.PREF_TESTRAIL_URL, txtUrl.getText());
            pluginStore.setString(TestRailConstants.PREF_TESTRAIL_PROJECT, txtProject.getText());
            try {
                pluginStore.setString(TestRailConstants.PREF_TESTRAIL_PASSWORD, txtPassword.getText(), true);
            } catch (CryptoException e) {
                // Cannot encrypt the password
                e.printStackTrace();
            }
            pluginStore.setBoolean(TestRailConstants.IS_ENCRYPTION_MIGRATED, true);

            pluginStore.setString(TestRailConstants.PREF_TESTRAIL_CUSTOM_FIELD_MAPPINGS,
                    JSONObject.toJSONString(propertyMapComposite.getPropertyMap()));
            pluginStore.save();

            return super.performOk();
        } catch (ResourceException e) {
            MessageDialog.openWarning(getShell(), "Warning", "Unable to update TestRail Integration Settings.");
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private void initializeInput() {
        try {
            PluginPreference pluginStore = getPluginStore();
            try {
                TestRailHelper.doEncryptionMigrated(pluginStore);
            } catch (CryptoException | ResourceException e) {
                MessageDialog.openError(getShell(), "Error", e.getMessage());
            }
            chckEnableIntegration.setSelection(pluginStore.getBoolean(TestRailConstants.PREF_TESTRAIL_ENABLED, false));
            chckEnableIntegration.notifyListeners(SWT.Selection, new Event());

            txtUsername.setText(pluginStore.getString(TestRailConstants.PREF_TESTRAIL_USERNAME, ""));
            txtUrl.setText(pluginStore.getString(TestRailConstants.PREF_TESTRAIL_URL, ""));
            txtProject.setText(pluginStore.getString(TestRailConstants.PREF_TESTRAIL_PROJECT, ""));
            try {
                txtPassword.setText(pluginStore.getString(TestRailConstants.PREF_TESTRAIL_PASSWORD, "", true));
            } catch (InvalidDataTypeFormatException | CryptoException e) {
                // Cannot decrypt the password
                e.printStackTrace();
            }

            propertyMapComposite.setInput(loadPropertyMap(pluginStore));

            container.layout(true, true);
        } catch (ResourceException e) {
            MessageDialog.openWarning(getShell(), "Warning", "Unable to update TestRail Integration Settings.");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> loadPropertyMap(PluginPreference pluginStore) {
        JSONParser parser = new JSONParser();
        try {
            String propertyMapString = pluginStore.getString(TestRailConstants.PREF_TESTRAIL_CUSTOM_FIELD_MAPPINGS,
                    "{}");
            Map<String, Map<String, Object>> propertyMap = (Map<String, Map<String, Object>>) parser.parse(propertyMapString);            
            return new LinkedHashMap<String, Map<String, Object>>(propertyMap);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return new LinkedHashMap<String, Map<String, Object>>();
    }
}
