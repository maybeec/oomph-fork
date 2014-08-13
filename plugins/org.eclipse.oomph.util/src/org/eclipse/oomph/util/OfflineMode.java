/*
 * Copyright (c) 2014 Eike Stepper (Berlin, Germany) and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Eike Stepper - initial API and implementation
 */
package org.eclipse.oomph.util;

import org.eclipse.oomph.internal.util.UtilPlugin;
import org.eclipse.oomph.internal.util.UtilPlugin.ToggleStateAccessor;

import org.eclipse.emf.common.CommonPlugin;

/**
 * @author Eike Stepper
 */
public class OfflineMode
{
  private static final String COMMAND_ID = "org.eclipse.oomph.ui.ToggleOfflineMode";

  private static boolean initializedToggleStateAccessor;

  public OfflineMode()
  {
  }

  public static boolean isEnabled()
  {
    ToggleStateAccessor toggleStateAccessor = UtilPlugin.getToggleStateAccessor();
    return toggleStateAccessor.isEnabled(COMMAND_ID);
  }

  public static void setEnabled(boolean enabled)
  {
    if (!initializedToggleStateAccessor)
    {
      injectUILevelAccessor();
      initializedToggleStateAccessor = true;
    }

    ToggleStateAccessor toggleStateAccessor = UtilPlugin.getToggleStateAccessor();
    toggleStateAccessor.setEnabled(COMMAND_ID, enabled);
  }

  private static void injectUILevelAccessor()
  {
    try
    {
      CommonPlugin.loadClass("org.eclipse.oomph.ui", "org.eclipse.oomph.internal.ui.UIPlugin");
    }
    catch (Throwable t)
    {
      // Ignore.
    }
  }
}
