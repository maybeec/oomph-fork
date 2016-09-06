/*
 * Copyright (c) 2014, 2015 Eike Stepper (Berlin, Germany) and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Eike Stepper - initial API and implementation
 */
package org.eclipse.oomph.setup.internal.core.util;

import org.eclipse.oomph.base.util.BaseUtil;
import org.eclipse.oomph.internal.setup.SetupProperties;
import org.eclipse.oomph.preferences.util.PreferencesUtil;
import org.eclipse.oomph.setup.internal.core.SetupContext;
import org.eclipse.oomph.setup.internal.core.SetupCorePlugin;
import org.eclipse.oomph.setup.internal.core.util.ECFURIHandlerImpl.AuthorizationHandler.Authorization;
import org.eclipse.oomph.setup.util.SetupUtil;
import org.eclipse.oomph.util.IOExceptionWithCause;
import org.eclipse.oomph.util.IORuntimeException;
import org.eclipse.oomph.util.IOUtil;
import org.eclipse.oomph.util.OS;
import org.eclipse.oomph.util.PropertiesUtil;
import org.eclipse.oomph.util.StringUtil;
import org.eclipse.oomph.util.WorkerPool;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.URIConverter;
import org.eclipse.emf.ecore.resource.impl.URIHandlerImpl;
import org.eclipse.emf.ecore.xml.type.XMLTypeFactory;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.ecf.core.ContainerCreateException;
import org.eclipse.ecf.core.ContainerFactory;
import org.eclipse.ecf.core.IContainer;
import org.eclipse.ecf.core.security.ConnectContextFactory;
import org.eclipse.ecf.core.util.Proxy;
import org.eclipse.ecf.filetransfer.BrowseFileTransferException;
import org.eclipse.ecf.filetransfer.IFileTransferListener;
import org.eclipse.ecf.filetransfer.IRemoteFile;
import org.eclipse.ecf.filetransfer.IRemoteFileInfo;
import org.eclipse.ecf.filetransfer.IRemoteFileSystemBrowserContainerAdapter;
import org.eclipse.ecf.filetransfer.IRemoteFileSystemListener;
import org.eclipse.ecf.filetransfer.IRetrieveFileTransferContainerAdapter;
import org.eclipse.ecf.filetransfer.IRetrieveFileTransferOptions;
import org.eclipse.ecf.filetransfer.IncomingFileTransferException;
import org.eclipse.ecf.filetransfer.RemoteFileSystemException;
import org.eclipse.ecf.filetransfer.UserCancelledException;
import org.eclipse.ecf.filetransfer.events.IFileTransferEvent;
import org.eclipse.ecf.filetransfer.events.IIncomingFileTransferReceiveDoneEvent;
import org.eclipse.ecf.filetransfer.events.IIncomingFileTransferReceiveStartEvent;
import org.eclipse.ecf.filetransfer.events.IRemoteFileSystemBrowseEvent;
import org.eclipse.ecf.filetransfer.events.IRemoteFileSystemEvent;
import org.eclipse.ecf.provider.filetransfer.identity.FileTransferID;
import org.eclipse.ecf.provider.filetransfer.identity.FileTransferNamespace;
import org.eclipse.ecf.provider.filetransfer.util.ProxySetupHelper;
import org.eclipse.equinox.p2.core.UIServices;
import org.eclipse.equinox.p2.core.UIServices.AuthenticationInfo;
import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.StorageException;

import org.osgi.framework.Version;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

/**
 * @author Eike Stepper
 */
public class ECFURIHandlerImpl extends URIHandlerImpl
{
  public static final String OPTION_CACHE_HANDLING = "OPTION_CACHE_HANDLING";

  public static final String OPTION_AUTHORIZATION_HANDLER = "OPTION_AUTHORIZATION_HANDLER";

  public static final String OPTION_AUTHORIZATION = "OPTION_AUTHORIZATION";

  private static final URI CACHE_FOLDER = SetupContext.GLOBAL_STATE_LOCATION_URI.appendSegment("cache");

  private static final Map<URI, String> EXPECTED_ETAGS = new HashMap<URI, String>();

  private static final boolean TEST_IO_EXCEPTION = false;

  private static final boolean TEST_SLOW_NETWORK = false;

  private static final boolean TRACE = PropertiesUtil.isProperty(SetupProperties.PROP_SETUP_ECF_TRACE);

  private static final String API_GITHUB_HOST = "api.github.com";

  private static final String CONTENT_TAG = "\"content\":\"";

  private static final int CONNECT_TIMEOUT = PropertiesUtil.getProperty(SetupProperties.PROP_SETUP_ECF_CONNECT_TIMEOUT, 10000);

  private static final int READ_TIMEOUT = PropertiesUtil.getProperty(SetupProperties.PROP_SETUP_ECF_READ_TIMEOUT, 10000);

  private static boolean loggedBlockedURI;

  private static final String USER_AGENT;
  static
  {
    String userAgentProperty = PropertiesUtil.getProperty(SetupProperties.PROP_SETUP_USER_AGENT);
    if (userAgentProperty == null)
    {
      StringBuilder userAgent = new StringBuilder("eclipse/oomph/");
      if (SetupUtil.INSTALLER_PRODUCT)
      {
        userAgent.append("installer/");
      }
      else if (SetupUtil.SETUP_ARCHIVER_APPLICATION)
      {
        userAgent.append("installer/");
      }

      Version oomphVersion = SetupCorePlugin.INSTANCE.getBundle().getVersion();
      userAgent.append(oomphVersion);
      USER_AGENT = userAgent.toString();
    }
    else
    {
      USER_AGENT = userAgentProperty;
    }
  }

  private AuthorizationHandler defaultAuthorizationHandler;

  public ECFURIHandlerImpl(AuthorizationHandler defaultAuthorizationHandler)
  {
    this.defaultAuthorizationHandler = defaultAuthorizationHandler;
  }

  @Override
  public Map<String, ?> getAttributes(URI uri, Map<?, ?> options)
  {
    if (uri.scheme().startsWith("http"))
    {
      Set<String> requestedAttributes = getRequestedAttributes(options);
      if (requestedAttributes != null && requestedAttributes.contains(URIConverter.ATTRIBUTE_READ_ONLY) && requestedAttributes.size() == 1)
      {
        // For performance reasons, this assumes that all http/https accessible files are read only.
        Map<String, Object> result = new HashMap<String, Object>();
        result.put(URIConverter.ATTRIBUTE_READ_ONLY, true);
        return result;
      }
    }

    return getRemoteAttributes(uri, options);
  }

  private Map<String, ?> getRemoteAttributes(URI uri, Map<?, ?> options)
  {
    if (uri.isPlatform())
    {
      return super.getAttributes(uri, options);
    }

    Set<String> requestedAttributes = getRequestedAttributes(options);

    CacheHandling cacheHandling = getCacheHandling(options);
    URIConverter uriConverter = getURIConverter(options);
    URI cacheURI = getCacheFile(uri);
    String eTag = cacheHandling == CacheHandling.CACHE_IGNORE ? null : getETag(uriConverter, cacheURI);
    String expectedETag = cacheHandling == CacheHandling.CACHE_IGNORE ? null : getExpectedETag(uri);

    String tracePrefix = null;
    if (TRACE)
    {
      tracePrefix = ">? ECF: " + uri;
      System.out.println(tracePrefix + " uri=" + uri);
      System.out.println(tracePrefix + " cacheURI=" + cacheURI);
      System.out.println(tracePrefix + " eTag=" + eTag);
      System.out.println(tracePrefix + " expectedETag=" + expectedETag);
    }

    if (expectedETag != null || cacheHandling == CacheHandling.CACHE_ONLY || cacheHandling == CacheHandling.CACHE_WITHOUT_ETAG_CHECKING)
    {
      if (cacheHandling == CacheHandling.CACHE_ONLY || cacheHandling == CacheHandling.CACHE_WITHOUT_ETAG_CHECKING ? eTag != null : expectedETag.equals(eTag))
      {
        Map<String, ?> result = handleAttributes(requestedAttributes, uriConverter.getAttributes(cacheURI, options));
        if (!result.isEmpty())
        {
          return result;
        }
      }
    }

    String host = getHost(uri);
    if ("git.eclipse.org".equals(host))
    {
      return Collections.emptyMap();
    }

    String username;
    String password;

    String uriString = uri.toString();
    Proxy proxy = ProxySetupHelper.getProxy(uriString);
    if (proxy != null)
    {
      username = proxy.getUsername();
      password = proxy.getPassword();
    }
    else
    {
      username = null;
      password = null;
    }

    if (TRACE)
    {
      System.out.println(tracePrefix + " proxy=" + proxy);
      System.out.println(tracePrefix + " username=" + username);
      System.out.println(tracePrefix + " password=" + PreferencesUtil.encrypt(password));
    }

    IContainer container;
    try
    {
      container = createContainer();
    }
    catch (IOException ex)
    {
      return Collections.emptyMap();
    }

    AuthorizationHandler authorizatonHandler = getAuthorizatonHandler(options);
    Authorization authorization = getAuthorizaton(options);

    if (TRACE)
    {
      System.out.println(tracePrefix + " authorizationHandler=" + authorizatonHandler);
    }

    int triedReauthorization = 0;
    for (int i = 0;; ++i)
    {
      if (TRACE)
      {
        System.out.println(tracePrefix + " trying=" + i);
        System.out.println(tracePrefix + " triedReauthorization=" + triedReauthorization);
        System.out.println(tracePrefix + " authorization=" + authorization);
      }

      IRemoteFileSystemBrowserContainerAdapter fileBrowser = container.getAdapter(IRemoteFileSystemBrowserContainerAdapter.class);

      fileBrowser.setProxy(proxy);

      if (username != null)
      {
        fileBrowser.setConnectContextForAuthentication(ConnectContextFactory.createUsernamePasswordConnectContext(username, password));
      }
      else if (password != null)
      {
        fileBrowser.setConnectContextForAuthentication(ConnectContextFactory.createPasswordConnectContext(password));
      }

      RemoteFileSystemListener fileSystemListener = new RemoteFileSystemListener();

      try
      {
        FileTransferID fileTransferID = new FileTransferID(new FileTransferNamespace(), IOUtil.newURI(uriString));

        // ECF doesn't support such options.
        // I'm not sure how it can browser a file that's authorization protected and it also behind an authorization protected firewall.
        // Map<Object, Object> requestOptions = new HashMap<Object, Object>();
        // requestOptions.put(IRetrieveFileTransferOptions.CONNECT_TIMEOUT, CONNECT_TIMEOUT);
        // requestOptions.put(IRetrieveFileTransferOptions.READ_TIMEOUT, READ_TIMEOUT);
        // if (authorization != null && authorization.isAuthorized())
        // {
        // requestOptions.put(IRetrieveFileTransferOptions.REQUEST_HEADERS, Collections.singletonMap("Authorization", authorization.getAuthorization()));
        // }

        fileBrowser.sendBrowseRequest(fileTransferID, fileSystemListener);
      }
      catch (RemoteFileSystemException ex)
      {
        return Collections.emptyMap();
      }

      try
      {
        fileSystemListener.receiveLatch.await();
      }
      catch (InterruptedException ex)
      {
        if (TRACE)
        {
          System.out.println(tracePrefix + " InterruptedException");
          ex.printStackTrace(System.out);
        }

        return Collections.emptyMap();
      }

      if (fileSystemListener.exception != null)
      {
        if (TRACE)
        {
          System.out.println(tracePrefix + " transferLister.exception");
          fileSystemListener.exception.printStackTrace(System.out);
        }

        if (!(fileSystemListener.exception instanceof UserCancelledException))
        {
          if (fileSystemListener.exception.getCause() instanceof SocketTimeoutException && i <= 2)
          {
            continue;
          }

          if (fileSystemListener.exception instanceof BrowseFileTransferException)
          {
            // We assume contents can be accessed via the github API https://developer.github.com/v3/repos/contents/#get-contents
            // That API, for security reasons, does not return HTTP_UNAUTHORIZED, so we need this special case for that host.
            BrowseFileTransferException browseFileTransferException = (BrowseFileTransferException)fileSystemListener.exception;
            int errorCode = browseFileTransferException.getErrorCode();
            if (TRACE)
            {
              System.out.println(tracePrefix + " errorCode=" + errorCode);
            }

            if (authorizatonHandler != null)
            {
              if (errorCode == HttpURLConnection.HTTP_UNAUTHORIZED || API_GITHUB_HOST.equals(getHost(uri)) && errorCode == HttpURLConnection.HTTP_NOT_FOUND)
              {
                if (authorization == null)
                {
                  authorization = authorizatonHandler.authorize(uri);
                  if (authorization.isAuthorized())
                  {
                    --i;
                    continue;
                  }
                }

                if (!authorization.isUnauthorizeable() && triedReauthorization++ < 3)
                {
                  authorization = authorizatonHandler.reauthorize(uri, authorization);
                  if (authorization.isAuthorized())
                  {
                    --i;
                    continue;
                  }
                }
              }
            }

            // We can't do a HEAD request.
            if (errorCode == HttpURLConnection.HTTP_BAD_METHOD)
            {
              // Try instead to create an input stream and use the response to get at least the timestamp property.
              Map<Object, Object> specializedOptions = new HashMap<Object, Object>(options);
              Map<Object, Object> response = new HashMap<Object, Object>();
              specializedOptions.put(URIConverter.OPTION_RESPONSE, response);
              try
              {
                InputStream inputStream = createInputStream(uri, specializedOptions);
                inputStream.close();
                return handleResponseAttributes(requestedAttributes, response);
              }
              catch (IOException ex)
              {
                // This implies a GET request also fails, so continue the processing below.
              }
            }
          }
        }

        if (!CacheHandling.CACHE_IGNORE.equals(cacheHandling) && uriConverter.exists(cacheURI, options)
            && (!(fileSystemListener.exception instanceof RemoteFileSystemException)
                || ((BrowseFileTransferException)fileSystemListener.exception).getErrorCode() != HttpURLConnection.HTTP_NOT_FOUND))
        {
          return handleAttributes(requestedAttributes, uriConverter.getAttributes(cacheURI, options));
        }

        if (TRACE)
        {
          System.out.println(tracePrefix + " failing");
        }

        return Collections.emptyMap();
      }

      // In the case of the Github API, the bytes will be JSON that contains a "content" pair containing the Base64 encoding of the actual contents.
      if (API_GITHUB_HOST.equals(getHost(uri)))
      {
        // We should have a special case for that too.
      }

      return handleAttributes(requestedAttributes, fileSystemListener.info);
    }
  }

  private final Map<String, ?> handleResponseAttributes(Set<String> requestedAttributes, Map<Object, Object> response)
  {
    Map<String, Object> result = new HashMap<String, Object>();
    if (requestedAttributes == null || requestedAttributes.contains(URIConverter.ATTRIBUTE_READ_ONLY))
    {
      result.put(URIConverter.ATTRIBUTE_READ_ONLY, true);
    }

    if (requestedAttributes == null || requestedAttributes.contains(URIConverter.ATTRIBUTE_TIME_STAMP))
    {
      Object timeStamp = response.get(URIConverter.RESPONSE_TIME_STAMP_PROPERTY);
      if (timeStamp != null)
      {
        result.put(URIConverter.ATTRIBUTE_TIME_STAMP, timeStamp);
      }
    }

    return result;
  }

  private final Map<String, ?> handleAttributes(Set<String> requestedAttributes, IRemoteFileInfo info)
  {
    Map<String, Object> result = new HashMap<String, Object>();
    if (requestedAttributes == null || requestedAttributes.contains(URIConverter.ATTRIBUTE_READ_ONLY))
    {
      result.put(URIConverter.ATTRIBUTE_READ_ONLY, true);
    }

    if (requestedAttributes == null || requestedAttributes.contains(URIConverter.ATTRIBUTE_TIME_STAMP))
    {
      result.put(URIConverter.ATTRIBUTE_TIME_STAMP, info.getLastModified());
    }

    if (requestedAttributes == null || requestedAttributes.contains(URIConverter.ATTRIBUTE_LENGTH))
    {
      result.put(URIConverter.ATTRIBUTE_LENGTH, info.getLength());
    }

    return result;
  }

  private final Map<String, ?> handleAttributes(Set<String> requestedAttributes, Map<String, ?> attributes)
  {
    Map<String, Object> result = new HashMap<String, Object>();
    if (requestedAttributes == null || requestedAttributes.contains(URIConverter.ATTRIBUTE_READ_ONLY))
    {
      result.put(URIConverter.ATTRIBUTE_READ_ONLY, true);
    }

    if (requestedAttributes == null || requestedAttributes.contains(URIConverter.ATTRIBUTE_TIME_STAMP))
    {
      Object timeStamp = attributes.get(URIConverter.ATTRIBUTE_TIME_STAMP);
      if (timeStamp != null)
      {
        result.put(URIConverter.ATTRIBUTE_TIME_STAMP, timeStamp);
      }
    }

    if (requestedAttributes == null || requestedAttributes.contains(URIConverter.ATTRIBUTE_LENGTH))
    {
      Object length = attributes.get(URIConverter.ATTRIBUTE_LENGTH);
      if (length != null)
      {
        result.put(URIConverter.ATTRIBUTE_LENGTH, length);
      }
    }

    return result;
  }

  @Override
  public boolean exists(URI uri, Map<?, ?> options)
  {
    return !getAttributes(uri, options).isEmpty();
  }

  @Override
  public InputStream createInputStream(URI uri, Map<?, ?> options) throws IOException
  {
    if (uri.isPlatform())
    {
      return super.createInputStream(uri, options);
    }

    if (TEST_IO_EXCEPTION)
    {
      File folder = new File(CACHE_FOLDER.toFileString());
      if (folder.isDirectory())
      {
        System.out.println("Deleting cache folder: " + folder);
        IOUtil.deleteBestEffort(folder);
      }

      throw new IOException("Simulated network problem");
    }

    CacheHandling cacheHandling = getCacheHandling(options);
    URIConverter uriConverter = getURIConverter(options);
    URI cacheURI = getCacheFile(uri);
    String eTag = cacheHandling == CacheHandling.CACHE_IGNORE ? null : getETag(uriConverter, cacheURI);
    String expectedETag = cacheHandling == CacheHandling.CACHE_IGNORE ? null : getExpectedETag(uri);

    String tracePrefix = null;
    if (TRACE)
    {
      tracePrefix = "> ECF: " + uri;
      System.out.println(tracePrefix + " uri=" + uri);
      System.out.println(tracePrefix + " cacheURI=" + cacheURI);
      System.out.println(tracePrefix + " eTag=" + eTag);
      System.out.println(tracePrefix + " expectedETag=" + expectedETag);
    }

    // To prevent Eclipse's Git server from being overload, because it can't scale to thousands of users, we block all access.
    String host = getHost(uri);
    boolean isBlockedEclipseGitURI = !SetupUtil.SETUP_ARCHIVER_APPLICATION && "git.eclipse.org".equals(host);
    if (isBlockedEclipseGitURI && uriConverter.exists(cacheURI, options))
    {
      // If the file is in the cache, it's okay to use that cached version, so try that first.
      cacheHandling = CacheHandling.CACHE_ONLY;
    }

    if (expectedETag != null || cacheHandling == CacheHandling.CACHE_ONLY || cacheHandling == CacheHandling.CACHE_WITHOUT_ETAG_CHECKING)
    {
      if (cacheHandling == CacheHandling.CACHE_ONLY || cacheHandling == CacheHandling.CACHE_WITHOUT_ETAG_CHECKING ? eTag != null : expectedETag.equals(eTag))
      {
        try
        {
          setExpectedETag(uri, expectedETag);
          InputStream result = uriConverter.createInputStream(cacheURI, options);
          if (TRACE)
          {
            System.out.println(tracePrefix + " returning cached content");
          }

          return result;
        }
        catch (IOException ex)
        {
          // Perhaps another JVM is busy writing this file.
          // Proceed as if it doesn't exist.
          if (TRACE)
          {
            System.out.println(tracePrefix + " unable to load cached content");
          }
        }
      }
    }

    // In general all Eclipse-hosted setups should be in the Eclipse project or product catalog and therefore should be in
    // SetupContext.INDEX_SETUP_ARCHIVE_LOCATION_URI or should already be in the cache from running the setup archiver application.
    if (isBlockedEclipseGitURI)
    {
      synchronized (this)
      {
        if (!loggedBlockedURI)
        {
          String launcher = OS.getCurrentLauncher(true);
          if (launcher == null)
          {
            launcher = "eclipse";
          }

          // We'll log a single warning for this case.
          SetupCorePlugin.INSTANCE.log(
              "The Eclipse Git-hosted URI '" + uri + "' is blocked for direct access." + StringUtil.NL + //
                  "Please open a Bugzilla to add it to an official Oomph catalog." + StringUtil.NL + //
                  "For initial testing, use the file system local version of the resource." + StringUtil.NL + //
                  "Alternatively, run the setup archiver application as follows:" + StringUtil.NL + //
                  "  " + launcher + " -application org.eclipse.oomph.setup.core.SetupArchiver -consoleLog -noSplash -uris " + uri, //
              IStatus.WARNING);
          loggedBlockedURI = true;
        }
      }

      throw new IOException("Eclipse Git access blocked: " + uri);
    }

    String username;
    String password;

    String uriString = uri.toString();
    Proxy proxy = ProxySetupHelper.getProxy(uriString);
    if (proxy != null)
    {
      username = proxy.getUsername();
      password = proxy.getPassword();
    }
    else
    {
      username = null;
      password = null;
    }

    if (TRACE)
    {
      System.out.println(tracePrefix + " proxy=" + proxy);
      System.out.println(tracePrefix + " username=" + username);
      System.out.println(tracePrefix + " password=" + PreferencesUtil.encrypt(password));
    }

    IContainer container = createContainer();

    AuthorizationHandler authorizatonHandler = getAuthorizatonHandler(options);
    Authorization authorization = getAuthorizaton(options);

    if (TRACE)
    {
      System.out.println(tracePrefix + " authorizationHandler=" + authorizatonHandler);
    }

    int triedReauthorization = 0;
    for (int i = 0;; ++i)
    {
      if (TRACE)
      {
        System.out.println(tracePrefix + " trying=" + i);
        System.out.println(tracePrefix + " triedReauthorization=" + triedReauthorization);
        System.out.println(tracePrefix + " authorization=" + authorization);
      }

      IRetrieveFileTransferContainerAdapter fileTransfer = container.getAdapter(IRetrieveFileTransferContainerAdapter.class);

      fileTransfer.setProxy(proxy);

      if (username != null)
      {
        fileTransfer.setConnectContextForAuthentication(ConnectContextFactory.createUsernamePasswordConnectContext(username, password));
      }
      else if (password != null)
      {
        fileTransfer.setConnectContextForAuthentication(ConnectContextFactory.createPasswordConnectContext(password));
      }

      FileTransferListener transferListener = new FileTransferListener(eTag);

      try
      {
        FileTransferID fileTransferID = new FileTransferID(new FileTransferNamespace(), IOUtil.newURI(uriString));
        Map<Object, Object> requestOptions = new HashMap<Object, Object>();
        requestOptions.put(IRetrieveFileTransferOptions.CONNECT_TIMEOUT, CONNECT_TIMEOUT);
        requestOptions.put(IRetrieveFileTransferOptions.READ_TIMEOUT, READ_TIMEOUT);
        if (authorization != null && authorization.isAuthorized())
        {
          requestOptions.put(IRetrieveFileTransferOptions.REQUEST_HEADERS, Collections.singletonMap("Authorization", authorization.getAuthorization()));
        }

        if (!StringUtil.isEmpty(USER_AGENT) && host != null && host.endsWith(".eclipse.org"))
        {
          Map<String, String> requestHeaders = new HashMap<String, String>();
          requestOptions.put(IRetrieveFileTransferOptions.REQUEST_HEADERS, requestHeaders);
          requestHeaders.put("User-Agent", USER_AGENT);
        }

        fileTransfer.sendRetrieveRequest(fileTransferID, transferListener, requestOptions);
      }
      catch (IncomingFileTransferException ex)
      {
        if (TRACE)
        {
          System.out.println(tracePrefix + " IncomingFileTransferException");
          ex.printStackTrace(System.out);
        }

        throw new IOExceptionWithCause(ex);
      }

      try
      {
        transferListener.receiveLatch.await();
      }
      catch (InterruptedException ex)
      {
        if (TRACE)
        {
          System.out.println(tracePrefix + " InterruptedException");
          ex.printStackTrace(System.out);
        }

        throw new IOExceptionWithCause(ex);
      }

      if (transferListener.exception != null)
      {
        if (TRACE)
        {
          System.out.println(tracePrefix + " transferLister.exception");
          transferListener.exception.printStackTrace(System.out);
        }

        if (!(transferListener.exception instanceof UserCancelledException))
        {
          if (transferListener.exception.getCause() instanceof SocketTimeoutException && i <= 2)
          {
            continue;
          }

          if (authorizatonHandler != null && transferListener.exception instanceof IncomingFileTransferException)
          {
            // We assume contents can be accessed via the github API https://developer.github.com/v3/repos/contents/#get-contents
            // That API, for security reasons, does not return HTTP_UNAUTHORIZED, so we need this special case for that host.
            IncomingFileTransferException incomingFileTransferException = (IncomingFileTransferException)transferListener.exception;
            int errorCode = incomingFileTransferException.getErrorCode();
            if (TRACE)
            {
              System.out.println(tracePrefix + " errorCode=" + errorCode);
            }

            if (errorCode == HttpURLConnection.HTTP_UNAUTHORIZED || API_GITHUB_HOST.equals(getHost(uri)) && errorCode == HttpURLConnection.HTTP_NOT_FOUND)
            {
              if (authorization == null)
              {
                authorization = authorizatonHandler.authorize(uri);
                if (authorization.isAuthorized())
                {
                  --i;
                  continue;
                }
              }

              if (!authorization.isUnauthorizeable() && triedReauthorization++ < 3)
              {
                authorization = authorizatonHandler.reauthorize(uri, authorization);
                if (authorization.isAuthorized())
                {
                  --i;
                  continue;
                }
              }
            }
          }
        }

        if (!CacheHandling.CACHE_IGNORE.equals(cacheHandling) && uriConverter.exists(cacheURI, options)
            && (!(transferListener.exception instanceof IncomingFileTransferException)
                || ((IncomingFileTransferException)transferListener.exception).getErrorCode() != HttpURLConnection.HTTP_NOT_FOUND)
            || uri.equals(SetupContext.INDEX_SETUP_ARCHIVE_LOCATION_URI))
        {
          setExpectedETag(uri, transferListener.eTag == null ? eTag : transferListener.eTag);
          if (TRACE)
          {
            System.out.println(tracePrefix + " returning cache content");
          }

          return uriConverter.createInputStream(cacheURI, options);
        }

        if (TRACE)
        {
          System.out.println(tracePrefix + " failing");
        }

        throw new IOExceptionWithCause(transferListener.exception);
      }

      byte[] bytes = transferListener.out.toByteArray();

      // In the case of the Github API, the bytes will be JSON that contains a "content" pair containing the Base64 encoding of the actual contents.
      if (API_GITHUB_HOST.equals(getHost(uri)))
      {
        // Find the start tag in the JSON value.
        String value = new String(bytes, "UTF-8");
        int start = value.indexOf(CONTENT_TAG);
        if (start != -1)
        {
          // Find the ending quote of the encoded contents.
          start += CONTENT_TAG.length();
          int end = value.indexOf('"', start);
          if (end != -1)
          {
            // The content is delimited by \n so split on that during the conversion.
            String content = value.substring(start, end);
            String[] split = content.split("\\\\n");

            // Write the converted bytes to a new stream and process those bytes instead.
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (String line : split)
            {
              byte[] binary = XMLTypeFactory.eINSTANCE.createBase64Binary(line);
              out.write(binary);
            }

            out.close();
            bytes = out.toByteArray();
          }
        }
      }

      try
      {
        if (TRACE)
        {
          System.out.println(tracePrefix + " writing cache");
        }

        BaseUtil.writeFile(uriConverter, options, cacheURI, bytes);
      }
      catch (IORuntimeException ex)
      {
        // Ignore attempts to write out to the cache file.
        // This may collide with another JVM doing exactly the same thing.
        transferListener.eTag = null;

        if (TRACE)
        {
          System.out.println(tracePrefix + " failed writing cache");
          ex.printStackTrace(System.out);
        }
      }
      finally
      {
        setETag(uriConverter, cacheURI, transferListener.eTag);
      }

      setExpectedETag(uri, transferListener.eTag);
      Map<Object, Object> response = getResponse(options);
      if (response != null)
      {
        response.put(URIConverter.RESPONSE_TIME_STAMP_PROPERTY, transferListener.lastModified);
      }

      ETagMirror etagMirror = (ETagMirror)options.get(ETagMirror.OPTION_ETAG_MIRROR);
      if (etagMirror != null)
      {
        etagMirror.cacheUpdated(uri);
      }

      if (TRACE)
      {
        System.out.println(tracePrefix + " returning successful results");
      }

      return new ByteArrayInputStream(bytes);
    }
  }

  private IContainer createContainer() throws IOException
  {
    try
    {
      return ContainerFactory.getDefault().createContainer();
    }
    catch (ContainerCreateException ex)
    {
      throw new IOExceptionWithCause(ex);
    }
  }

  public static Set<? extends URI> clearExpectedETags()
  {
    Set<URI> result;
    synchronized (EXPECTED_ETAGS)
    {
      result = new HashSet<URI>(EXPECTED_ETAGS.keySet());
      EXPECTED_ETAGS.clear();
    }

    return result;
  }

  public static Job mirror(final Set<? extends URI> uris)
  {
    Job job = new Job("ETag Mirror")
    {
      @Override
      protected IStatus run(IProgressMonitor monitor)
      {
        new ETagMirror().begin(uris, monitor);
        return Status.OK_STATUS;
      }
    };

    job.schedule();
    return job;
  }

  public static URI getCacheFile(URI uri)
  {
    return CACHE_FOLDER.appendSegment(IOUtil.encodeFileName(uri.toString()));
  }

  public static String getETag(URIConverter uriConverter, URI file)
  {
    if (uriConverter.exists(file, null))
    {
      URI eTagFile = file.appendFileExtension("etag");
      if (uriConverter.exists(eTagFile, null))
      {
        try
        {
          return new String(BaseUtil.readFile(uriConverter, null, eTagFile), "UTF-8");
        }
        catch (IORuntimeException ex)
        {
          // If we can't read the ETag, we'll just return null.
        }
        catch (UnsupportedEncodingException ex)
        {
          // All systems support UTF-8.
        }
      }
    }

    return null;
  }

  public static void setETag(URIConverter uriConverter, URI file, String eTag)
  {
    try
    {
      if (eTag != null)
      {
        BaseUtil.writeFile(uriConverter, null, file.appendFileExtension("etag"), eTag.getBytes("UTF-8"));
      }
      else
      {
        BaseUtil.deleteFile(uriConverter, null, file);
      }
    }
    catch (IORuntimeException ex)
    {
      // If we can't write the ETag, perhaps some other process is writing it, but it's expected to write the same ETag value.
    }
    catch (UnsupportedEncodingException ex)
    {
      // All systems support UTF-8.
    }
  }

  private AuthorizationHandler getAuthorizatonHandler(Map<?, ?> options)
  {
    if (options.containsKey(OPTION_AUTHORIZATION_HANDLER))
    {
      return (AuthorizationHandler)options.get(OPTION_AUTHORIZATION_HANDLER);
    }

    return defaultAuthorizationHandler;
  }

  private static String getHost(URI uri)
  {
    String authority = uri.authority();
    if (authority != null)
    {
      int i = authority.indexOf('@');
      int j = authority.indexOf(':', i + 1);
      return j < 0 ? authority.substring(i + 1) : authority.substring(i + 1, j);
    }

    return null;
  }

  @SuppressWarnings("all")
  private static Date parseHTTPDate(String string)
  {
    try
    {
      return org.apache.http.impl.cookie.DateUtils.parseDate(string);
    }
    catch (Exception ex)
    {
      //$FALL-THROUGH$
    }

    return null;
  }

  static String getExpectedETag(URI uri)
  {
    synchronized (EXPECTED_ETAGS)
    {
      return EXPECTED_ETAGS.get(uri);
    }
  }

  private static void setExpectedETag(URI uri, String eTag)
  {
    synchronized (EXPECTED_ETAGS)
    {
      String originalExpectedETag = EXPECTED_ETAGS.put(uri, eTag);
      if (eTag == null && originalExpectedETag != null)
      {
        EXPECTED_ETAGS.put(uri, originalExpectedETag);
      }
    }
  }

  private static CacheHandling getCacheHandling(Map<?, ?> options)
  {
    CacheHandling cacheHandling = (CacheHandling)options.get(OPTION_CACHE_HANDLING);
    if (cacheHandling == null)
    {
      cacheHandling = CacheHandling.CACHE_WITH_ETAG_CHECKING;
    }

    return cacheHandling;
  }

  private static Authorization getAuthorizaton(Map<?, ?> options)
  {
    return (Authorization)options.get(OPTION_AUTHORIZATION);
  }

  /**
   * @author Ed Merks
   */
  public enum CacheHandling
  {
    CACHE_ONLY, CACHE_WITHOUT_ETAG_CHECKING, CACHE_WITH_ETAG_CHECKING, CACHE_IGNORE
  }

  /**
   * @author Ed Merks
   */
  public interface AuthorizationHandler
  {
    public Authorization authorize(URI uri);

    public Authorization reauthorize(URI uri, Authorization authorization);

    /**
     * @author Ed Merks
     */
    public static final class Authorization
    {
      public static final Authorization UNAUTHORIZED = new Authorization("", "");

      public static final Authorization UNAUTHORIZEABLE = new Authorization("", "");

      private final String user;

      private final String password;

      public Authorization(String user, String password)
      {
        this.user = user == null ? "" : user;
        this.password = obscure(password == null ? "" : password);
      }

      public String getUser()
      {
        return user;
      }

      public String getPassword()
      {
        return unobscure(password);
      }

      public String getAuthorization()
      {
        return "Basic " + obscure(user.length() == 0 ? getPassword() : user + ":" + getPassword());
      }

      public boolean isAuthorized()
      {
        return !"".equals(password);
      }

      public boolean isUnauthorizeable()
      {
        return this == UNAUTHORIZEABLE;
      }

      private String obscure(String string)
      {
        return XMLTypeFactory.eINSTANCE.convertBase64Binary(string.getBytes());
      }

      private String unobscure(String string)
      {
        return new String(XMLTypeFactory.eINSTANCE.createBase64Binary(string));
      }

      @Override
      public int hashCode()
      {
        final int prime = 31;
        int result = 1;
        result = prime * result + (user == null ? 0 : user.hashCode());
        result = prime * result + (password == null ? 0 : password.hashCode());
        return result;
      }

      @Override
      public boolean equals(Object obj)
      {
        if (this == obj)
        {
          return true;
        }

        if (obj == null)
        {
          return false;
        }

        if (getClass() != obj.getClass())
        {
          return false;
        }

        if (this == UNAUTHORIZEABLE)
        {
          return obj == UNAUTHORIZEABLE;
        }

        Authorization other = (Authorization)obj;
        if (!user.equals(other.user))
        {
          return false;
        }

        if (!password.equals(other.password))
        {
          return false;
        }

        return true;
      }

      @Override
      public String toString()
      {
        return this == UNAUTHORIZEABLE ? "Authorization [unauthorizeable]" : "Authorization [user=" + user + ", password=" + password + "]";
      }
    }
  }

  /**
   * @author Ed Merks
   */
  public static class AuthorizationHandlerImpl implements AuthorizationHandler
  {
    private final Map<String, Authorization> authorizations = new HashMap<String, Authorization>();

    private final UIServices uiServices;

    private ISecurePreferences securePreferences;

    public AuthorizationHandlerImpl(UIServices uiServices, ISecurePreferences securePreferences)
    {
      this.uiServices = uiServices;
      this.securePreferences = securePreferences;
    }

    public synchronized void clearCache()
    {
      authorizations.clear();
    }

    public synchronized Authorization authorize(URI uri)
    {
      String host = getHost(uri);
      if (host != null)
      {
        Authorization cachedAuthorization = authorizations.get(host);
        if (cachedAuthorization == Authorization.UNAUTHORIZEABLE)
        {
          return cachedAuthorization;
        }

        if (securePreferences != null)
        {
          try
          {
            ISecurePreferences node = securePreferences.node(host);
            String user = node.get("user", "");
            String password = node.get("password", "");

            Authorization authorization = new Authorization(user, password);
            if (authorization.isAuthorized())
            {
              authorizations.put(host, authorization);
              return authorization;
            }
          }
          catch (StorageException ex)
          {
            SetupCorePlugin.INSTANCE.log(ex);
          }
        }

        if (cachedAuthorization != null)
        {
          return cachedAuthorization;
        }
      }

      return Authorization.UNAUTHORIZED;
    }

    public synchronized Authorization reauthorize(URI uri, Authorization authorization)
    {
      // Double check that another thread hasn't already prompted and updated the secure store or has not already permanently failed to authorize.
      Authorization currentAuthorization = authorize(uri);
      if (!currentAuthorization.equals(authorization) || currentAuthorization == Authorization.UNAUTHORIZEABLE)
      {
        return currentAuthorization;
      }

      if (uiServices != null)
      {
        String host = getHost(uri);
        if (host != null)
        {
          AuthenticationInfo authenticationInfo = uiServices.getUsernamePassword(uri.toString());
          String user = authenticationInfo.getUserName();
          String password = authenticationInfo.getPassword();
          Authorization reauthorization = new Authorization(user, password);
          if (reauthorization.isAuthorized())
          {
            if (authenticationInfo.saveResult() && securePreferences != null)
            {
              try
              {
                ISecurePreferences node = securePreferences.node(host);
                node.put("user", user, false);
                node.put("password", password, true);
                node.flush();
              }
              catch (IOException ex)
              {
                SetupCorePlugin.INSTANCE.log(ex);
              }
              catch (StorageException ex)
              {
                SetupCorePlugin.INSTANCE.log(ex);
              }
            }

            authorizations.put(host, reauthorization);
            return reauthorization;
          }
          else
          {
            authorizations.put(host, Authorization.UNAUTHORIZEABLE);
            return Authorization.UNAUTHORIZEABLE;
          }
        }
      }

      return currentAuthorization;
    }
  }

  /**
   * @author Eike Stepper
   */
  private static final class FileTransferListener implements IFileTransferListener
  {
    public final CountDownLatch receiveLatch = new CountDownLatch(1);

    public final String expectedETag;

    public String eTag;

    public ByteArrayOutputStream out;

    public long lastModified;

    public Exception exception;

    public FileTransferListener(String expectedETag)
    {
      this.expectedETag = expectedETag;
    }

    public void handleTransferEvent(IFileTransferEvent event)
    {
      if (event instanceof IIncomingFileTransferReceiveStartEvent)
      {
        IIncomingFileTransferReceiveStartEvent receiveStartEvent = (IIncomingFileTransferReceiveStartEvent)event;

        out = new ByteArrayOutputStream();

        @SuppressWarnings("rawtypes")
        Map responseHeaders = receiveStartEvent.getResponseHeaders();
        if (responseHeaders != null)
        {
          eTag = (String)responseHeaders.get("ETag");

          String lastModifiedValue = (String)responseHeaders.get("Last-Modified");
          if (lastModifiedValue != null)
          {
            Date date = parseHTTPDate(lastModifiedValue.toString());
            if (date != null)
            {
              lastModified = date.getTime();
              if (eTag == null)
              {
                eTag = Long.toString(lastModified);
              }
            }
          }

          if (expectedETag != null && expectedETag.equals(eTag))
          {
            receiveStartEvent.cancel();

            // Older versions of ECF don't produce a IIncomingFileTransferReceiveDoneEvent.
            exception = new UserCancelledException();
            receiveLatch.countDown();
            return;
          }

          boolean isHTTPS;
          try
          {
            isHTTPS = "https".equals(receiveStartEvent.getFileID().getURI().getScheme());
          }
          catch (URISyntaxException ex)
          {
            isHTTPS = false;
          }

          if (isHTTPS && responseHeaders.get("Set-Cookie") != null)
          {
            try
            {
              java.net.URI uri = ((IIncomingFileTransferReceiveStartEvent)event).getFileID().getURI();
              if ("bitbucket.org".equals(uri.getHost()))
              {
                IncomingFileTransferException incomingFileTransferException = new IncomingFileTransferException(HttpURLConnection.HTTP_UNAUTHORIZED);
                incomingFileTransferException.fillInStackTrace();
                exception = incomingFileTransferException;
                receiveStartEvent.cancel();

                // Older versions of ECF don't produce a IIncomingFileTransferReceiveDoneEvent.
                // exception = new UserCancelledException();
                receiveLatch.countDown();
                return;
              }
            }
            catch (URISyntaxException ex)
            {
              // Ignore.
            }
          }
        }

        try
        {
          receiveStartEvent.receive(out);
        }
        catch (IOException ex)
        {
          exception = ex;
        }
      }
      else if (event instanceof IIncomingFileTransferReceiveDoneEvent)
      {
        IIncomingFileTransferReceiveDoneEvent done = (IIncomingFileTransferReceiveDoneEvent)event;
        Exception ex = done.getException();
        if (ex != null && exception == null)
        {
          exception = ex;
        }

        receiveLatch.countDown();
      }
    }
  }

  /**
   * @author Ed Merks
   */
  private static final class RemoteFileSystemListener implements IRemoteFileSystemListener
  {
    public final CountDownLatch receiveLatch = new CountDownLatch(1);

    public Exception exception;

    public IRemoteFileInfo info;

    public RemoteFileSystemListener()
    {
    }

    public void handleRemoteFileEvent(IRemoteFileSystemEvent event)
    {
      if (event instanceof IRemoteFileSystemBrowseEvent)
      {
        IRemoteFileSystemBrowseEvent browseEvent = (IRemoteFileSystemBrowseEvent)event;
        exception = browseEvent.getException();
        if (exception == null)
        {
          for (IRemoteFile remoteFile : browseEvent.getRemoteFiles())
          {
            info = remoteFile.getInfo();
            break;
          }
        }

        receiveLatch.countDown();
      }
    }
  }

  /**
   * @author Ed Merks
   */
  public static class ETagMirror extends WorkerPool<ETagMirror, URI, ETagMirror.Worker>
  {
    private static final Map<Object, Object> OPTIONS;

    private static final URIConverter URI_CONVERTER;

    private static final String OPTION_ETAG_MIRROR = "OPTION_ETAG_MIRROR";

    static
    {
      ResourceSet resourceSet = SetupCoreUtil.createResourceSet();
      OPTIONS = resourceSet.getLoadOptions();
      OPTIONS.put(OPTION_CACHE_HANDLING, CacheHandling.CACHE_WITH_ETAG_CHECKING);
      URI_CONVERTER = resourceSet.getURIConverter();
    }

    private Set<? extends URI> uris;

    private Map<Object, Object> options = new HashMap<Object, Object>(OPTIONS);

    public ETagMirror()
    {
      options.put(OPTION_ETAG_MIRROR, this);
    }

    @Override
    protected Worker createWorker(URI key, int workerID, boolean secondary)
    {
      return new Worker("ETag Mirror " + key, this, key, workerID, secondary);
    }

    public void begin(Set<? extends URI> uris, final IProgressMonitor monitor)
    {
      this.uris = uris;
      int size = uris.size();
      monitor.beginTask("Mirroring " + size + " resource" + (size == 1 ? "" : "s"), uris.size());
      super.begin("Mirroring", monitor);
    }

    @Override
    protected void run(String taskName, IProgressMonitor monitor)
    {
      perform(uris);
    }

    protected void cacheUpdated(URI uri)
    {
    }

    /**
     * @author Ed Merks
     */
    private static class Worker extends WorkerPool.Worker<URI, ETagMirror>
    {
      protected Worker(String name, ETagMirror workPool, URI key, int id, boolean secondary)
      {
        super(name, workPool, key, id, secondary);
      }

      @Override
      protected IStatus perform(IProgressMonitor monitor)
      {
        URI key = getKey();
        ETagMirror workPool = getWorkPool();
        IProgressMonitor workpoolMonitor = workPool.getMonitor();

        try
        {
          workpoolMonitor.subTask("Mirroring " + key);
        }
        catch (Exception ex)
        {
          SetupCorePlugin.INSTANCE.log(ex, IStatus.WARNING);
        }

        try
        {
          if (TEST_SLOW_NETWORK)
          {
            try
            {
              Thread.sleep(5000);
            }
            catch (InterruptedException ex)
            {
              ex.printStackTrace();
            }
          }

          URI_CONVERTER.createInputStream(key, workPool.options).close();
        }
        catch (IOException ex)
        {
          SetupCorePlugin.INSTANCE.log(ex, IStatus.WARNING);
        }
        finally
        {
          try
          {
            workpoolMonitor.worked(1);
          }
          catch (Exception ex)
          {
            SetupCorePlugin.INSTANCE.log(ex, IStatus.WARNING);
          }
        }

        return Status.OK_STATUS;
      }
    }
  }
}
