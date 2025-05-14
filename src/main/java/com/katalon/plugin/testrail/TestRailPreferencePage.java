package com.katalon.plugin.testrail;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.jface.resource.FontDescriptor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.events.VerifyEvent;
import org.eclipse.swt.events.VerifyListener;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;

import com.katalon.platform.api.exception.CryptoException;
import com.katalon.platform.api.exception.InvalidDataTypeFormatException;
import com.katalon.platform.api.exception.ResourceException;
import com.katalon.platform.api.preference.PluginPreference;
import com.katalon.platform.api.service.ApplicationManager;
import com.katalon.platform.api.ui.UISynchronizeService;
import com.katalon.plugin.components.HelpComposite;

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

    private Group grpCustomFieldMappings;
    private Table customFieldMappingsTable;
    private ToolBar toolBar;
    private ToolItem btnAddMapping;
    private ToolItem btnDeleteMapping;
    private ToolItem btnClearMappings;

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

        // Add custom field mappings group after authentication group
        grpCustomFieldMappings = new Group(container, SWT.NONE);
        grpCustomFieldMappings.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        GridLayout glCustomFieldMappings = new GridLayout(1, false);
        glCustomFieldMappings.horizontalSpacing = 15;
        glCustomFieldMappings.verticalSpacing = 10;
        grpCustomFieldMappings.setLayout(glCustomFieldMappings);
        grpCustomFieldMappings.setText("Custom Field Mappings");

        // Create toolbar instead of button composite
        toolBar = new ToolBar(grpCustomFieldMappings, SWT.FLAT | SWT.RIGHT);
        toolBar.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        // Create tool items
        btnAddMapping = new ToolItem(toolBar, SWT.PUSH);
        btnAddMapping.setText("Add");
        btnAddMapping.setToolTipText("Add new mapping");
        setToolItemImage(btnAddMapping, "/icons/add_16.png");

        btnDeleteMapping = new ToolItem(toolBar, SWT.PUSH);
        btnDeleteMapping.setText("Delete");
        btnDeleteMapping.setToolTipText("Delete selected mappings");
        setToolItemImage(btnDeleteMapping, "/icons/delete_16.png");

        btnClearMappings = new ToolItem(toolBar, SWT.PUSH);
        btnClearMappings.setText("Clear");
        btnClearMappings.setToolTipText("Clear all mappings");
        setToolItemImage(btnClearMappings, "/icons/clear_16.png");

        // Add tool item listeners
        btnAddMapping.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                TableItem item = new TableItem(customFieldMappingsTable, SWT.NONE);
                item.setText(new String[] { "", "", "" });
            }
        });

        btnDeleteMapping.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                int[] selectedIndices = customFieldMappingsTable.getSelectionIndices();
                for (int i = selectedIndices.length - 1; i >= 0; i--) {
                    customFieldMappingsTable.remove(selectedIndices[i]);
                }
            }
        });

        btnClearMappings.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                customFieldMappingsTable.removeAll();
            }
        });

        // Create table with grid style
        customFieldMappingsTable = new Table(grpCustomFieldMappings, 
            SWT.BORDER | SWT.FULL_SELECTION | SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL);
        customFieldMappingsTable.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        customFieldMappingsTable.setHeaderVisible(true);
        customFieldMappingsTable.setLinesVisible(true); // Show grid lines

        // Create columns
        TableColumn nameColumn = new TableColumn(customFieldMappingsTable, SWT.NONE);
        nameColumn.setText("Name");
        nameColumn.setWidth(150);

        TableColumn typeColumn = new TableColumn(customFieldMappingsTable, SWT.NONE);
        typeColumn.setText("Type");
        typeColumn.setWidth(100);

        TableColumn valueColumn = new TableColumn(customFieldMappingsTable, SWT.NONE);
        valueColumn.setText("Value");
        valueColumn.setWidth(200);

        // Add 5 empty rows
        for (int i = 0; i < 5; i++) {
            TableItem item = new TableItem(customFieldMappingsTable, SWT.NONE);
            item.setText(new String[] { "", "", "" });
        }

        // Make table items editable
        customFieldMappingsTable.addListener(SWT.MouseDown, event -> {
            if (event.button == 1) { // Left click
                TableItem item = customFieldMappingsTable.getItem(new org.eclipse.swt.graphics.Point(event.x, event.y));
                if (item != null) {
                    int column = getColumnAtPosition(customFieldMappingsTable, event.x);
                    if (column != -1) {
                        editTableItem(item, column);
                    }
                }
            }
        });

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
                recursiveSetEnabled(grpCustomFieldMappings, enabled);
                toolBar.setEnabled(enabled);
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

            // Save custom field mappings as Map
            Map<String, Object> customFieldMappings = TestRailHelper.tableItemsToMap(customFieldMappingsTable.getItems());
            pluginStore.setString(TestRailConstants.PREF_TESTRAIL_CUSTOM_FIELD_MAPPINGS, 
                TestRailHelper.convertCustomFieldMappingsToString(customFieldMappings));
            
            pluginStore.save();
            return super.performOk();
        } catch (ResourceException e) {
            MessageDialog.openWarning(getShell(), "Warning", "Unable to update TestRail Integration Settings.");
            return false;
        }
    }

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

            // Initialize custom field mappings from Map
            String customFieldMappingsStr = pluginStore.getString(TestRailConstants.PREF_TESTRAIL_CUSTOM_FIELD_MAPPINGS, "");
            if (!customFieldMappingsStr.isEmpty()) {
                Map<String, Object> customFieldMappings = TestRailHelper.convertStringToCustomFieldMappings(customFieldMappingsStr);
                TestRailHelper.mapToTableItems(customFieldMappingsTable, customFieldMappings);
            }

            container.layout(true, true);
        } catch (ResourceException e) {
            MessageDialog.openWarning(getShell(), "Warning", "Unable to update TestRail Integration Settings.");
        }
    }

    private int getColumnAtPosition(Table table, int x) {
        int totalWidth = 0;
        TableColumn[] columns = table.getColumns();
        for (int i = 0; i < columns.length; i++) {
            totalWidth += columns[i].getWidth();
            if (x < totalWidth) {
                return i;
            }
        }
        return -1;
    }

    private void editTableItem(TableItem item, int column) {
        if (column == 1) { // Type column
            Combo combo = new Combo(customFieldMappingsTable, SWT.DROP_DOWN | SWT.READ_ONLY);
            String[] types = new String[] {
                TestRailHelper.TYPE_STRING,
                TestRailHelper.TYPE_NUMBER,
                TestRailHelper.TYPE_BOOLEAN
            };
            combo.setItems(types);
            
            // Set current value if it exists
            String currentType = item.getText(column);
            if (currentType != null && !currentType.isEmpty()) {
                combo.setText(currentType);
            } else {
                combo.setText(TestRailHelper.TYPE_STRING); // Default to String
            }
            
            // Position the combo box
            int columnX = 0;
            for (int i = 0; i < column; i++) {
                columnX += customFieldMappingsTable.getColumn(i).getWidth();
            }
            int itemY = item.getBounds().y;
            combo.setBounds(columnX, itemY, customFieldMappingsTable.getColumn(column).getWidth(), item.getBounds().height);

            combo.setFocus();
            
            // Add listeners for the combo box
            combo.addListener(SWT.FocusOut, event -> {
                String newType = combo.getText();
                if (TestRailHelper.isValidType(newType)) {
                    item.setText(column, newType);
                    // Validate and convert the value based on the new type
                    String value = item.getText(2);
                    Object typedValue = TestRailHelper.convertValueByType(value, newType);
                    item.setText(2, typedValue != null ? typedValue.toString() : "");
                }
                combo.dispose();
            });

            combo.addListener(SWT.Traverse, event -> {
                if (event.detail == SWT.TRAVERSE_RETURN) {
                    String newType = combo.getText();
                    if (TestRailHelper.isValidType(newType)) {
                        item.setText(column, newType);
                        // Validate and convert the value based on the new type
                        String value = item.getText(2);
                        Object typedValue = TestRailHelper.convertValueByType(value, newType);
                        item.setText(2, typedValue != null ? typedValue.toString() : "");
                    }
                    combo.dispose();
                } else if (event.detail == SWT.TRAVERSE_ESCAPE) {
                    combo.dispose();
                }
            });

            // Add selection listener for immediate update
            combo.addSelectionListener(new SelectionAdapter() {
                @Override
                public void widgetSelected(SelectionEvent e) {
                    String newType = combo.getText();
                    if (TestRailHelper.isValidType(newType)) {
                        item.setText(column, newType);
                        // Validate and convert the value based on the new type
                        String value = item.getText(2);
                        Object typedValue = TestRailHelper.convertValueByType(value, newType);
                        item.setText(2, typedValue != null ? typedValue.toString() : "");
                    }
                    combo.dispose();
                }
            });
        } else { // Name or Value column
            Text text = new Text(customFieldMappingsTable, SWT.BORDER);
            text.setText(item.getText(column));
            
            // Position the text editor
            int columnX = 0;
            for (int i = 0; i < column; i++) {
                columnX += customFieldMappingsTable.getColumn(i).getWidth();
            }
            int itemY = item.getBounds().y;
            text.setBounds(columnX, itemY, customFieldMappingsTable.getColumn(column).getWidth(), item.getBounds().height);

            text.setFocus();
            text.selectAll();

            // Add validation for value column
            if (column == 2) { // Value column
                text.addVerifyListener(new VerifyListener() {
                    @Override
                    public void verifyText(VerifyEvent e) {
                        String type = item.getText(1);
                        String newValue = text.getText().substring(0, e.start) + e.text + text.getText().substring(e.end);
                        
                        if (type.equals(TestRailHelper.TYPE_NUMBER)) {
                            try {
                                if (!newValue.isEmpty()) {
                                    Double.parseDouble(newValue);
                                }
                            } catch (NumberFormatException ex) {
                                e.doit = false;
                            }
                        } else if (type.equals(TestRailHelper.TYPE_BOOLEAN)) {
                            if (!newValue.isEmpty() && 
                                !newValue.equalsIgnoreCase("true") && 
                                !newValue.equalsIgnoreCase("false")) {
                                e.doit = false;
                            }
                        }
                    }
                });
            }

            text.addListener(SWT.FocusOut, event -> {
                String newValue = text.getText();
                if (column == 2) { // Value column
                    String type = item.getText(1);
                    Object typedValue = TestRailHelper.convertValueByType(newValue, type);
                    item.setText(column, typedValue != null ? typedValue.toString() : "");
                } else {
                    item.setText(column, newValue);
                }
                text.dispose();
            });

            text.addListener(SWT.Traverse, event -> {
                if (event.detail == SWT.TRAVERSE_RETURN) {
                    String newValue = text.getText();
                    if (column == 2) { // Value column
                        String type = item.getText(1);
                        Object typedValue = TestRailHelper.convertValueByType(newValue, type);
                        item.setText(column, typedValue != null ? typedValue.toString() : "");
                    } else {
                        item.setText(column, newValue);
                    }
                    text.dispose();
                } else if (event.detail == SWT.TRAVERSE_ESCAPE) {
                    text.dispose();
                }
            });
        }
    }

    private void setToolItemImage(ToolItem toolItem, String iconPath) {
        try {
            Image image = new Image(Display.getCurrent(), getClass().getResourceAsStream(iconPath));
            toolItem.setImage(image);
        } catch (Exception e) {
            // Image not found, continue without icon
            System.err.println("Could not load icon " + iconPath + ": " + e.getMessage());
        }
    }
}
