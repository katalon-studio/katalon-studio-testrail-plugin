package com.katalon.plugin.testrail.components;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;

import com.katalon.plugin.testrail.TestRailConstants;

public class HelpToolItem extends ToolItem {
    private String documentationLink;

    public HelpToolItem(ToolBar parent, String documentationLink) {
        this(parent, documentationLink, "");
    }

    public HelpToolItem(ToolBar parent, String documentationLink, String label) {
        super(parent, SWT.PUSH);
        this.documentationLink = documentationLink;
        Image img = new Image(Display.getCurrent(),
                HelpToolItem.class.getResourceAsStream(TestRailConstants.HELP_ICON_URI));
        setImage(img);
        setToolTipText(TestRailConstants.HRLP_TOOL_TIP);
        setText(label);
        addSelectionListener(getSelectionListener());
    }

    protected SelectionListener getSelectionListener() {
        return new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                openBrowserToLink(getDocumentationUrl());
            }
        };
    }

    protected void openBrowserToLink(String url) {
        Program.launch(url);
    }

    private String getDocumentationUrl() {
        return documentationLink;
    }

    @Override
    protected void checkSubclass() {
        // Override this to subclass
    }

}
