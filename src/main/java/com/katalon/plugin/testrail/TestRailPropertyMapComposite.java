package com.katalon.plugin.testrail;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.eclipse.jface.layout.TableColumnLayout;
import org.eclipse.jface.viewers.CellEditor;
import org.eclipse.jface.viewers.ColumnWeightData;
import org.eclipse.jface.viewers.ComboBoxCellEditor;
import org.eclipse.jface.viewers.EditingSupport;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITableLabelProvider;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.jface.viewers.TextCellEditor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;

public class TestRailPropertyMapComposite extends Composite {
    private static final int COLUMN_NAME_INDEX = 0;
    private static final int COLUMN_TYPE_INDEX = 1;
    private static final int COLUMN_VALUE_INDEX = 2;
    private static final String[] COLUMN_NAMES = { "Name", "Type", "Value" };

    private static final String DEFAULT_DRIVER_PROPERTY_NAME = "property";

    private Table table;
    private TableViewer tableViewer;
    private ToolItem tltmAddProperty;
    private ToolItem tltmRemoveProperty;
    private ToolItem tltmClearProperty;

    // Sample:
    // { "host_name": {"type": "String", "value": "hostName" }}
    private Map<String, Map<String, Object>> propertyMap;

    enum PropertyValueType {
        // Ref: https://support.testrail.com/hc/en-us/articles/7373850291220-Configuring-custom-fields
        String, Boolean, Integer;

        public static String[] stringValues() {
            return Arrays.stream(PropertyValueType.values())
                .map(PropertyValueType::name)
                .toArray(String[]::new);
        }

        public static PropertyValueType fromValue(Object value) {
            if (value instanceof Integer) {
                return Integer;
            }
            if (value instanceof Boolean) {
                return Boolean;
            }
            return String;
        }

        public Object getDefaultValue() {
            switch (this) {
                case Boolean:
                    return new Boolean(true);
                case Integer:
                    return new Integer(0);
                default:
                    return "";
            }
        }
    }

    static class MapPropertyLabelProvider extends LabelProvider implements ITableLabelProvider {
        @Override
        public Image getColumnImage(Object element, int columnIndex) {
            return null;
        }

        @SuppressWarnings("unchecked")
        @Override
        public String getColumnText(Object element, int columnIndex) {
            if (element == null || !(element instanceof Entry)) {
                return "";
            }
            
            final Entry<String, Map<String, Object>> entry = (Entry<String, Map<String, Object>>) element;
            if (columnIndex == COLUMN_NAME_INDEX) {
                return String.valueOf(entry.getKey());
            }
            if (columnIndex == COLUMN_TYPE_INDEX) {
                return entry.getValue().get("type").toString();
            }
            if (columnIndex == COLUMN_VALUE_INDEX) {
                return entry.getValue().get("value").toString();
            }
            return null;
        }
    }

    static class MapPropertyTableViewerContentProvider implements ITreeContentProvider {
        @Override
        public Object[] getElements(Object inputElement) {
            if (inputElement == null || !(inputElement instanceof Map)) {
                return null;
            }
            Map<?, ?> propertyMap = (Map<?, ?>) inputElement;
            return propertyMap.entrySet().toArray();
        }

        @Override
        public Object[] getChildren(Object parentElement) {
            return null;
        }

        @Override
        public Object getParent(Object element) {
            return null;
        }

        @Override
        public boolean hasChildren(Object element) {
            return false;
        }
    }

    static class NameEditingSupport extends EditingSupport {
        private TableViewer tableViewer;

        public NameEditingSupport(TableViewer tableViewer) {
            super(tableViewer);
            this.tableViewer = tableViewer;
        }

        @Override
        protected CellEditor getCellEditor(Object element) {
            return new TextCellEditor(tableViewer.getTable());
        }

        @Override
        protected boolean canEdit(Object element) {
            return element instanceof Entry;
        }

        @Override
        protected Object getValue(Object element) {
            return ((Entry<?, ?>) element).getKey();
        }

        @SuppressWarnings("unchecked")
        @Override
        protected void setValue(Object element, Object newName) {            
            Entry<String, Map<String, Object>> entry = (Entry<String, Map<String, Object>>) element;
            String oldKey = entry.getKey();
            String newKey = (String)newName;

            if (oldKey.equals(newKey)) {
                return;
            }

            // Need to clone and copy the entries to maintain the order
            Map<String, Map<String, Object>> propertyMap = (Map<String, Map<String, Object>>) tableViewer.getInput();
            LinkedHashMap<String, Map<String, Object>> clonedPropertyMap = new LinkedHashMap<>(propertyMap);
            Map<String, Object> entryValue = propertyMap.get(oldKey);

            propertyMap.clear();
            
            for (Map.Entry<String, Map<String, Object>> clonedEntry : clonedPropertyMap.entrySet()) {
                if (clonedEntry.getKey().equals(oldKey)) {
                    propertyMap.put(newKey, entryValue);
                }
                else {
                    propertyMap.put(clonedEntry.getKey(), clonedEntry.getValue());
                }
            }

            tableViewer.refresh();
        }
    }

    static class TypeEditingSupport extends EditingSupport {
        private TableViewer tableViewer;

        public TypeEditingSupport(TableViewer tableViewer) {
            super(tableViewer);
            this.tableViewer = tableViewer;
        }

        @Override
        protected CellEditor getCellEditor(Object element) {
            return new ComboBoxCellEditor(tableViewer.getTable(), PropertyValueType.stringValues());
        }

        @Override
        protected boolean canEdit(Object element) {
            return element instanceof Entry;
        }

        @SuppressWarnings("unchecked")
        @Override
        protected Object getValue(Object element) {            
            Entry<String, Map<String, Object>> entry = (Entry<String, Map<String, Object>>) element;
            PropertyValueType valueType = PropertyValueType.fromValue(entry.getValue().get("type"));
            int index = Arrays.asList(PropertyValueType.values()).indexOf(valueType);
            return index >= 0 ? index : 0;
        }

        @SuppressWarnings("unchecked")
        @Override
        protected void setValue(Object element, Object value) {
            if (!(value instanceof Integer)) {
                return;
            }

            Integer newSelectedIndex = (Integer) value;
            if (newSelectedIndex < 0 || newSelectedIndex >= PropertyValueType.values().length) {
                return;
            }

            Entry<String, Map<String, Object>> entry = (Entry<String, Map<String, Object>>) element;
            PropertyValueType valueType = PropertyValueType.fromValue(entry.getValue().get("type"));
            PropertyValueType newValueType = PropertyValueType
                    .valueOf(PropertyValueType.stringValues()[(Integer) newSelectedIndex]);

            if (newValueType != null && newValueType.equals(valueType)) {
                return;
            }

            entry.getValue().put("type", newValueType.name());
            if (entry.getValue().get("value") == null) {
                entry.getValue().put("value", newValueType.getDefaultValue());
            }

            tableViewer.refresh();                 
        }
    }

    static class ValueEditingSupport extends EditingSupport {
        private TableViewer tableViewer;

        public ValueEditingSupport(TableViewer tableViewer) {
            super(tableViewer);
            this.tableViewer = tableViewer;
        }

        @Override
        protected CellEditor getCellEditor(Object element) {            
            return new TextCellEditor(tableViewer.getTable());
        }

        @Override
        protected boolean canEdit(Object element) {
            return element instanceof Entry;
        }

        @SuppressWarnings("unchecked")
        @Override
        protected Object getValue(Object element) {            
            Entry<String, Map<String, Object>> entry = (Entry<String, Map<String, Object>>) element;
            return entry.getValue().get("value");      
        }

        @SuppressWarnings("unchecked")
        @Override
        protected void setValue(Object element, Object value) {
            Entry<String, Map<String, Object>> entry = (Entry<String, Map<String, Object>>) element;
            entry.getValue().put("value", value);           
            tableViewer.refresh();
        }
    }

    public TestRailPropertyMapComposite(Composite parent) {
        super(parent, SWT.NONE);
        setBackgroundMode(SWT.INHERIT_FORCE);

        setLayout(new GridLayout(1, false));
        setLayoutData(new GridData(GridData.FILL_BOTH));

        Composite composite = new Composite(this, SWT.NONE);
        GridLayout gl_composite = new GridLayout(1, false);
        gl_composite.marginWidth = 0;
        gl_composite.marginHeight = 0;
        composite.setLayout(gl_composite);
        composite.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true, 1, 1));

        Label lblPropertyMap = new Label(composite, SWT.NONE);
        lblPropertyMap.setText("Custom field mappings");
        
        Composite toolbarComposite = new Composite(composite, SWT.NONE);
        toolbarComposite.setLayout(new FillLayout(SWT.HORIZONTAL));
        toolbarComposite.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, false, 1, 1));
        ToolBar toolBar = new ToolBar(toolbarComposite, SWT.FLAT | SWT.RIGHT);

        tltmAddProperty = new ToolItem(toolBar, SWT.NONE);
        tltmAddProperty.setText("Add");
        setToolItemImage(tltmAddProperty, "/icons/add_16.png");

        tltmRemoveProperty = new ToolItem(toolBar, SWT.NONE);
        tltmRemoveProperty.setText("Delete");
        setToolItemImage(tltmRemoveProperty, "/icons/delete_16.png");

        tltmClearProperty = new ToolItem(toolBar, SWT.NONE);
        tltmClearProperty.setText("Clear");
        setToolItemImage(tltmClearProperty, "/icons/clear_16.png");
        addToolItemListeners();

        Composite tableComposite = new Composite(composite, SWT.NONE);
        tableComposite.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true, 1, 1));

        tableViewer = new TableViewer(tableComposite, SWT.BORDER | SWT.MULTI | SWT.FULL_SELECTION);
        table = tableViewer.getTable();
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true, 1, 1));
        TableColumnLayout tableColumnLayout = new TableColumnLayout();
        tableComposite.setLayout(tableColumnLayout);

        addTableColumn(tableViewer, tableColumnLayout, COLUMN_NAMES[COLUMN_NAME_INDEX], 100, 30,
                new NameEditingSupport(tableViewer));
        addTableColumn(tableViewer, tableColumnLayout, COLUMN_NAMES[COLUMN_TYPE_INDEX], 100, 30,
                new TypeEditingSupport(tableViewer));
        addTableColumn(tableViewer, tableColumnLayout, COLUMN_NAMES[COLUMN_VALUE_INDEX], 100, 30,
                new ValueEditingSupport(tableViewer));

        tableViewer.setLabelProvider(new MapPropertyLabelProvider());
        tableViewer.setContentProvider(new MapPropertyTableViewerContentProvider());
    }

    private void setToolItemImage(ToolItem toolItem, String iconPath) {
        try {
            toolItem.setImage(new Image(Display.getCurrent(), getClass().getResourceAsStream(iconPath)));
        } catch (Exception e) {
            System.err.println("Could not load icon " + iconPath + ": " + e.getMessage());
        }
    }

    private void addTableColumn(TableViewer parent, TableColumnLayout tableColumnLayout, String headerText, int width,
            int weight, EditingSupport editingSupport) {
        TableViewerColumn tableColumn = new TableViewerColumn(parent, SWT.NONE);
        tableColumn.getColumn().setWidth(width);
        tableColumn.getColumn().setMoveable(true);
        tableColumn.getColumn().setText(headerText);
        tableColumn.setEditingSupport(editingSupport);
        tableColumnLayout.setColumnData(tableColumn.getColumn(),
                new ColumnWeightData(weight, tableColumn.getColumn().getWidth()));
    }

    public void setInput(Map<String, Map<String, Object>> propertyMap) {
        this.propertyMap = propertyMap;
        tableViewer.setInput(propertyMap);
    }

    private void addToolItemListeners() {
        tltmAddProperty.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                Map<String, Object> defaultMapEntry = new HashMap<>();
                defaultMapEntry.put("type", PropertyValueType.String.name());
                defaultMapEntry.put("value", "");
                propertyMap.put(generateNewPropertyName(propertyMap), defaultMapEntry);
                tableViewer.refresh();
            }
        });

        tltmRemoveProperty.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                IStructuredSelection selection = (IStructuredSelection) tableViewer.getSelection();
                if (!selection.isEmpty()) {
                    for (Object selectedObject : selection.toList()) {
                        if (selectedObject instanceof Entry<?, ?>) {
                            propertyMap.remove(((Entry<?, ?>) selectedObject).getKey());
                        }
                    }
                    tableViewer.refresh();
                }
            }
        });

        tltmClearProperty.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                propertyMap.clear();
                tableViewer.refresh();
            }
        });
    }

    private static String generateNewPropertyName(Map<String, Map<String, Object>> propertyMap) {
        String name = DEFAULT_DRIVER_PROPERTY_NAME;
        if (propertyMap.get(name) == null) {
            return name;
        }
        int index = 0;
        boolean isUnique = false;
        while (!isUnique) {
            index++;
            String newName = name + "_" + index;
            isUnique = propertyMap.get(newName) == null;
        }
        return name + "_" + index;
    }

    public Map<String, Map<String, Object>> getPropertyMap() {
        return propertyMap;
    }
}