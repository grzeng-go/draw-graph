package com.github.linwancen.plugin.graph.ui;

import com.github.linwancen.plugin.common.ui.UiUtils;
import com.github.linwancen.plugin.graph.printer.InstallMermaid;
import com.github.linwancen.plugin.graph.printer.PrinterData;
import com.github.linwancen.plugin.graph.printer.PrinterGraphviz;
import com.github.linwancen.plugin.graph.printer.PrinterMermaid;
import com.github.linwancen.plugin.graph.printer.PrinterPlantuml;
import com.github.linwancen.plugin.graph.settings.DrawGraphAppState;
import com.github.linwancen.plugin.graph.settings.DrawGraphProjectState;
import com.github.linwancen.plugin.graph.ui.webview.Browser;
import com.github.linwancen.plugin.graph.ui.webview.BrowserFactory;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.util.concurrency.EdtExecutorService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.event.FocusEvent;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Component;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class GraphWindow {
    Project project;
    ToolWindow toolWindow;
    @NotNull
    DrawGraphAppState appState;
    @NotNull
    DrawGraphProjectState projectState;
    private final boolean primary;
    private final JTabbedPane classTabs = new JTabbedPane();
    private final LinkedHashMap<String, GraphWindow> classGraphWindows = new LinkedHashMap<>();
    private VirtualFile sourceFile;
    private int sourceOffset;
    private boolean selectingClassTab;
    private static final int MAX_CLASS_TABS = 10;

    public GraphWindow(@NotNull Project project, ToolWindow toolWindow) {
        this(project, toolWindow, true);
    }

    public GraphWindow(@NotNull Project project, ToolWindow toolWindow, boolean primary) {
        this.project = project;
        this.toolWindow = toolWindow;
        this.primary = primary;
        appState = DrawGraphAppState.of(null);
        projectState = DrawGraphProjectState.of(project);

        openDir.addActionListener(e -> InstallMermaid.openDir(project));
        reload.addActionListener(e -> RelController.reload(project, this));
        reset.addActionListener(e -> resetCurrentGraph());

        autoLoad.setSelected(primary);
        autoLoad.setEnabled(primary);
        if (primary) {
            projectState.setAutoLoad(true);
        }
        autoLoad.addActionListener(e -> {
            projectState.setAutoLoad(autoLoad.isSelected());
            if (autoLoad.isSelected()) {
                GraphWindowFactory.openSelectedFile(project);
            }
        });

        skipLib.setSelected(projectState.getSkipLib());
        skipLib.addActionListener(e -> {
            projectState.setSkipLib(skipLib.isSelected());
            RelController.reload(project, this);
        });

        skipGetSetIs.setSelected(projectState.getSkipGetSetIs());
        skipGetSetIs.addActionListener(e -> {
            projectState.setSkipGetSetIs(skipGetSetIs.isSelected());
            RelController.reload(project, this);
        });

        lr.setSelected(appState.getLr());
        lr.addActionListener(e -> {
            appState.setLr(lr.isSelected());
            RelController.reload(project, this);
        });

        doc.setSelected(appState.getDoc());
        doc.addActionListener(e -> {
            appState.setDoc(doc.isSelected());
            RelController.reload(project, this);
        });

        impl.setSelected(appState.getImpl());
        impl.addActionListener(e -> {
            appState.setImpl(impl.isSelected());
            RelController.reload(project, this);
        });

        mvc.setSelected(appState.getMvc());
        mvc.addActionListener(e -> {
            appState.setMvc(mvc.isSelected());
            RelController.reload(project, this);
        });

        online.setSelected(appState.getOnline());
        online.addActionListener(e -> {
            appState.setOnline(online.isSelected());
            RelController.reload(project, this);
        });

        UiUtils.onChange(limit, String.valueOf(appState.getLimit()), s -> {
            try {
                int v = Integer.parseInt(s);
                appState.setLimit(v);
            } catch (Exception ignored) {}
        });

        UiUtils.onChange(include, projectState.getInclude(), projectState::setInclude);
        UiUtils.onChange(exclude, projectState.getExclude(), projectState::setExclude);
        UiUtils.onChange(otherInclude, projectState.getOtherInclude(), projectState::setOtherInclude);
        UiUtils.onChange(otherExclude, projectState.getOtherExclude(), projectState::setOtherExclude);
        UiUtils.onChange(effectInclude, projectState.getEffectInclude(), projectState::setEffectInclude);
        UiUtils.onChange(effectExclude, projectState.getEffectExclude(), projectState::setEffectExclude);
        UiUtils.onChange(annoDoc, projectState.getAnnoDoc(), projectState::setAnnoDoc);
        UiUtils.onChange(effectAnno, appState.getEffectAnno(), appState::setEffectAnno);
        UiUtils.onChange(mermaidOnline, appState.getMermaidOnline(), appState::setMermaidOnline);
        UiUtils.onChange(tempPath, appState.getTempPath(), appState::setTempPath);
        resetSetting.addActionListener(e -> {
            include.setText(DrawGraphProjectState.getDefault().getInclude());
            exclude.setText(DrawGraphProjectState.getDefault().getExclude());
            otherInclude.setText(DrawGraphProjectState.getDefault().getOtherInclude());
            otherExclude.setText(DrawGraphProjectState.getDefault().getOtherExclude());
            projectState.reset();
        });

        if (primary) {
            setupClassTabs();
        } else {
            initOut(project, false);
        }
    }

    public boolean isPrimary() {
        return primary;
    }

    private void initOut(Project project) {
        initOut(project, true);
    }

    private void initOut(Project project, boolean renderExisting) {
        disposeBrowsers();
        plantumlBrowser = BrowserFactory.of(plantumlOut, project);
        mermaidBrowser = BrowserFactory.of(mermaidOut, project);
        graphvizBrowser = BrowserFactory.of(graphvizOut, project);
        initSrc(renderExisting);
    }

    private void disposeBrowsers() {
        if (plantumlBrowser != null) plantumlBrowser.dispose();
        if (mermaidBrowser != null) mermaidBrowser.dispose();
        if (graphvizBrowser != null) graphvizBrowser.dispose();
    }

    private void setupClassTabs() {
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        for (Component component : new Component[]{
                openDir, reload, reset, lr, autoLoad, skipLib, skipGetSetIs, doc, impl, mvc,
        }) {
            toolbar.add(component);
        }
        mainPanel.removeAll();
        mainPanel.setLayout(new BorderLayout());
        mainPanel.add(toolbar, BorderLayout.NORTH);
        mainPanel.add(classTabs, BorderLayout.CENTER);
        classTabs.addChangeListener(event -> {
            if (selectingClassTab) {
                return;
            }
            GraphWindow selected = selectedGraphWindow();
            if (selected != null) {
                markRecentlyUsed(selected);
                selected.navigateToSource();
            }
        });
    }

    public GraphWindow openClassTab(String key, String title, VirtualFile file, int offset) {
        GraphWindow existing = classGraphWindows.get(key);
        if (existing != null) {
            existing.sourceFile = file;
            existing.sourceOffset = offset;
            selectingClassTab = true;
            classTabs.setSelectedComponent(existing.graphTabs);
            selectingClassTab = false;
            markRecentlyUsed(existing);
            existing.navigateToSource();
            return existing;
        }

        GraphWindow page = new GraphWindow(project, toolWindow, false);
        page.sourceFile = file;
        page.sourceOffset = offset;
        classGraphWindows.put(key, page);
        classTabs.addTab(title, page.graphTabs);
        selectingClassTab = true;
        classTabs.setSelectedComponent(page.graphTabs);
        selectingClassTab = false;
        trimClassTabs();
        page.navigateToSource();
        return page;
    }

    public GraphWindow selectedGraphWindow() {
        Component selected = classTabs.getSelectedComponent();
        for (GraphWindow window : classGraphWindows.values()) {
            if (window.graphTabs == selected) {
                return window;
            }
        }
        return null;
    }

    public GraphWindow findClassTab(String key) {
        return classGraphWindows.get(key);
    }

    private void markRecentlyUsed(GraphWindow window) {
        String key = null;
        for (Map.Entry<String, GraphWindow> entry : classGraphWindows.entrySet()) {
            if (entry.getValue() == window) {
                key = entry.getKey();
                break;
            }
        }
        if (key != null) {
            classGraphWindows.remove(key);
            classGraphWindows.put(key, window);
        }
    }

    private void trimClassTabs() {
        while (classGraphWindows.size() > MAX_CLASS_TABS) {
            Map.Entry<String, GraphWindow> oldest = classGraphWindows.entrySet().iterator().next();
            classTabs.remove(oldest.getValue().graphTabs);
            oldest.getValue().disposePage();
            classGraphWindows.remove(oldest.getKey());
        }
    }

    private void navigateToSource() {
        if (sourceFile != null && sourceFile.isValid()) {
            FileEditor currentEditor = FileEditorManager.getInstance(project).getSelectedEditor();
            if (currentEditor != null && sourceFile.equals(currentEditor.getFile())) {
                com.intellij.openapi.editor.Editor editor =
                        FileEditorManager.getInstance(project).getSelectedTextEditor();
                if (editor != null && editor.getCaretModel().getOffset() == sourceOffset) {
                    return;
                }
            }
            new com.intellij.openapi.fileEditor.OpenFileDescriptor(project, sourceFile, sourceOffset).navigate(true);
        }
    }

    private void resetCurrentGraph() {
        GraphWindow selected = primary ? selectedGraphWindow() : this;
        if (selected != null) {
            selected.initOut(project, false);
            RelController.reload(project, selected);
        }
    }

    private void initSrc(boolean renderExisting) {
        initEvent(plantumlSrc, plantumlHtml, plantumlBrowser, PrinterPlantuml::build, renderExisting);
        initEvent(mermaidSrc, mermaidHtml, mermaidBrowser, PrinterMermaid::build, renderExisting);
        initEvent(graphvizSrc, graphvizHtml, graphvizBrowser, PrinterGraphviz::build, renderExisting);
    }

    private void initEvent(@NotNull JTextArea src, @NotNull JTextArea html, @Nullable Browser browser,
                           @NotNull BiConsumer<PrinterData, Consumer<String>> fun, boolean renderExisting) {
        @NotNull Consumer<FocusEvent> htmlAndOut = e -> fun.accept(new PrinterData(src.getText(), null, project),
                s -> EdtExecutorService.getInstance().submit(() -> {
                    html.setText(s);
                    if (browser != null) browser.load(s);
                }));
        if (renderExisting && !src.getText().isBlank()) {
            ApplicationManager.getApplication().executeOnPooledThread(() -> htmlAndOut.accept(null));
        }

        UiUtils.onFocusLost(src, project, htmlAndOut);
        UiUtils.onFocusLost(html, project, e -> EdtExecutorService.getInstance().submit(() -> {
            if (browser != null) browser.load(html.getText());
        }));
    }

    public void closeAutoLoad() {
        EdtExecutorService.getInstance().submit(() -> {
            autoLoad.setSelected(false);
            if (primary) {
                projectState.setAutoLoad(false);
            }
        });
    }

    public void disposePage() {
        disposeBrowsers();
    }

    JPanel mainPanel;
    private JTabbedPane graphTabs;

    // region title
    private JButton openDir;
    private JButton reload;
    private JButton reset;
    private JCheckBox autoLoad;
    private JCheckBox skipLib;
    // region title

    // region html
    @Nullable
    Browser mermaidBrowser;
    JPanel mermaidOut;
    JTextArea mermaidHtml;
    JTextArea mermaidSrc;

    @Nullable
    Browser plantumlBrowser;
    JPanel plantumlOut;
    JTextArea plantumlHtml;
    JTextArea plantumlSrc;

    @Nullable
    Browser graphvizBrowser;
    JPanel graphvizOut;
    JTextArea graphvizHtml;
    JTextArea graphvizSrc;
    // endregion html

    JTextArea effect;

    // region setting
    private JButton resetSetting;
    private JCheckBox skipGetSetIs;
    private JCheckBox lr;
    private JCheckBox doc;
    private JCheckBox htmlPreview;
    private JTextField limit;
    private JCheckBox impl;
    private JCheckBox mvc;
    private JTextField include;
    private JTextField exclude;
    private JTextField otherInclude;
    private JTextField otherExclude;
    private JTextField effectInclude;
    private JTextField effectExclude;
    private JTextArea annoDoc;
    private JTextArea effectAnno;
    private JCheckBox online;
    private JTextField mermaidOnline;
    private JTextField tempPath;
    // endregion setting
}
