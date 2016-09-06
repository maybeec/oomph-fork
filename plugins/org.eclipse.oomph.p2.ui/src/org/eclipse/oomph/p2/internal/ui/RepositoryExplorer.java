/*
 * Copyright (c) 2014-2016 Eike Stepper (Berlin, Germany) and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Eike Stepper - initial API and implementation
 */
package org.eclipse.oomph.p2.internal.ui;

import org.eclipse.oomph.internal.ui.GeneralDragAdapter;
import org.eclipse.oomph.internal.ui.GeneralDropAdapter;
import org.eclipse.oomph.internal.ui.GeneralDropAdapter.DroppedObjectHandler;
import org.eclipse.oomph.internal.ui.OomphTransferDelegate;
import org.eclipse.oomph.p2.P2Exception;
import org.eclipse.oomph.p2.P2Factory;
import org.eclipse.oomph.p2.P2Package;
import org.eclipse.oomph.p2.Repository;
import org.eclipse.oomph.p2.Requirement;
import org.eclipse.oomph.p2.VersionSegment;
import org.eclipse.oomph.p2.core.P2Util;
import org.eclipse.oomph.p2.core.RepositoryProvider;
import org.eclipse.oomph.p2.impl.RequirementImpl;
import org.eclipse.oomph.p2.internal.ui.RepositoryManager.RepositoryManagerListener;
import org.eclipse.oomph.p2.provider.RequirementItemProvider;
import org.eclipse.oomph.ui.SearchField;
import org.eclipse.oomph.ui.SearchField.FilterHandler;
import org.eclipse.oomph.ui.UIUtil;
import org.eclipse.oomph.util.CollectionUtil;
import org.eclipse.oomph.util.ObjectUtil;
import org.eclipse.oomph.util.StringUtil;

import org.eclipse.emf.ecore.EObject;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.core.runtime.URIUtil;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.variables.IStringVariableManager;
import org.eclipse.core.variables.VariablesPlugin;
import org.eclipse.equinox.p2.core.ProvisionException;
import org.eclipse.equinox.p2.metadata.IInstallableUnit;
import org.eclipse.equinox.p2.metadata.IProvidedCapability;
import org.eclipse.equinox.p2.metadata.IRequirement;
import org.eclipse.equinox.p2.metadata.Version;
import org.eclipse.equinox.p2.metadata.VersionRange;
import org.eclipse.equinox.p2.metadata.expression.IMatchExpression;
import org.eclipse.equinox.p2.query.IQueryResult;
import org.eclipse.equinox.p2.query.QueryUtil;
import org.eclipse.equinox.p2.repository.metadata.IMetadataRepository;
import org.eclipse.equinox.p2.repository.metadata.IMetadataRepositoryManager;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ComboViewer;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.IStructuredContentProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.StructuredViewer;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerComparator;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CCombo;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.events.FocusEvent;
import org.eclipse.swt.events.FocusListener;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IActionBars;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.forms.FormColors;
import org.eclipse.ui.forms.widgets.Form;
import org.eclipse.ui.forms.widgets.FormToolkit;
import org.eclipse.ui.part.ViewPart;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @author Eike Stepper
 */
public class RepositoryExplorer extends ViewPart implements FilterHandler
{
  public static final String ID = "org.eclipse.oomph.p2.ui.RepositoryExplorer"; //$NON-NLS-1$

  private static final IDialogSettings SETTINGS = P2UIPlugin.INSTANCE.getDialogSettings(RepositoryExplorer.class.getSimpleName());

  private static final int DND_OPERATIONS = DND.DROP_COPY | DND.DROP_MOVE | DND.DROP_LINK;

  private static final Transfer[] DND_TRANSFERS = OomphTransferDelegate.asTransfers(org.eclipse.oomph.internal.ui.OomphTransferDelegate.DELEGATES)
      .toArray(new Transfer[OomphTransferDelegate.asTransfers(org.eclipse.oomph.internal.ui.OomphTransferDelegate.DELEGATES).size()]);

  private static final String DEFAULT_CAPABILITY_NAMESPACE = IInstallableUnit.NAMESPACE_IU_ID;

  private static final String CURRENT_NAMESPACE_KEY = "currentNamespace";

  private static final String EXPERT_MODE_KEY = "expertMode";

  private static final String CATEGORIZE_ITEMS_KEY = "categorizeItems";

  private static final String VERSION_SEGMENT_KEY = "versionSegment";

  private static final String COMPATIBLE_VERSION_KEY = "compatibleVersion";

  private static final String SOURCE_SUFFIX = ".source";

  private static final String SOURCE_FEATURE_SUFFIX = SOURCE_SUFFIX + Requirement.FEATURE_SUFFIX;

  private static final Object[] NO_ELEMENTS = new Object[0];

  private final LoadJob loadJob = new LoadJob();

  private final AnalyzeJob analyzeJob = new AnalyzeJob();

  private final Mode categoriesMode = new CategoriesMode();

  private final Mode featuresMode = new FeaturesMode();

  private final Mode capabilitiesMode = new CapabilitiesMode();

  private final RepositoryFocusListener repositoryFocusListener = new RepositoryFocusListener();

  private final RepositoryHistoryListener repositoryHistoryListener = new RepositoryHistoryListener();

  private final VersionProvider versionProvider = new VersionProvider();

  private final CollapseAllAction collapseAllAction = new CollapseAllAction();

  private final SearchAction searchAction = new SearchAction();

  private Composite container;

  private ComboViewer repositoryViewer;

  private CCombo repositoryCombo;

  private RepositoryProvider.Metadata repositoryProvider;

  private Composite selectorComposite;

  private Composite itemsComposite;

  private StructuredViewer itemsViewer;

  private CategoryItem itemsViewerInput;

  private TableViewer versionsViewer;

  private String currentNamespace;

  private boolean expertMode;

  private boolean categorizeItems;

  private boolean compatibleVersion;

  private Mode mode;

  private IQueryResult<IInstallableUnit> installableUnits;

  private String filter;

  private FormToolkit formToolkit;

  public RepositoryExplorer()
  {
    currentNamespace = SETTINGS.get(CURRENT_NAMESPACE_KEY);
    if (currentNamespace == null)
    {
      currentNamespace = DEFAULT_CAPABILITY_NAMESPACE;
    }

    expertMode = SETTINGS.getBoolean(EXPERT_MODE_KEY);

    String value = SETTINGS.get(CATEGORIZE_ITEMS_KEY);
    if (value == null || value.length() == 0)
    {
      categorizeItems = true;
    }
    else
    {
      categorizeItems = "true".equals(value);
    }

    compatibleVersion = SETTINGS.getBoolean(COMPATIBLE_VERSION_KEY);
  }

  @Override
  public void dispose()
  {
    if (formToolkit != null)
    {
      formToolkit.dispose();
    }

    disposeRepositoryProvider();
    super.dispose();
  }

  private void disposeRepositoryProvider()
  {
    if (repositoryProvider != null)
    {
      repositoryProvider.dispose();
      repositoryProvider = null;
    }
  }

  @Override
  public void setFocus()
  {
    if (RepositoryManager.INSTANCE.getActiveRepository() != null)
    {
      repositoryCombo.setFocus();
    }
  }

  private void updateMode()
  {
    Mode mode = expertMode ? capabilitiesMode : categorizeItems ? categoriesMode : featuresMode;
    if (this.mode != mode)
    {
      this.mode = mode;

      GridLayout selectorLayout = new GridLayout();
      selectorLayout.marginWidth = 0;
      selectorLayout.marginHeight = 0;

      selectorComposite.setLayout(selectorLayout);
      mode.fillSelector(selectorComposite);
      selectorComposite.layout();
      selectorComposite.getParent().layout();

      mode.fillItems(itemsComposite);
      itemsComposite.layout();

      itemsViewer.addSelectionChangedListener(new ISelectionChangedListener()
      {
        public void selectionChanged(SelectionChangedEvent event)
        {
          IStructuredSelection selection = (IStructuredSelection)itemsViewer.getSelection();
          if (selection.size() == 1)
          {
            versionsViewer.setInput(selection.getFirstElement());
          }
          else
          {
            versionsViewer.setInput(null);
          }
        }
      });

      analyzeJob.reschedule();
      collapseAllAction.updateEnablement();
    }
  }

  private void setItems(Item... items)
  {
    if (!container.isDisposed())
    {
      versionsViewer.setInput(null);

      itemsViewerInput = new CategoryItem();
      itemsViewerInput.setChildren(items);
      itemsViewer.setInput(itemsViewerInput);

      if (itemsViewer instanceof TreeViewer && filter != null)
      {
        TreeViewer treeViewer = (TreeViewer)itemsViewer;
        treeViewer.expandAll();
      }
    }
  }

  private boolean isFiltered(String string)
  {
    return filter == null || string == null || string.toLowerCase().contains(filter);
  }

  public void handleFilter(String filter)
  {
    if (filter == null || filter.length() == 0)
    {
      this.filter = null;
    }
    else
    {
      this.filter = filter.toLowerCase();
    }

    analyzeJob.reschedule();
  }

  @Override
  public void createPartControl(Composite parent)
  {
    final Display display = parent.getDisplay();

    formToolkit = new FormToolkit(display);
    FormColors colors = formToolkit.getColors();
    colors.createColor("initial_repository", FormColors.blend(colors.getForeground().getRGB(), colors.getBackground().getRGB(), 75));

    Form form = formToolkit.createForm(parent);
    container = form.getBody();
    container.setLayout(new GridLayout(1, false));

    createRepositoriesArea(container);
    createItemsArea(container);
    createVersionsArea(container);

    updateMode();

    String activeRepository = RepositoryManager.INSTANCE.getActiveRepository();
    if (activeRepository == null)
    {
      // Force hint to be shown.
      repositoryFocusListener.focusLost(null);
    }
    else
    {
      repositoryCombo.setText(activeRepository);
      triggerLoad(activeRepository);
    }

    hookActions();
  }

  private static CCombo createCombo(Composite parent, int style, boolean grabExcessHorizontalSpace)
  {
    CCombo combo = new CCombo(parent, style);
    GridData layoutData = new GridData(SWT.FILL, SWT.FILL, grabExcessHorizontalSpace, false);

    int increaseHeight = 0;
    String ws = Platform.getWS();
    if (Platform.WS_COCOA.equals(ws))
    {
      increaseHeight = 7;
    }
    else if (Platform.WS_GTK.equals(ws))
    {
      increaseHeight = 9;
    }

    if (increaseHeight != 0)
    {
      FontData[] fontData = combo.getFont().getFontData();
      layoutData.heightHint = fontData[0].getHeight() + increaseHeight;
    }

    combo.setLayoutData(layoutData);
    return combo;
  }

  private void createRepositoriesArea(Composite container)
  {
    repositoryCombo = createCombo(container, SWT.BORDER, true);
    repositoryCombo.setToolTipText("Repository location (type a URL, drop a repository or pick from the drop down history)");
    repositoryCombo.addFocusListener(repositoryFocusListener);
    repositoryCombo.addKeyListener(repositoryHistoryListener);

    repositoryViewer = new ComboViewer(repositoryCombo);
    repositoryViewer.setContentProvider(new RepositoryContentProvider());
    repositoryViewer.setLabelProvider(new LabelProvider());
    repositoryViewer.setInput(RepositoryManager.INSTANCE);
    repositoryViewer.addSelectionChangedListener(repositoryHistoryListener);

    repositoryViewer.addDropSupport(DND_OPERATIONS, DND_TRANSFERS, new GeneralDropAdapter(repositoryViewer, P2Factory.eINSTANCE.createRepositoryList(),
        P2Package.Literals.REPOSITORY_LIST__REPOSITORIES, new DroppedObjectHandler()
        {
          public void handleDroppedObject(Object object) throws Exception
          {
            if (object instanceof Repository)
            {
              Repository repository = (Repository)object;
              String url = repository.getURL();
              if (!StringUtil.isEmpty(url))
              {
                activateAndLoadRepository(url);
              }
            }
          }
        }));
  }

  private void createItemsArea(Composite parent)
  {
    GridLayout containerLayout = new GridLayout(2, false);
    containerLayout.marginWidth = 0;
    containerLayout.marginHeight = 0;

    Composite container = formToolkit.createComposite(parent, SWT.NONE);
    container.setLayout(containerLayout);
    container.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

    SearchField searchField = new SearchField(container, this)
    {
      @Override
      protected void finishFilter()
      {
        itemsViewer.getControl().setFocus();
        selectFirstLeaf(itemsViewerInput);
      }

      private void selectFirstLeaf(CategoryItem category)
      {
        if (category != null)
        {
          Item[] children = category.getChildren();
          if (children != null && children.length != 0)
          {
            Item firstChild = children[0];
            if (firstChild instanceof CategoryItem)
            {
              CategoryItem firstCategory = (CategoryItem)firstChild;
              selectFirstLeaf(firstCategory);
            }
            else
            {
              itemsViewer.setSelection(new StructuredSelection(firstChild));
            }
          }
        }
      }
    };

    searchField.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

    selectorComposite = formToolkit.createComposite(container, SWT.NONE);
    selectorComposite.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));

    itemsComposite = formToolkit.createComposite(container, SWT.NONE);
    itemsComposite.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1));
    itemsComposite.setLayout(new FillLayout());
  }

  private void createVersionsArea(Composite container)
  {
    Composite versionsComposite = formToolkit.createComposite(container, SWT.NONE);
    GridLayout gl_versionsComposite = new GridLayout(2, false);
    gl_versionsComposite.marginWidth = 0;
    gl_versionsComposite.marginHeight = 0;
    versionsComposite.setLayout(gl_versionsComposite);
    versionsComposite.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

    versionsViewer = new TableViewer(versionsComposite, SWT.BORDER);
    versionsViewer.getControl().setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, false, 1, 1));
    versionsViewer.setContentProvider(versionProvider);
    versionsViewer.setLabelProvider(versionProvider);
    addDragSupport(versionsViewer);

    formToolkit.adapt(versionsViewer.getControl(), false, false);

    Composite versionsGroup = formToolkit.createComposite(versionsComposite, SWT.NONE);
    versionsGroup.setLayout(new GridLayout(1, false));
    versionsGroup.setLayoutData(new GridData(SWT.LEFT, SWT.TOP, false, false));

    final Button compatibleButton = new Button(versionsGroup, SWT.CHECK);
    compatibleButton.setText("Compatible");
    compatibleButton.setToolTipText("Show compatible versions");
    compatibleButton.setSelection(compatibleVersion);

    final Button majorButton = addVersionSegmentButton(versionsGroup, "Major", "Show major versions", VersionSegment.MAJOR);
    final Button minorButton = addVersionSegmentButton(versionsGroup, "Minor", "Show minor versions", VersionSegment.MINOR);
    addVersionSegmentButton(versionsGroup, "Micro", "Show micro versions", VersionSegment.MICRO);
    addVersionSegmentButton(versionsGroup, "Qualifier", "Show qualified versions", VersionSegment.QUALIFIER);

    majorButton.setEnabled(!compatibleVersion);
    compatibleButton.addSelectionListener(new SelectionAdapter()
    {
      @Override
      public void widgetSelected(SelectionEvent e)
      {
        boolean compatible = compatibleButton.getSelection();
        if (compatibleVersion != compatible)
        {
          compatibleVersion = compatible;
          SETTINGS.put(COMPATIBLE_VERSION_KEY, compatibleVersion);

          majorButton.setEnabled(!compatible);

          if (compatible && versionProvider.getVersionSegment() == VersionSegment.MAJOR)
          {
            majorButton.setSelection(false);
            minorButton.setSelection(true);
            versionProvider.setVersionSegment(VersionSegment.MINOR);
          }
        }
      }
    });
  }

  private Button addVersionSegmentButton(Composite parent, String text, String toolTip, final VersionSegment versionSegment)
  {
    Button button = new Button(parent, SWT.RADIO);
    button.setText(text);
    button.setToolTipText(toolTip);
    button.addSelectionListener(new SelectionAdapter()
    {
      @Override
      public void widgetSelected(SelectionEvent e)
      {
        versionProvider.setVersionSegment(versionSegment);
      }
    });

    if (versionSegment == versionProvider.getVersionSegment())
    {
      button.setSelection(true);
    }

    return button;
  }

  private void hookActions()
  {
    IActionBars actionBars = getViewSite().getActionBars();

    IToolBarManager toolbarManager = actionBars.getToolBarManager();
    toolbarManager.add(new Separator("additions"));
    toolbarManager.add(collapseAllAction);

    toolbarManager.add(new Action("Refresh", P2UIPlugin.INSTANCE.getImageDescriptor("refresh"))
    {
      {
        setToolTipText("Reload the active repository and refresh the tree");
      }

      @Override
      public void run()
      {
        String activeRepository = RepositoryManager.INSTANCE.getActiveRepository();
        if (activeRepository != null)
        {
          disposeRepositoryProvider();
          triggerLoad(activeRepository);
        }
      }
    });

    toolbarManager.add(new Separator("modes"));
    toolbarManager.add(new Action("Expert Mode", IAction.AS_CHECK_BOX)
    {
      {
        setImageDescriptor(P2UIPlugin.INSTANCE.getImageDescriptor("obj16/capability"));
        setChecked(expertMode);
      }

      @Override
      public void run()
      {
        expertMode = isChecked();
        SETTINGS.put(EXPERT_MODE_KEY, expertMode);
        updateMode();
      }
    });

    toolbarManager.add(new Separator("search"));
    toolbarManager.add(searchAction);

    toolbarManager.add(new Separator("end"));
  }

  private void activateAndLoadRepository(String repository)
  {
    if (RepositoryManager.INSTANCE.setActiveRepository(repository))
    {
      triggerLoad(repository);
    }
  }

  private void triggerLoad(String repository)
  {
    try
    {
      IStringVariableManager manager = VariablesPlugin.getDefault().getStringVariableManager();
      repository = manager.performStringSubstitution(repository);
    }
    catch (Exception ex)
    {
      //$FALL-THROUGH$
    }

    URI location = null;

    try
    {
      location = new URI(repository);
    }
    catch (URISyntaxException ex)
    {
      File folder = new File(repository);
      if (folder.isDirectory())
      {
        location = folder.toURI();
      }
    }

    if (location != null)
    {
      loadJob.reschedule(location);
    }
  }

  private void addDragSupport(StructuredViewer viewer)
  {
    viewer.addDragSupport(DND_OPERATIONS, DND_TRANSFERS, new GeneralDragAdapter(viewer, new GeneralDragAdapter.DraggedObjectsFactory()
    {
      public List<EObject> createDraggedObjects(ISelection selection) throws Exception
      {
        List<EObject> result = new ArrayList<EObject>();

        IStructuredSelection ssel = (IStructuredSelection)selection;
        for (Iterator<?> it = ssel.iterator(); it.hasNext();)
        {
          Object element = it.next();

          VersionRange versionRange = VersionRange.emptyRange;
          String filter = null;

          if (element instanceof VersionProvider.ItemVersion)
          {
            VersionProvider.ItemVersion itemVersion = (VersionProvider.ItemVersion)element;
            Version version = itemVersion.getVersion();

            VersionSegment versionSegment = versionProvider.getVersionSegment();
            versionRange = P2Factory.eINSTANCE.createVersionRange(version, versionSegment, compatibleVersion);

            filter = RequirementImpl.formatMatchExpression(itemVersion.getFilter());

            element = ((IStructuredSelection)itemsViewer.getSelection()).getFirstElement();
          }

          if (element instanceof Item)
          {
            Item item = (Item)element;

            String namespace = item.getNamespace();
            if (namespace != null)
            {
              if (filter == null && item instanceof VersionedItem)
              {
                VersionedItem versionedItem = (VersionedItem)item;

                for (IMatchExpression<IInstallableUnit> matchExpression : versionedItem.getVersions().values())
                {
                  String string = RequirementImpl.formatMatchExpression(matchExpression);
                  if (filter == null || filter.equals(string))
                  {
                    filter = string;
                  }
                  else
                  {
                    filter = null;
                    break;
                  }
                }
              }

              Requirement requirement = P2Factory.eINSTANCE.createRequirement();
              requirement.setNamespace(namespace);
              requirement.setName(item.getName());
              requirement.setVersionRange(versionRange);
              requirement.setFilter(filter);
              result.add(requirement);
            }
          }
        }

        return result;
      }
    }));
  }

  private static String[] sortStrings(Collection<String> c)
  {
    String[] array = c.toArray(new String[c.size()]);
    Arrays.sort(array);
    return array;
  }

  static void minimizeNamespaces(Set<String> flavors, Set<String> namespaces)
  {
    String[] flavorIDs = getMinimalFlavors(flavors);
    for (Iterator<String> it = namespaces.iterator(); it.hasNext();)
    {
      String namespace = it.next();
      for (int i = 0; i < flavorIDs.length; i++)
      {
        String flavor = flavorIDs[i];
        if (namespace.startsWith(flavor))
        {
          it.remove();
          break;
        }
      }
    }
  }

  private static String[] getMinimalFlavors(final Set<String> flavors)
  {
    String[] flavorIDs = sortStrings(flavors);
    int start = 0;

    while (start < flavorIDs.length)
    {
      boolean changed = false;
      for (int i = start + 1; i < flavorIDs.length; i++)
      {
        String flavorID = flavorIDs[i];
        if (flavorID.startsWith(flavorIDs[start]))
        {
          flavors.remove(flavorID);
          changed = true;
        }
      }

      if (changed)
      {
        flavorIDs = sortStrings(flavors);
      }

      ++start;
    }

    return flavorIDs;
  }

  private static boolean isCategory(IInstallableUnit iu)
  {
    return "true".equalsIgnoreCase(iu.getProperty(QueryUtil.PROP_TYPE_CATEGORY));
  }

  private static boolean isFeature(IInstallableUnit iu)
  {
    return iu.getId().endsWith(Requirement.FEATURE_SUFFIX);
  }

  public static boolean explore(String repository)
  {
    IWorkbenchWindow window = UIUtil.WORKBENCH.getActiveWorkbenchWindow();
    if (window != null)
    {
      IWorkbenchPage page = window.getActivePage();
      if (page != null)
      {
        IViewPart view = page.findView(ID);
        if (view == null)
        {
          try
          {
            view = page.showView(ID);
          }
          catch (PartInitException ex)
          {
            P2UIPlugin.INSTANCE.log(ex);
          }
        }

        if (view instanceof RepositoryExplorer)
        {
          RepositoryExplorer explorer = (RepositoryExplorer)view;
          explorer.activateAndLoadRepository(repository);
          return true;
        }
      }
    }

    return false;
  }

  /**
   * @author Eike Stepper
   */
  private final class CollapseAllAction extends Action
  {
    public CollapseAllAction()
    {
      super("Collapse All", P2UIPlugin.INSTANCE.getImageDescriptor("collapse-all"));
      setToolTipText("Collapse all tree items");
      updateEnablement();
    }

    public void updateEnablement()
    {
      setEnabled(itemsViewer instanceof TreeViewer);
    }

    @Override
    public void run()
    {
      if (itemsViewer instanceof TreeViewer)
      {
        TreeViewer treeViewer = (TreeViewer)itemsViewer;
        treeViewer.collapseAll();
      }
    }
  }

  /**
   * @author Ed Merks
   */
  private final class SearchAction extends Action
  {
    public SearchAction()
    {
      super("Search", P2UIPlugin.INSTANCE.getImageDescriptor("tool16/search"));
      setToolTipText("Search Eclipse repositories by provided capabilities");
    }

    @Override
    public void run()
    {
      SearchEclipseRepositoryDialog searchEclipseRepositoryDialog = new SearchEclipseRepositoryDialog(UIUtil.getShell());
      if (searchEclipseRepositoryDialog.open() == Dialog.OK)
      {
        activateAndLoadRepository(searchEclipseRepositoryDialog.getSelectedRepository());
      }
    }
  }

  /**
   * @author Eike Stepper
   */
  private abstract class SafeJob extends Job
  {
    public SafeJob(String name)
    {
      super(name);
    }

    @Override
    protected final IStatus run(IProgressMonitor monitor)
    {
      try
      {
        doSafe(monitor);
        return Status.OK_STATUS;
      }
      catch (OperationCanceledException ex)
      {
        return Status.CANCEL_STATUS;
      }
      catch (Exception ex)
      {
        if (ex instanceof P2Exception)
        {
          Throwable cause = ex.getCause();
          if (cause instanceof CoreException)
          {
            ex = (CoreException)cause;
          }
        }

        final IStatus status = P2UIPlugin.INSTANCE.getStatus(ex);
        UIUtil.asyncExec(new Runnable()
        {
          public void run()
          {
            setItems(new ErrorItem(status));
          }
        });

        return Status.OK_STATUS;
      }
      catch (Throwable t)
      {
        return P2UIPlugin.INSTANCE.getStatus(t);
      }
    }

    protected abstract void doSafe(IProgressMonitor monitor) throws Throwable;
  }

  /**
   * @author Eike Stepper
   */
  private final class LoadJob extends SafeJob
  {
    private URI location;

    public LoadJob()
    {
      super("Loading repository");
    }

    public void reschedule(URI location)
    {
      this.location = location;
      setItems(new LoadingItem(location));

      cancel();
      schedule();
    }

    @Override
    @SuppressWarnings("restriction")
    protected void doSafe(IProgressMonitor monitor) throws Throwable
    {
      analyzeJob.cancel();
      installableUnits = null;

      IMetadataRepositoryManager repositoryManager = P2Util.getAgentManager().getCurrentAgent().getMetadataRepositoryManager();
      if (repositoryProvider == null || !repositoryProvider.getLocation().equals(location))
      {
        disposeRepositoryProvider();
        repositoryProvider = new RepositoryProvider.Metadata(repositoryManager, location);
      }

      SubMonitor progress = SubMonitor.convert(monitor, 101);

      IMetadataRepository repository = repositoryProvider.getRepository(progress.newChild(100));

      if (repository instanceof org.eclipse.equinox.internal.p2.metadata.repository.CompositeMetadataRepository)
      {
        org.eclipse.equinox.internal.p2.metadata.repository.CompositeMetadataRepository compositeRepository = (org.eclipse.equinox.internal.p2.metadata.repository.CompositeMetadataRepository)repository;
        org.eclipse.equinox.internal.p2.persistence.CompositeRepositoryState state = compositeRepository.toState();
        URI[] children = state.getChildren();

        final List<Item> errors = new ArrayList<Item>();
        Set<String> messages = new HashSet<String>();

        for (URI child : children)
        {
          try
          {
            URI absolute = URIUtil.makeAbsolute(child, location);
            if (repositoryManager.loadRepository(absolute, null) == null)
            {
              throw new ProvisionException("No repository found at " + absolute + ".");
            }
          }
          catch (Exception ex)
          {
            IStatus status = P2UIPlugin.INSTANCE.getStatus(ex);
            if (messages.add(status.getMessage()))
            {
              errors.add(new ErrorItem(status));
            }
          }
        }

        if (!errors.isEmpty())
        {
          UIUtil.asyncExec(new Runnable()
          {
            public void run()
            {
              setItems(errors.toArray(new Item[errors.size()]));
            }
          });

          return;
        }
      }

      installableUnits = repository.query(QueryUtil.createIUAnyQuery(), progress.newChild(1));
      analyzeJob.reschedule();
    }
  }

  /**
   * @author Eike Stepper
   */
  private final class AnalyzeJob extends SafeJob
  {
    public AnalyzeJob()
    {
      super("Analyzing repository");
    }

    public void reschedule()
    {
      cancel();

      if (installableUnits != null)
      {
        schedule();
      }
    }

    @Override
    protected void doSafe(IProgressMonitor monitor) throws Throwable
    {
      mode.analyzeInstallableUnits(monitor);
    }
  }

  /**
   * @author Eike Stepper
   */
  private abstract class Mode
  {
    protected final void disposeChildren(Composite parent)
    {
      for (Control child : parent.getChildren())
      {
        child.dispose();
      }
    }

    protected final void fillCategorySelector(Composite parent)
    {
      Control[] children = parent.getChildren();
      if (children.length == 1 && children[0] instanceof Button)
      {
        ((Button)children[0]).setSelection(categorizeItems);
        return;
      }

      disposeChildren(parent);

      final Button button = new Button(parent, SWT.CHECK);
      button.setText("Group items by category");
      button.setToolTipText("Whether to show items in categories or in a complete list");
      button.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
      button.setSelection(categorizeItems);
      button.addSelectionListener(new SelectionAdapter()
      {
        @Override
        public void widgetSelected(SelectionEvent e)
        {
          categorizeItems = button.getSelection();
          SETTINGS.put(CATEGORIZE_ITEMS_KEY, categorizeItems);

          updateMode();
        }
      });
    }

    public abstract void fillSelector(Composite parent);

    public abstract void fillItems(Composite parent);

    public abstract void analyzeInstallableUnits(IProgressMonitor monitor);
  }

  /**
   * @author Eike Stepper
   */
  private final class CategoriesMode extends Mode
  {
    @Override
    public void fillSelector(Composite parent)
    {
      fillCategorySelector(parent);
    }

    @Override
    public void fillItems(Composite parent)
    {
      disposeChildren(parent);

      TreeViewer categoriesViewer = new TreeViewer(parent, SWT.BORDER | SWT.MULTI);
      categoriesViewer.setUseHashlookup(true);
      categoriesViewer.setContentProvider(new ItemContentProvider());
      categoriesViewer.setLabelProvider(new ItemLabelProvider());
      addDragSupport(categoriesViewer);

      itemsViewer = categoriesViewer;
    }

    @SuppressWarnings("restriction")
    @Override
    public void analyzeInstallableUnits(IProgressMonitor monitor)
    {
      // IU.id -> value
      Map<String, String> names = new HashMap<String, String>();
      Map<String, Set<IInstallableUnit>> ius = new HashMap<String, Set<IInstallableUnit>>();
      Map<String, Set<IRequirement>> categories = new HashMap<String, Set<IRequirement>>();

      for (IInstallableUnit iu : installableUnits)
      {
        P2UIPlugin.checkCancelation(monitor);
        String id = iu.getId();

        names.put(id, P2Util.getName(iu));
        CollectionUtil.add(ius, id, iu);

        if (isCategory(iu))
        {
          CollectionUtil.addAll(categories, id, iu.getRequirements());
        }
      }

      Set<String> rootIDs = new HashSet<String>();

      for (String categoryID : categories.keySet())
      {
        P2UIPlugin.checkCancelation(monitor);
        rootIDs.add(categoryID);
      }

      for (Set<IRequirement> requirements : categories.values())
      {
        for (IRequirement requirement : requirements)
        {
          P2UIPlugin.checkCancelation(monitor);

          if (requirement instanceof org.eclipse.equinox.internal.p2.metadata.IRequiredCapability)
          {
            org.eclipse.equinox.internal.p2.metadata.IRequiredCapability requiredCapability = (org.eclipse.equinox.internal.p2.metadata.IRequiredCapability)requirement;
            if (IInstallableUnit.NAMESPACE_IU_ID.equals(requiredCapability.getNamespace()))
            {
              rootIDs.remove(requiredCapability.getName());
            }
          }
        }
      }

      Set<CategoryItem> rootCategories = new HashSet<CategoryItem>();
      for (String rootID : rootIDs)
      {
        P2UIPlugin.checkCancelation(monitor);

        CategoryItem rootCategory = analyzeCategory(names, ius, categories, rootID, monitor);
        if (rootCategory != null)
        {
          rootCategories.add(rootCategory);
        }
      }

      final CategoryItem[] roots = rootCategories.toArray(new CategoryItem[rootCategories.size()]);
      UIUtil.asyncExec(new Runnable()
      {
        public void run()
        {
          setItems(roots);
        }
      });
    }

    @SuppressWarnings("restriction")
    private CategoryItem analyzeCategory(Map<String, String> names, Map<String, Set<IInstallableUnit>> ius, Map<String, Set<IRequirement>> categories,
        String categoryID, IProgressMonitor monitor)
    {
      Map<String, Item> children = new HashMap<String, Item>();
      Map<Item, Map<Version, IMatchExpression<IInstallableUnit>>> versions = new HashMap<Item, Map<Version, IMatchExpression<IInstallableUnit>>>();

      for (IRequirement requirement : categories.get(categoryID))
      {
        P2UIPlugin.checkCancelation(monitor);

        if (requirement instanceof org.eclipse.equinox.internal.p2.metadata.IRequiredCapability)
        {
          org.eclipse.equinox.internal.p2.metadata.IRequiredCapability requiredCapability = (org.eclipse.equinox.internal.p2.metadata.IRequiredCapability)requirement;
          if (IInstallableUnit.NAMESPACE_IU_ID.equals(requiredCapability.getNamespace()))
          {
            String requiredID = requiredCapability.getName();
            if (categories.containsKey(requiredID))
            {
              CategoryItem child = analyzeCategory(names, ius, categories, requiredID, monitor);
              if (child != null)
              {
                children.put(requiredID, child);
              }
            }
            else
            {
              VersionRange range = requiredCapability.getRange();
              Item child = children.get(requiredID);

              Set<IInstallableUnit> set = ius.get(requiredID);
              if (set != null)
              {
                for (IInstallableUnit iu : set)
                {
                  P2UIPlugin.checkCancelation(monitor);
                  Version version = iu.getVersion();

                  if (range.isIncluded(version))
                  {
                    if (child == null)
                    {
                      String name = names.get(requiredID);
                      if (isFiltered(name))
                      {
                        if (isFeature(iu))
                        {
                          if (requiredID.endsWith(SOURCE_FEATURE_SUFFIX))
                          {
                            String mainID = requiredID.substring(0, requiredID.length() - SOURCE_FEATURE_SUFFIX.length()) + Requirement.FEATURE_SUFFIX;
                            String mainName = names.get(mainID);

                            if (ObjectUtil.equals(name, mainName))
                            {
                              name += " (Source)";
                            }
                          }

                          child = new FeatureItem(requiredID);
                        }
                        else
                        {
                          if (requiredID.endsWith(SOURCE_SUFFIX))
                          {
                            String mainID = requiredID.substring(0, requiredID.length() - SOURCE_SUFFIX.length());
                            String mainName = names.get(mainID);

                            if (ObjectUtil.equals(name, mainName))
                            {
                              name += " (Source)";
                            }
                          }

                          child = new PluginItem(requiredID);
                        }

                        child.setLabel(name);
                        children.put(requiredID, child);
                      }
                    }

                    if (child != null)
                    {
                      IMatchExpression<IInstallableUnit> matchExpression = iu.getFilter();

                      Map<Version, IMatchExpression<IInstallableUnit>> map = versions.get(child);
                      if (map == null)
                      {
                        map = new HashMap<Version, IMatchExpression<IInstallableUnit>>();
                        versions.put(child, map);
                      }

                      map.put(version, matchExpression);
                    }
                  }
                }
              }
            }
          }
        }
      }

      for (Map.Entry<Item, Map<Version, IMatchExpression<IInstallableUnit>>> entry : versions.entrySet())
      {
        P2UIPlugin.checkCancelation(monitor);

        Item child = entry.getKey();
        if (child instanceof VersionedItem)
        {
          VersionedItem versionedItem = (VersionedItem)child;
          versionedItem.setVersions(entry.getValue());
        }
      }

      if (children.isEmpty())
      {
        return null;
      }

      CategoryItem categoryItem = new CategoryItem();
      categoryItem.setLabel(names.get(categoryID));
      categoryItem.setChildren(children.values().toArray(new Item[children.size()]));
      return categoryItem;
    }
  }

  /**
   * @author Eike Stepper
   */
  private final class FeaturesMode extends Mode
  {
    @Override
    public void fillSelector(Composite parent)
    {
      fillCategorySelector(parent);
    }

    @Override
    public void fillItems(Composite parent)
    {
      disposeChildren(parent);

      TableViewer featuresViewer = new TableViewer(parent, SWT.BORDER | SWT.MULTI | SWT.VIRTUAL);
      featuresViewer.setUseHashlookup(true);
      featuresViewer.setContentProvider(new ItemContentProvider());
      featuresViewer.setLabelProvider(new ItemLabelProvider());
      addDragSupport(featuresViewer);

      itemsViewer = featuresViewer;
    }

    @Override
    public void analyzeInstallableUnits(IProgressMonitor monitor)
    {
      Map<String, String> names = new HashMap<String, String>();
      Map<String, Map<Version, IMatchExpression<IInstallableUnit>>> versions = new HashMap<String, Map<Version, IMatchExpression<IInstallableUnit>>>();

      for (IInstallableUnit iu : installableUnits)
      {
        P2UIPlugin.checkCancelation(monitor);
        String id = iu.getId();

        if (id.endsWith(Requirement.FEATURE_SUFFIX) && !id.endsWith(SOURCE_FEATURE_SUFFIX))
        {
          String name = P2Util.getName(iu);
          if (isFiltered(name))
          {
            names.put(id, name);

            Version version = iu.getVersion();
            IMatchExpression<IInstallableUnit> filter = iu.getFilter();

            Map<Version, IMatchExpression<IInstallableUnit>> map = versions.get(id);
            if (map == null)
            {
              map = new HashMap<Version, IMatchExpression<IInstallableUnit>>();
              versions.put(id, map);
            }

            map.put(version, filter);
          }
        }
      }

      final FeatureItem[] featureItems = new FeatureItem[versions.size()];
      Iterator<String> iterator = versions.keySet().iterator();

      for (int i = 0; i < featureItems.length; i++)
      {
        P2UIPlugin.checkCancelation(monitor);
        String id = iterator.next();
        Map<Version, IMatchExpression<IInstallableUnit>> map = versions.get(id);

        FeatureItem featureItem = new FeatureItem(id);
        featureItem.setVersions(map);
        featureItem.setLabel(names.get(id));
        featureItems[i] = featureItem;
      }

      UIUtil.asyncExec(new Runnable()
      {
        public void run()
        {
          setItems(featureItems);
        }
      });
    }
  }

  /**
   * @author Eike Stepper
   */
  private final class CapabilitiesMode extends Mode
  {
    private ComboViewer namespaceViewer;

    @Override
    public void fillSelector(Composite parent)
    {
      disposeChildren(parent);

      CCombo namespaceCombo =
          // new CCombo(parent, SWT.BORDER | SWT.READ_ONLY | SWT.FLAT);
          createCombo(parent, SWT.BORDER | SWT.READ_ONLY | SWT.FLAT, false);
      namespaceCombo.setToolTipText("Select the namespace of the capabilities to show");

      namespaceViewer = new ComboViewer(namespaceCombo);
      namespaceViewer.setComparator(new ViewerComparator());
      namespaceViewer.setContentProvider(new ArrayContentProvider());
      namespaceViewer.setLabelProvider(new LabelProvider());
      namespaceViewer.setInput(new String[] { currentNamespace });
      namespaceViewer.addSelectionChangedListener(new ISelectionChangedListener()
      {
        public void selectionChanged(SelectionChangedEvent event)
        {
          IStructuredSelection selection = (IStructuredSelection)namespaceViewer.getSelection();
          String newNamespace = (String)selection.getFirstElement();
          if (!ObjectUtil.equals(newNamespace, currentNamespace))
          {
            SETTINGS.put(CURRENT_NAMESPACE_KEY, newNamespace);
            currentNamespace = newNamespace;
            analyzeJob.reschedule();
          }
        }
      });

      namespaceViewer.setSelection(new StructuredSelection(currentNamespace));
    }

    @Override
    public void fillItems(Composite parent)
    {
      disposeChildren(parent);

      TableViewer capabilitiesViewer = new TableViewer(parent, SWT.BORDER | SWT.MULTI | SWT.VIRTUAL);
      capabilitiesViewer.setUseHashlookup(true);
      capabilitiesViewer.setContentProvider(new ItemContentProvider());
      capabilitiesViewer.setLabelProvider(new ItemLabelProvider());
      addDragSupport(capabilitiesViewer);

      itemsViewer = capabilitiesViewer;
    }

    @Override
    public void analyzeInstallableUnits(IProgressMonitor monitor)
    {
      final Set<String> flavors = new HashSet<String>();
      final Set<String> namespaces = new HashSet<String>();
      Map<String, Set<Version>> versions = new HashMap<String, Set<Version>>();

      for (IInstallableUnit iu : installableUnits)
      {
        for (IProvidedCapability capability : iu.getProvidedCapabilities())
        {
          P2UIPlugin.checkCancelation(monitor);

          String namespace = capability.getNamespace();
          String name = capability.getName();

          if ("org.eclipse.equinox.p2.flavor".equals(namespace))
          {
            flavors.add(name);
          }
          else if (!"A.PDE.Target.Platform".equalsIgnoreCase(namespace))
          {
            namespaces.add(namespace);
          }

          if (ObjectUtil.equals(namespace, currentNamespace) && isFiltered(name))
          {
            Version version = capability.getVersion();
            if (version != null && !Version.emptyVersion.equals(version))
            {
              CollectionUtil.add(versions, name, version);
            }
          }
        }
      }

      minimizeNamespaces(flavors, namespaces);

      if (!namespaces.contains(currentNamespace))
      {
        String newCurrentNamespace = null;
        if (namespaces.contains(DEFAULT_CAPABILITY_NAMESPACE))
        {
          newCurrentNamespace = DEFAULT_CAPABILITY_NAMESPACE;
        }
        else if (!namespaces.isEmpty())
        {
          newCurrentNamespace = namespaces.iterator().next();
        }

        if (newCurrentNamespace != null)
        {
          currentNamespace = newCurrentNamespace;
          analyzeInstallableUnits(monitor);
          return;
        }
      }

      final CapabilityItem[] capabilityItems = new CapabilityItem[versions.size()];
      Iterator<String> iterator = versions.keySet().iterator();

      for (int i = 0; i < capabilityItems.length; i++)
      {
        String id = iterator.next();

        CapabilityItem capabilityItem = new CapabilityItem();
        capabilityItem.setVersions(versions.get(id));
        capabilityItem.setNamespace(currentNamespace);
        capabilityItem.setLabel(id);
        capabilityItems[i] = capabilityItem;
      }

      UIUtil.asyncExec(new Runnable()
      {
        public void run()
        {
          if (!container.isDisposed())
          {
            setItems(capabilityItems);

            namespaceViewer.setInput(namespaces);
            namespaceViewer.getCCombo().pack();
            selectorComposite.getParent().layout();

            UIUtil.asyncExec(new Runnable()
            {
              public void run()
              {
                if (!container.isDisposed() && currentNamespace != null)
                {
                  namespaceViewer.setSelection(new StructuredSelection(currentNamespace));
                }
              }
            });
          }
        }
      });
    }
  }

  /**
   * @author Eike Stepper
   */
  private final class RepositoryFocusListener implements FocusListener
  {
    private Color originalForeground;

    public void focusGained(FocusEvent e)
    {
      if (originalForeground != null)
      {
        repositoryCombo.setText("");
        repositoryCombo.setForeground(originalForeground);
        originalForeground = null;
      }
    }

    public void focusLost(FocusEvent e)
    {
      String activeRepository = RepositoryManager.INSTANCE.getActiveRepository();
      if (activeRepository == null)
      {
        originalForeground = repositoryCombo.getForeground();
        repositoryCombo.setText("type repository url, drag and drop, or pick from list");
        Color color = formToolkit.getColors().getColor("initial_repository");
        repositoryCombo.setForeground(color);
      }
      else
      {
        if (!activeRepository.equals(repositoryCombo.getText()))
        {
          repositoryCombo.setText(activeRepository);
        }
      }
    }
  }

  /**
   * @author Eike Stepper
   */
  private final class RepositoryHistoryListener extends KeyAdapter implements ISelectionChangedListener
  {
    private boolean listVisible;

    private String listRepository;

    @Override
    public void keyReleased(KeyEvent e)
    {
      boolean currentListVisible = repositoryCombo.getListVisible();
      if (currentListVisible)
      {
        String repository = getSelectedRepository();
        if (!StringUtil.isEmpty(repository))
        {
          listRepository = repository;
        }
      }

      if (currentListVisible && (e.keyCode == SWT.DEL || e.keyCode == SWT.BS))
      {
        RepositoryManager.INSTANCE.removeRepository(listRepository);
      }
      else if (e.keyCode == SWT.CR && listVisible && !currentListVisible)
      {
        selectRepository();
      }

      listVisible = currentListVisible;
    }

    public void selectionChanged(SelectionChangedEvent event)
    {
      listVisible = repositoryCombo.getListVisible();
      if (!listVisible)
      {
        selectRepository();
      }
    }

    private void selectRepository()
    {
      String newRepository = getSelectedRepository();
      activateAndLoadRepository(newRepository);
    }

    private String getSelectedRepository()
    {
      IStructuredSelection selection = (IStructuredSelection)repositoryViewer.getSelection();
      return selection.isEmpty() ? repositoryCombo.getText() : (String)selection.getFirstElement();
    }
  }

  /**
   * @author Eike Stepper
   */
  private final class RepositoryContentProvider implements IStructuredContentProvider, RepositoryManagerListener
  {
    public RepositoryContentProvider()
    {
      RepositoryManager.INSTANCE.addListener(this);
    }

    public void inputChanged(Viewer viewer, Object oldInput, Object newInput)
    {
    }

    public void dispose()
    {
      RepositoryManager.INSTANCE.removeListener(this);
    }

    public Object[] getElements(Object element)
    {
      return RepositoryManager.INSTANCE.getRepositories();
    }

    public void repositoriesChanged(RepositoryManager repositoryManager)
    {
      UIUtil.asyncExec(new Runnable()
      {
        public void run()
        {
          if (!container.isDisposed())
          {
            repositoryViewer.refresh();

            UIUtil.asyncExec(new Runnable()
            {
              public void run()
              {
                if (!container.isDisposed())
                {
                  String activeRepository = RepositoryManager.INSTANCE.getActiveRepository();
                  if (activeRepository == null)
                  {
                    repositoryViewer.setSelection(StructuredSelection.EMPTY);
                    repositoryCombo.setText("");
                  }
                  else
                  {
                    ISelection selection = new StructuredSelection(activeRepository);
                    repositoryViewer.setSelection(selection);
                    repositoryCombo.setText(activeRepository);
                    repositoryCombo.setSelection(new Point(0, activeRepository.length()));
                  }
                }
              }
            });
          }
        }
      });
    }

    public void activeRepositoryChanged(RepositoryManager repositoryManager, String repository)
    {
      // Do nothing.
    }
  }

  /**
   * @author Eike Stepper
   */
  private final class ItemContentProvider implements ITreeContentProvider
  {
    public void inputChanged(Viewer viewer, Object oldInput, Object newInput)
    {
    }

    public void dispose()
    {
    }

    public Object getParent(Object element)
    {
      return null;
    }

    public Object[] getElements(Object element)
    {
      return getChildren(element);
    }

    public Object[] getChildren(Object element)
    {
      Item[] children = ((Item)element).getChildren();
      if (children != null)
      {
        return children;
      }

      return NO_ELEMENTS;
    }

    public boolean hasChildren(Object element)
    {
      return ((Item)element).hasChildren();
    }
  }

  /**
  * @author Eike Stepper
  */
  private static final class ItemLabelProvider extends LabelProvider
  {
    @Override
    public Image getImage(Object element)
    {
      Item item = (Item)element;
      return item.getImage();
    }

    @Override
    public String getText(Object element)
    {
      Item item = (Item)element;
      return item.getLabel();
    }
  }

  /**
   * @author Eike Stepper
   */
  private static final class VersionProvider extends LabelProvider implements IStructuredContentProvider
  {
    private static final Image IMAGE = P2UIPlugin.INSTANCE.getSWTImage("obj16/version");

    private TableViewer versionsViewer;

    private VersionSegment versionSegment;

    public VersionProvider()
    {
      try
      {
        versionSegment = VersionSegment.get(SETTINGS.get(VERSION_SEGMENT_KEY));
      }
      catch (Exception ex)
      {
        //$FALL-THROUGH$
      }

      if (versionSegment == null)
      {
        versionSegment = VersionSegment.QUALIFIER;
      }
    }

    public VersionSegment getVersionSegment()
    {
      return versionSegment;
    }

    public void setVersionSegment(VersionSegment versionSegment)
    {
      if (this.versionSegment != versionSegment)
      {
        this.versionSegment = versionSegment;
        SETTINGS.put(VERSION_SEGMENT_KEY, versionSegment.getLiteral());

        if (versionsViewer != null)
        {
          versionsViewer.refresh();
        }
      }
    }

    public void inputChanged(Viewer viewer, Object oldInput, Object newInput)
    {
      versionsViewer = (TableViewer)viewer;
    }

    @Override
    public void dispose()
    {
    }

    public Object[] getElements(Object inputElement)
    {
      if (inputElement instanceof VersionedItem)
      {
        VersionedItem versionedItem = (VersionedItem)inputElement;
        Map<Version, IMatchExpression<IInstallableUnit>> versions = versionedItem.getVersions();
        if (versions != null)
        {
          Set<ItemVersion> itemVersions = new HashSet<ItemVersion>();
          for (Map.Entry<Version, IMatchExpression<IInstallableUnit>> entry : versions.entrySet())
          {
            ItemVersion itemVersion = getItemVersion(entry.getKey(), entry.getValue());
            itemVersions.add(itemVersion);
          }

          ItemVersion[] array = itemVersions.toArray(new ItemVersion[itemVersions.size()]);
          Arrays.sort(array);
          return array;
        }
      }

      return NO_ELEMENTS;
    }

    @Override
    public Image getImage(Object element)
    {
      return IMAGE;
    }

    private ItemVersion getItemVersion(Version version, IMatchExpression<IInstallableUnit> filter)
    {
      int segments = version.getSegmentCount();
      if (segments == 0)
      {
        return new ItemVersion(version, "0.0.0", filter);
      }

      segments = Math.min(segments, versionSegment.ordinal() + 1);
      StringBuilder builder = new StringBuilder();

      for (int i = 0; i < segments; i++)
      {
        String segment = version.getSegment(i).toString();
        if (StringUtil.isEmpty(segment))
        {
          break;
        }

        if (builder.length() != 0)
        {
          builder.append('.');
        }

        builder.append(segment);
      }

      version = Version.create(builder.toString());

      if (segments < 3)
      {
        builder.append(".x");
      }

      return new ItemVersion(version, builder.toString(), filter);
    }

    /**
     * @author Eike Stepper
     */
    public static final class ItemVersion implements Comparable<ItemVersion>
    {
      private final Version version;

      private final String label;

      private final IMatchExpression<IInstallableUnit> filter;

      public ItemVersion(Version version, String label, IMatchExpression<IInstallableUnit> filter)
      {
        this.version = version;
        this.label = label;
        this.filter = filter;
      }

      public Version getVersion()
      {
        return version;
      }

      public IMatchExpression<IInstallableUnit> getFilter()
      {
        return filter;
      }

      public int compareTo(ItemVersion o)
      {
        return version.compareTo(o.version);
      }

      @Override
      public int hashCode()
      {
        return version.hashCode();
      }

      @Override
      public boolean equals(Object obj)
      {
        return version.equals(((ItemVersion)obj).version);
      }

      @Override
      public String toString()
      {
        return label;
      }
    }
  }

  /**
   * @author Eike Stepper
   */
  private static abstract class Item implements Comparable<Item>
  {
    protected static final Integer CATEGORY_ORDER = 0;

    protected static final Integer NON_CATEGORY_ORDER = 1;

    private String label;

    public Item()
    {
    }

    public abstract Image getImage();

    public String getNamespace()
    {
      return IInstallableUnit.NAMESPACE_IU_ID;
    }

    public String getName()
    {
      return getLabel();
    }

    public String getLabel()
    {
      return label;
    }

    public void setLabel(String label)
    {
      this.label = StringUtil.safe(label);
    }

    public Item[] getChildren()
    {
      return null;
    }

    public boolean hasChildren()
    {
      return false;
    }

    @Override
    public String toString()
    {
      return label;
    }

    @Override
    public final int hashCode()
    {
      return super.hashCode();
    }

    @Override
    public final boolean equals(Object obj)
    {
      return super.equals(obj);
    }

    public int compareTo(Item o)
    {
      Integer category1 = getCategoryOrder();
      Integer category2 = o.getCategoryOrder();

      int result = category1.compareTo(category2);
      if (result == 0)
      {
        String label1 = label.toLowerCase();
        String label2 = o.label.toLowerCase();
        result = label1.compareTo(label2);
      }

      return result;
    }

    protected Integer getCategoryOrder()
    {
      return NON_CATEGORY_ORDER;
    }
  }

  /**
   * @author Eike Stepper
   */
  private static final class LoadingItem extends Item
  {
    private static final Image IMAGE = P2UIPlugin.INSTANCE.getSWTImage("obj16/repository");

    private final URI location;

    public LoadingItem(URI location)
    {
      this.location = location;
    }

    @Override
    public Image getImage()
    {
      return IMAGE;
    }

    @Override
    public String getLabel()
    {
      return "Loading " + location;
    }
  }

  /**
   * @author Eike Stepper
   */
  private static final class ErrorItem extends Item
  {
    private final IStatus status;

    public ErrorItem(IStatus status)
    {
      this.status = status;
    }

    @Override
    public Image getImage()
    {
      return UIUtil.getStatusImage(status.getSeverity());
    }

    @Override
    public String getLabel()
    {
      return status.getMessage();
    }
  }

  /**
   * @author Eike Stepper
   */
  private static final class CategoryItem extends Item
  {
    private static final Image IMAGE = P2UIPlugin.INSTANCE.getSWTImage("obj16/category");

    private Item[] children;

    public CategoryItem()
    {
    }

    @Override
    public Image getImage()
    {
      return IMAGE;
    }

    @Override
    public boolean hasChildren()
    {
      return children != null && children.length != 0;
    }

    @Override
    public Item[] getChildren()
    {
      return children;
    }

    public void setChildren(Item[] children)
    {
      Arrays.sort(children);
      this.children = children;
    }

    @Override
    protected Integer getCategoryOrder()
    {
      return CATEGORY_ORDER;
    }
  }

  /**
   * @author Eike Stepper
   */
  private static abstract class VersionedItem extends Item
  {
    private Map<Version, IMatchExpression<IInstallableUnit>> versions;

    public VersionedItem()
    {
    }

    public Map<Version, IMatchExpression<IInstallableUnit>> getVersions()
    {
      return versions;
    }

    public void setVersions(Map<Version, IMatchExpression<IInstallableUnit>> map)
    {
      versions = map;
    }
  }

  /**
   * @author Eike Stepper
   */
  private static final class FeatureItem extends VersionedItem
  {
    private static final Image IMAGE = P2UIPlugin.INSTANCE.getSWTImage("obj16/artifactFeature");

    private final String id;

    public FeatureItem(String id)
    {
      this.id = id;
    }

    @Override
    public Image getImage()
    {
      return IMAGE;
    }

    @Override
    public String getName()
    {
      return id;
    }
  }

  /**
   * @author Eike Stepper
   */
  private static final class PluginItem extends VersionedItem
  {
    private static final Image IMAGE = P2UIPlugin.INSTANCE.getSWTImage("obj16/artifactPlugin");

    private final String id;

    public PluginItem(String id)
    {
      this.id = id;
    }

    @Override
    public Image getImage()
    {
      return IMAGE;
    }

    @Override
    public String getName()
    {
      return id;
    }
  }

  /**
   * @author Eike Stepper
   */
  private static final class CapabilityItem extends VersionedItem
  {
    private static final Image IMAGE = P2UIPlugin.INSTANCE.getSWTImage("obj16/capability");

    private static final Image FEATURE_IMAGE = P2UIPlugin.INSTANCE.getSWTImage("obj16/artifactFeature");

    private static final Image PLUGIN_IMAGE = P2UIPlugin.INSTANCE.getSWTImage("obj16/artifactPlugin");

    private static final Image PACKAGE_IMAGE = P2UIPlugin.INSTANCE.getSWTImage("full/obj16/Requirement_Package");

    private String namespace;

    public CapabilityItem()
    {
    }

    @Override
    public String getNamespace()
    {
      return namespace;
    }

    public void setNamespace(String namespace)
    {
      this.namespace = namespace;
    }

    public void setVersions(Set<Version> versions)
    {
      Map<Version, IMatchExpression<IInstallableUnit>> map = new HashMap<Version, IMatchExpression<IInstallableUnit>>();
      for (Version version : versions)
      {
        map.put(version, null);
      }

      setVersions(map);
    }

    @Override
    public Image getImage()
    {
      if (IInstallableUnit.NAMESPACE_IU_ID.equals(namespace))
      {
        if (getLabel().endsWith(Requirement.FEATURE_SUFFIX))
        {
          return FEATURE_IMAGE;
        }

        return PLUGIN_IMAGE;
      }

      if (RequirementItemProvider.NAMESPACE_PACKAGE_ID.equals(namespace))
      {
        return PACKAGE_IMAGE;
      }

      return IMAGE;
    }
  }
}
