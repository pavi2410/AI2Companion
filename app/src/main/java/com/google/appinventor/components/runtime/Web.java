// -*- mode: java; c-basic-offset: 2; -*-
// Copyright 2009-2011 Google, All Rights reserved
// Copyright 2011-2020 MIT, All rights reserved
// Released under the Apache License, Version 2.0
// http://www.apache.org/licenses/LICENSE-2.0

package com.google.appinventor.components.runtime;

import android.Manifest;
import android.app.Activity;

import android.text.TextUtils;

import android.util.Log;

import androidx.annotation.VisibleForTesting;

import com.google.appinventor.components.annotations.DesignerComponent;
import com.google.appinventor.components.annotations.DesignerProperty;
import com.google.appinventor.components.annotations.PropertyCategory;
import com.google.appinventor.components.annotations.SimpleEvent;
import com.google.appinventor.components.annotations.SimpleFunction;
import com.google.appinventor.components.annotations.SimpleObject;
import com.google.appinventor.components.annotations.SimpleProperty;
import com.google.appinventor.components.annotations.UsesLibraries;
import com.google.appinventor.components.annotations.UsesPermissions;

import com.google.appinventor.components.common.ComponentCategory;
import com.google.appinventor.components.common.HtmlEntities;
import com.google.appinventor.components.common.PropertyTypeConstants;
import com.google.appinventor.components.common.YaVersion;

import com.google.appinventor.components.runtime.collect.Lists;
import com.google.appinventor.components.runtime.collect.Maps;

import com.google.appinventor.components.runtime.errors.IllegalArgumentError;
import com.google.appinventor.components.runtime.errors.PermissionException;
import com.google.appinventor.components.runtime.errors.RequestTimeoutException;

import com.google.appinventor.components.runtime.repackaged.org.json.XML;

import com.google.appinventor.components.runtime.util.AsynchUtil;
import com.google.appinventor.components.runtime.util.BulkPermissionRequest;
import com.google.appinventor.components.runtime.util.ErrorMessages;
import com.google.appinventor.components.runtime.util.FileUtil;
import com.google.appinventor.components.runtime.util.GingerbreadUtil;
import com.google.appinventor.components.runtime.util.JsonUtil;
import com.google.appinventor.components.runtime.util.MediaUtil;
import com.google.appinventor.components.runtime.util.SdkLevel;
import com.google.appinventor.components.runtime.util.XmlParser;
import com.google.appinventor.components.runtime.util.YailDictionary;
import com.google.appinventor.components.runtime.util.YailList;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;

import java.net.CookieHandler;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.SocketTimeoutException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;

import java.util.List;
import java.util.Map;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.json.JSONException;

import org.xml.sax.InputSource;

/**
 * Non-visible component that provides functions for HTTP GET, POST, PUT, and DELETE requests.
 *
 * @author lizlooney@google.com (Liz Looney)
 * @author josmasflores@gmail.com (Jose Dominguez)
 */
@DesignerComponent(version = YaVersion.WEB_COMPONENT_VERSION,
    description = "Non-visible component that provides functions for HTTP GET, POST, PUT, and DELETE requests.",
    category = ComponentCategory.CONNECTIVITY,
    nonVisible = true,
    iconName = "images/web.png")
@SimpleObject
@UsesPermissions(permissionNames = "android.permission.INTERNET," +
  "android.permission.WRITE_EXTERNAL_STORAGE," +
  "android.permission.READ_EXTERNAL_STORAGE")
@UsesLibraries(libraries = "json.jar")


public class Web extends AndroidNonvisibleComponent implements Component {
  /**
   * InvalidRequestHeadersException can be thrown from processRequestHeaders.
   * It is thrown if the list passed to processRequestHeaders contains an item that is not a list.
   * It is thrown if the list passed to processRequestHeaders contains an item that is a list whose
   * size is not 2.
   */
  private static class InvalidRequestHeadersException extends Exception {
    /*
     * errorNumber could be:
     * ErrorMessages.ERROR_WEB_REQUEST_HEADER_NOT_LIST
     * ErrorMessages.ERROR_WEB_REQUEST_HEADER_NOT_TWO_ELEMENTS
     */
    final int errorNumber;
    final int index;         // the index of the invalid header

    InvalidRequestHeadersException(int errorNumber, int index) {
      super();
      this.errorNumber = errorNumber;
      this.index = index;
    }
  }

  /**
   * BuildRequestDataException can be thrown from buildRequestData.
   * It is thrown if the list passed to buildRequestData contains an item that is not a list.
   * It is thrown if the list passed to buildRequestData contains an item that is a list whose size is
   * not 2.
   */
  // VisibleForTesting
  static class BuildRequestDataException extends Exception {
    /*
     * errorNumber could be:
     * ErrorMessages.ERROR_WEB_BUILD_REQUEST_DATA_NOT_LIST
     * ErrorMessages.ERROR_WEB_BUILD_REQUEST_DATA_NOT_TWO_ELEMENTS
     */
    final int errorNumber;
    final int index;         // the index of the invalid header

    BuildRequestDataException(int errorNumber, int index) {
      super();
      this.errorNumber = errorNumber;
      this.index = index;
    }
  }

  /**
   * The CapturedProperties class captures the current property values from a Web component before
   * an asynchronous request is made. This avoids concurrency problems if the user changes a
   * property value after initiating an asynchronous request.
   */
  private static class CapturedProperties {
    final String urlString;
    final URL url;
    final boolean allowCookies;
    final boolean saveResponse;
    final String responseFileName;
    final int timeout;
    final Map<String, List<String>> requestHeaders;
    final Map<String, List<String>> cookies;

    CapturedProperties(Web web) throws MalformedURLException, InvalidRequestHeadersException {
      urlString = web.urlString;
      url = new URL(urlString);
      allowCookies = web.allowCookies;
      saveResponse = web.saveResponse;
      responseFileName = web.responseFileName;
      timeout = web.timeout;
      requestHeaders = processRequestHeaders(web.requestHeaders);

      Map<String, List<String>> cookiesTemp = null;
      if (allowCookies && web.cookieHandler != null) {
        try {
          cookiesTemp = web.cookieHandler.get(url.toURI(), requestHeaders);
        } catch (URISyntaxException e) {
          // Can't convert the URL to a URI; no cookies for you.
        } catch (IOException e) {
          // Sorry, no cookies for you.
        }
      }
      cookies = cookiesTemp;
    }
  }

  private static final String LOG_TAG = "Web";

  private static final Map<String, String> mimeTypeToExtension;
  static {
    mimeTypeToExtension = Maps.newHashMap();
    mimeTypeToExtension.put("application/pdf", "pdf");
    mimeTypeToExtension.put("application/zip", "zip");
    mimeTypeToExtension.put("audio/mpeg", "mpeg");
    mimeTypeToExtension.put("audio/mp3", "mp3");
    mimeTypeToExtension.put("audio/mp4", "mp4");
    mimeTypeToExtension.put("image/gif", "gif");
    mimeTypeToExtension.put("image/jpeg", "jpg");
    mimeTypeToExtension.put("image/png", "png");
    mimeTypeToExtension.put("image/tiff", "tiff");
    mimeTypeToExtension.put("text/plain", "txt");
    mimeTypeToExtension.put("text/html", "html");
    mimeTypeToExtension.put("text/xml", "xml");
    // TODO(lizlooney) - consider adding more mime types.
  }

  private final Activity activity;
  private final CookieHandler cookieHandler;

  private String urlString = "";
  private boolean allowCookies;
  private YailList requestHeaders = new YailList();
  private boolean saveResponse;
  private String responseFileName = "";
  private int timeout = 0;

  // wether or not we have permission to manipulate external storage

  private boolean havePermission = false;



  /**
   * Creates a new Web component.
   *
   * @param container the Form that this component is contained in.
   */
  public Web(ComponentContainer container) {
    super(container.$form());
    activity = container.$context();

    cookieHandler = (SdkLevel.getLevel() >= SdkLevel.LEVEL_GINGERBREAD)
        ? GingerbreadUtil.newCookieManager()
        : null;
  }

  /**
   * This constructor is for testing purposes only.
   */
  protected Web() {
    super(null);
    activity = null;
    cookieHandler = null;
  }

  /**
   * The URL for the web request.
   *
   * @return the URL
   */
  @SimpleProperty(category = PropertyCategory.BEHAVIOR,
      description = "The URL for the web request.")
  public String Url() {
    return urlString;
  }

  /**
   * Specifies the URL.
   */
  @DesignerProperty(editorType = PropertyTypeConstants.PROPERTY_TYPE_STRING,
      defaultValue = "")
  @SimpleProperty
  public void Url(String url) {
    urlString = url;
  }

  /**
   * The request headers, as a list of two-element sublists. The first element of each sublist
   * represents the request header field name. The second element of each sublist represents the
   * request header field values, either a single value or a list containing multiple values.
   *
   * @return the request headers.
   */
  @SimpleProperty(category = PropertyCategory.BEHAVIOR,
      description = "The request headers, as a list of two-element sublists. The first element " +
      "of each sublist represents the request header field name. The second element of each " +
      "sublist represents the request header field values, either a single value or a list " +
      "containing multiple values.")
  public YailList RequestHeaders() {
    return requestHeaders;
  }

  /**
   * Sets the request headers.
   *
   * @param list a list of two-element sublists, each representing a header name and values
   */
  @SimpleProperty
  public void RequestHeaders(YailList list) {
    // Call processRequestHeaders to validate the list parameter before setting the requestHeaders
    // field.
    try {
      processRequestHeaders(list);
      requestHeaders = list;
    } catch (InvalidRequestHeadersException e) {
      form.dispatchErrorOccurredEvent(this, "RequestHeaders", e.errorNumber, e.index);
    }
  }

  /**
   * Whether the cookies from a response should be saved and used in subsequent requests. Cookies
   * are only supported on Android version 2.3 or greater.
   *
   * @return whether cookies should be allowed
   */
  @SimpleProperty(category = PropertyCategory.BEHAVIOR,
      description = "Whether the cookies from a response should be saved and used in subsequent " +
      "requests. Cookies are only supported on Android version 2.3 or greater.")
  public boolean AllowCookies() {
    return allowCookies;
  }

  /**
   * Specifies whether cookies should be allowed
   */
  @DesignerProperty(editorType = PropertyTypeConstants.PROPERTY_TYPE_BOOLEAN,
      defaultValue = "false")
  @SimpleProperty
  public void AllowCookies(boolean allowCookies) {
    this.allowCookies = allowCookies;
    if (allowCookies && cookieHandler == null) {
      form.dispatchErrorOccurredEvent(this, "AllowCookies",
          ErrorMessages.ERROR_FUNCTIONALITY_NOT_SUPPORTED_WEB_COOKIES);
    }
  }

  /**
   * Returns whether the response should be saved in a file.
   */
  @SimpleProperty(category = PropertyCategory.BEHAVIOR,
      description = "Whether the response should be saved in a file.")
  public boolean SaveResponse() {
    return saveResponse;
  }

  /**
   * Specifies whether the response should be saved in a file.
   */
  @DesignerProperty(editorType = PropertyTypeConstants.PROPERTY_TYPE_BOOLEAN,
      defaultValue = "false")
  @SimpleProperty
  public void SaveResponse(boolean saveResponse) {
    this.saveResponse = saveResponse;
  }

  /**
   * The name of the file where the response should be saved. If SaveResponse is true and
   * ResponseFileName is empty, then a new file name will be generated.
   *
   * @return the name of the file where the response should be saved
   */
  @SimpleProperty(category = PropertyCategory.BEHAVIOR,
      description = "The name of the file where the response should be saved. If SaveResponse " +
      "is true and ResponseFileName is empty, then a new file name will be generated.")
  public String ResponseFileName() {
    return responseFileName;
  }

  /**
   * Specifies the name of the file where the response should be saved.
   * If SaveResponse is true and ResponseFileName is empty, then a new file
   * name will be generated.
   */
  @DesignerProperty(editorType = PropertyTypeConstants.PROPERTY_TYPE_STRING,
      defaultValue = "")
  @SimpleProperty
  public void ResponseFileName(String responseFileName) {
    this.responseFileName = responseFileName;
  }

  /**
   * Returns the number of milliseconds that each request will wait for a response before they time out.
   * If set to 0 (the default), then the request will wait for a response indefinitely.
   */
  @SimpleProperty(category = PropertyCategory.BEHAVIOR,
      description = "The number of milliseconds that a web request will wait for a response before giving up. " +
          "If set to 0, then there is no time limit on how long the request will wait.")
  public int Timeout() {
    return timeout;
  }

  /**
   * Returns the number of milliseconds that each request will wait for a response before they time out.
   * If set to 0, then the request will wait for a response indefinitely.
   */
  @DesignerProperty(editorType = PropertyTypeConstants.PROPERTY_TYPE_NON_NEGATIVE_INTEGER,
      defaultValue = "0")
  @SimpleProperty
  public void Timeout(int timeout) {
    if (timeout < 0){
      throw new IllegalArgumentError("Web Timeout must be a non-negative integer.");
    }
    this.timeout = timeout;
  }

  @SimpleFunction(description = "Clears all cookies for this Web component.")
  public void ClearCookies() {
    if (cookieHandler != null) {
      GingerbreadUtil.clearCookies(cookieHandler);
    } else {
      form.dispatchErrorOccurredEvent(this, "ClearCookies",
          ErrorMessages.ERROR_FUNCTIONALITY_NOT_SUPPORTED_WEB_COOKIES);
    }
  }

  /**
   * Performs an HTTP GET request using the Url property and retrieves the
   * response.
   *
   *   If the SaveResponse property is true, the response will be saved in a file
   * and the GotFile event will be triggered. The ResponseFileName property
   * can be used to specify the name of the file.
   *
   *   If the SaveResponse property is false, the GotText event will be
   * triggered.
   */
  @SimpleFunction
  public void Get() {
    final String METHOD = "Get";
    // Capture property values in local variables before running asynchronously.
    final CapturedProperties webProps = capturePropertyValues(METHOD);
    if (webProps == null) {
      // capturePropertyValues has already called form.dispatchErrorOccurredEvent
      return;
    }

    AsynchUtil.runAsynchronously(new Runnable() {
      @Override
      public void run() {
        performRequest(webProps, null, null, "GET", METHOD);
      }
    });
  }

  /**
   * Performs an HTTP POST request using the Url property and the specified text.
   *
   *   The characters of the text are encoded using UTF-8 encoding.
   *
   *   If the SaveResponse property is true, the response will be saved in a
   * file and the GotFile event will be triggered. The responseFileName property
   * can be used to specify the name of the file.
   *
   *   If the SaveResponse property is false, the GotText event will be triggered.
   *
   * @param text the text data for the POST request
   */
  @SimpleFunction(description = "Performs an HTTP POST request using the Url property and " +
      "the specified text.<br>" +
      "The characters of the text are encoded using UTF-8 encoding.<br>" +
      "If the SaveResponse property is true, the response will be saved in a file and the " +
      "GotFile event will be triggered. The responseFileName property can be used to specify " +
      "the name of the file.<br>" +
      "If the SaveResponse property is false, the GotText event will be triggered.")
  public void PostText(final String text) {
    requestTextImpl(text, "UTF-8", "PostText", "POST");
  }

  /**
   * Performs an HTTP POST request using the Url property and the specified text.
   *
   *   The characters of the text are encoded using the given encoding.
   *
   *   If the SaveResponse property is true, the response will be saved in a
   * file and the GotFile event will be triggered. The ResponseFileName property
   * can be used to specify the name of the file.
   *
   *   If the SaveResponse property is false, the GotText event will be triggered.
   *
   * @param text the text data for the POST request
   * @param encoding the character encoding to use when sending the text. If
   *                 encoding is empty or null, UTF-8 encoding will be used.
   */
  @SimpleFunction(description = "Performs an HTTP POST request using the Url property and " +
      "the specified text.<br>" +
      "The characters of the text are encoded using the given encoding.<br>" +
      "If the SaveResponse property is true, the response will be saved in a file and the " +
      "GotFile event will be triggered. The ResponseFileName property can be used to specify " +
      "the name of the file.<br>" +
      "If the SaveResponse property is false, the GotText event will be triggered.")
  public void PostTextWithEncoding(final String text, final String encoding) {
    requestTextImpl(text, encoding, "PostTextWithEncoding", "POST");
  }

  /**
   * Performs an HTTP POST request using the Url property and data from the specified file.
   *
   *   If the SaveResponse property is true, the response will be saved in a file
   * and the GotFile event will be triggered. The ResponseFileName property can be
   * used to specify the name of the file.
   *
   *   If the SaveResponse property is false, the GotText event will be triggered.
   *
   * @param path the path of the file for the POST request
   */
  @SimpleFunction(description = "Performs an HTTP POST request using the Url property and " +
      "data from the specified file.<br>" +
      "If the SaveResponse property is true, the response will be saved in a file and the " +
      "GotFile event will be triggered. The ResponseFileName property can be used to specify " +
      "the name of the file.<br>" +
      "If the SaveResponse property is false, the GotText event will be triggered.")
  public void PostFile(final String path) {
    final String METHOD = "PostFile";
    // Capture property values before running asynchronously.
    final CapturedProperties webProps = capturePropertyValues(METHOD);
    if (webProps == null) {
      // capturePropertyValues has already called form.dispatchErrorOccurredEvent
      return;
    }

    AsynchUtil.runAsynchronously(new Runnable() {
      @Override
      public void run() {
        performRequest(webProps, null, path, "POST", METHOD);
      }
    });
  }

  /**
   * Performs an HTTP PUT request using the Url property and the specified text.
   *
   *   The characters of the text are encoded using UTF-8 encoding.
   *
   *   If the SaveResponse property is true, the response will be saved in a
   * file and the GotFile event will be triggered. The responseFileName property
   * can be used to specify the name of the file.
   *
   *   If the SaveResponse property is false, the GotText event will be triggered.
   *
   * @param text the text data for the PUT request
   */
  @SimpleFunction(description = "Performs an HTTP PUT request using the Url property and " +
      "the specified text.<br>" +
      "The characters of the text are encoded using UTF-8 encoding.<br>" +
      "If the SaveResponse property is true, the response will be saved in a file and the " +
      "GotFile event will be triggered. The responseFileName property can be used to specify " +
      "the name of the file.<br>" +
      "If the SaveResponse property is false, the GotText event will be triggered.")
  public void PutText(final String text) {
    requestTextImpl(text, "UTF-8", "PutText", "PUT");
  }

  /**
   * Performs an HTTP PUT request using the Url property and the specified text.
   *
   *   The characters of the text are encoded using the given encoding.
   *
   *   If the SaveResponse property is true, the response will be saved in a
   * file and the GotFile event will be triggered. The ResponseFileName property
   * can be used to specify the name of the file.
   *
   *   If the SaveResponse property is false, the GotText event will be triggered.
   *
   * @param text the text data for the PUT request
   * @param encoding the character encoding to use when sending the text. If
   *                 encoding is empty or null, UTF-8 encoding will be used.
   */
  @SimpleFunction(description = "Performs an HTTP PUT request using the Url property and " +
      "the specified text.<br>" +
      "The characters of the text are encoded using the given encoding.<br>" +
      "If the SaveResponse property is true, the response will be saved in a file and the " +
      "GotFile event will be triggered. The ResponseFileName property can be used to specify " +
      "the name of the file.<br>" +
      "If the SaveResponse property is false, the GotText event will be triggered.")
  public void PutTextWithEncoding(final String text, final String encoding) {
    requestTextImpl(text, encoding, "PutTextWithEncoding", "PUT");
  }

  /**
   * Performs an HTTP PUT request using the Url property and data from the specified file.
   *
   *   If the SaveResponse property is true, the response will be saved in a file
   * and the GotFile event will be triggered. The ResponseFileName property can be
   * used to specify the name of the file.
   *
   *   If the SaveResponse property is false, the GotText event will be triggered.
   *
   * @param path the path of the file for the PUT request
   */
  @SimpleFunction(description = "Performs an HTTP PUT request using the Url property and " +
      "data from the specified file.<br>" +
      "If the SaveResponse property is true, the response will be saved in a file and the " +
      "GotFile event will be triggered. The ResponseFileName property can be used to specify " +
      "the name of the file.<br>" +
      "If the SaveResponse property is false, the GotText event will be triggered.")
  public void PutFile(final String path) {
    final String METHOD = "PutFile";
    // Capture property values before running asynchronously.
    final CapturedProperties webProps = capturePropertyValues(METHOD);
    if (webProps == null) {
      // capturePropertyValues has already called form.dispatchErrorOccurredEvent
      return;
    }

    AsynchUtil.runAsynchronously(new Runnable() {
      @Override
      public void run() {
        performRequest(webProps, null, path, "PUT", METHOD);
      }
    });
  }

  /**
   * Performs an HTTP DELETE request using the Url property and retrieves the
   * response.
   *
   *   If the SaveResponse property is true, the response will be saved in a file
   * and the GotFile event will be triggered. The ResponseFileName property
   * can be used to specify the name of the file.
   *
   *   If the SaveResponse property is false, the GotText event will be
   * triggered.
   */
  @SimpleFunction
  public void Delete() {
    final String METHOD = "Delete";
    // Capture property values in local variables before running asynchronously.
    final CapturedProperties webProps = capturePropertyValues(METHOD);
    if (webProps == null) {
      // capturePropertyValues has already called form.dispatchErrorOccurredEvent
      return;
    }

    AsynchUtil.runAsynchronously(new Runnable() {
      @Override
      public void run() {
        performRequest(webProps, null, null, "DELETE", METHOD);
      }
    });
  }

  /*
   * Performs an HTTP GET, POST, PUT or DELETE request using the Url property and the specified
   * text, and retrieves the response asynchronously.<br>
   * The characters of the text are encoded using the given encoding.<br>
   * If the SaveResponse property is true, the response will be saved in a file
   * and the GotFile event will be triggered. The ResponseFileName property
   * can be used to specify the name of the file.<br>
   * If the SaveResponse property is false, the GotText event will be
   * triggered.
   *
   * @param text the text data for the POST or PUT request
   * @param encoding the character encoding to use when sending the text. If
   *                 encoding is empty or null, UTF-8 encoding will be used.
   * @param functionName the name of the function, used when dispatching errors
   * @param httpVerb the HTTP operation to be performed: GET, POST, PUT or DELETE
   */
  private void requestTextImpl(final String text, final String encoding,
      final String functionName, final String httpVerb) {
    // Capture property values before running asynchronously.
    final CapturedProperties webProps = capturePropertyValues(functionName);
    if (webProps == null) {
      // capturePropertyValues has already called form.dispatchErrorOccurredEvent
      return;
    }

    AsynchUtil.runAsynchronously(new Runnable() {
      @Override
      public void run() {
        // Convert text to bytes using the encoding.
        byte[] requestData;
        try {
          if (encoding == null || encoding.length() == 0) {
            requestData = text.getBytes("UTF-8");
          } else {
            requestData = text.getBytes(encoding);
          }
        } catch (UnsupportedEncodingException e) {
          form.dispatchErrorOccurredEvent(Web.this, functionName,
              ErrorMessages.ERROR_WEB_UNSUPPORTED_ENCODING, encoding);
          return;
        }

        performRequest(webProps, requestData, null, httpVerb, functionName);
      }
    });
  }

  /**
   * Event indicating that a request has finished.
   *
   * @param url the URL used for the request
   * @param responseCode the response code from the server
   * @param responseType the mime type of the response
   * @param responseContent the response content from the server
   */
  @SimpleEvent
  public void GotText(String url, int responseCode, String responseType, String responseContent) {
    // invoke the application's "GotText" event handler.
    EventDispatcher.dispatchEvent(this, "GotText", url, responseCode, responseType,
        responseContent);
  }

  /**
   * Event indicating that a request has finished.
   *
   * @param url the URL used for the request
   * @param responseCode the response code from the server
   * @param responseType the mime type of the response
   * @param fileName the full path name of the saved file
   */
  @SimpleEvent
  public void GotFile(String url, int responseCode, String responseType, String fileName) {
    // invoke the application's "GotFile" event handler.
    EventDispatcher.dispatchEvent(this, "GotFile", url, responseCode, responseType, fileName);
  }

  /**
   * Event indicating that a request has timed out.
   *
   * @param url the URL used for the request
   */
  @SimpleEvent
  public void TimedOut(String url) {
    // invoke the application's "TimedOut" event handler.
    EventDispatcher.dispatchEvent(this, "TimedOut", url);
  }

  /**
   * Converts a list of two-element sublists, representing name and value pairs, to a
   * string formatted as application/x-www-form-urlencoded media type, suitable to pass to
   * PostText.
   *
   * @param list a list of two-element sublists representing name and value pairs
   */
  @SimpleFunction
  public String BuildRequestData(YailList list) {
    try {
      return buildRequestData(list);
    } catch (BuildRequestDataException e) {
      form.dispatchErrorOccurredEvent(this, "BuildRequestData", e.errorNumber, e.index);
      return "";
    }
  }

  /*
   * Converts a list of two-element sublists, representing name and value pairs, to a
   * string formatted as application/x-www-form-urlencoded media type, suitable to pass to
   * PostText.
   *
   * @param list a list of two-element sublists representing name and value pairs
   * @throws BuildPostDataException if the list is not valid
   */
  // VisibleForTesting
  String buildRequestData(YailList list) throws BuildRequestDataException {
    StringBuilder sb = new StringBuilder();
    String delimiter = "";
    for (int i = 0; i < list.size(); i++) {
      Object item = list.getObject(i);
      // Each item must be a two-element sublist.
      if (item instanceof YailList) {
        YailList sublist = (YailList) item;
        if (sublist.size() == 2) {
          // The first element is the name.
          String name = sublist.getObject(0).toString();
          // The second element is the value.
          String value = sublist.getObject(1).toString();
          sb.append(delimiter).append(UriEncode(name)).append('=').append(UriEncode(value));
        } else {
          throw new BuildRequestDataException(
              ErrorMessages.ERROR_WEB_BUILD_REQUEST_DATA_NOT_TWO_ELEMENTS, i + 1);
        }
      } else {
        throw new BuildRequestDataException(ErrorMessages.ERROR_WEB_BUILD_REQUEST_DATA_NOT_LIST, i + 1);
      }
      delimiter = "&";
    }
    return sb.toString();
  }

  /**
   * Encodes the given text value so that it can be used in a URL.
   *
   * @param text the text to encode
   * @return the encoded text
   */
  @SimpleFunction
  public String UriEncode(String text) {
    try {
      return URLEncoder.encode(text, "UTF-8");
    } catch (UnsupportedEncodingException e) {
      // If UTF-8 is not supported, we're in big trouble!
      // According to Javadoc and Android documentation for java.nio.charset.Charset, UTF-8 is
      // available on every Java implementation.
      Log.e(LOG_TAG, "UTF-8 is unsupported?", e);
      return "";
    }
  }

    
  /**
   * Decodes the encoded text value so that the values aren't URL encoded anymore.
   *
   * @param text the text to encode
   * @return the decoded text
   */
  @SimpleFunction
  public String UriDecode(String text) {
    try {
      return URLDecoder.decode(text, "UTF-8");
    } catch (UnsupportedEncodingException e) {
      // If UTF-8 is not supported, we're in big trouble!
      // According to Javadoc and Android documentation for java.nio.charset.Charset, UTF-8 is
      // available on every Java implementation.
      Log.e(LOG_TAG, "UTF-8 is unsupported?", e);
      return "";
    }
  }

  /**
   * Decodes the given JSON encoded value to produce a corresponding AppInventor value.
   * A JSON list `[x, y, z]` decodes to a list `(x y z)`,  A JSON object with key A and value B,
   * (denoted as `{A:B}`) decodes to a list `((A B))`, that is, a list containing the two-element
   * list `(A B)`.
   *
   *   Use the method [JsonTextDecodeWithDictionaries](#Web.JsonTextDecodeWithDictionaries) if you
   * would prefer to get back dictionary objects rather than lists-of-lists in the result.
   *
   * @param jsonText the JSON text to decode
   * @return the decoded text
   */
  @SimpleFunction
  // This returns an object, which in general will be a Java ArrayList, String, Boolean, Integer,
  // or Double.
  // The object will be sanitized to produce the corresponding Yail data by call-component-method.
  // That mechanism would need to be extended if we ever change JSON decoding to produce
  // dictionaries rather than lists
  // TOOD(hal): Provide an alternative way to decode JSON objects to dictionaries.  Maybe with 
  // renaming this JsonTextDecodeWithPairs and making JsonTextDecode the one to use
  // dictionaries
  public Object JsonTextDecode(String jsonText) {
    try {
      return decodeJsonText(jsonText, false);
    } catch (IllegalArgumentException e) {
      form.dispatchErrorOccurredEvent(this, "JsonTextDecode",
          ErrorMessages.ERROR_WEB_JSON_TEXT_DECODE_FAILED, jsonText);
      return "";
    }
  }

  /**
   * Decodes the given JSON encoded value to produce a corresponding App Inventor value.
   * A JSON list [x, y, z] decodes to a list (x y z). A JSON Object with name A and value B,
   * denoted as \{a: b\} decodes to a dictionary with the key a and value b.
   *
   * @param jsonText The JSON text to decode.
   * @return The decoded value.
   */
  @SimpleFunction
  public Object JsonTextDecodeWithDictionaries(String jsonText) {
    try {
      return decodeJsonText(jsonText, true);
    } catch (IllegalArgumentException e) {
      form.dispatchErrorOccurredEvent(this, "JsonTextDecodeWithDictionaries",
          ErrorMessages.ERROR_WEB_JSON_TEXT_DECODE_FAILED, jsonText);
      return "";
    }
  }

  /**
   * Decodes the given JSON encoded value.
   *
   * @param jsonText the JSON text to decode
   * @param useDicts <code>true</code> to repesent JSON objects using YailDictionaries or false to
   *                 represent JSON objects using associative lists
   * @return the decoded object
   * @throws IllegalArgumentException if the JSON text can't be decoded
   */
  @VisibleForTesting
  static Object decodeJsonText(String jsonText, boolean useDicts) throws IllegalArgumentException {
    try {
      return JsonUtil.getObjectFromJson(jsonText, useDicts);
    } catch (JSONException e) {
      throw new IllegalArgumentException("jsonText is not a legal JSON value");
    }
  }

  /**
   * Decodes the given JSON encoded value.
   *
   * @param jsonText the JSON text to decode
   * @return the decoded object
   * @throws IllegalArgumentException if the JSON text can't be decoded
   * @deprecated As of nb182. Use {@link #decodeJsonText(String, boolean)} instead.
   */
  @Deprecated
  @VisibleForTesting
  static Object decodeJsonText(String jsonText) throws IllegalArgumentException {
    return decodeJsonText(jsonText, false);
  }

  /**
   * Returns the value of a built-in type (i.e., boolean, number, text, list, dictionary)
   * in its JavaScript Object Notation representation. If the value cannot be
   * represented as JSON, the Screen's ErrorOccurred event will be run, if any,
   * and the Web component will return the empty string.
   *
   * @param jsonObject the object to turn into JSON
   * @return the stringified JSON value
   */
  @SimpleFunction
  public String JsonObjectEncode(Object jsonObject) {
    try {
      return JsonUtil.encodeJsonObject(jsonObject);
    } catch (IllegalArgumentException e) {
      form.dispatchErrorOccurredEvent(this, "JsonObjectEncode",
          ErrorMessages.ERROR_WEB_JSON_TEXT_ENCODE_FAILED, jsonObject);
      return "";
    }
  }

  /**
   * Decodes the given XML string to produce a dictionary structure. The dictionary includes the
   * special keys `$tag`, `$localName`, `$namespace`, `$namespaceUri`, `$attributes`, and `$content`,
   * as well as a key for each unique tag for every node, which points to a list of elements of
   * the same structure as described here.
   *
   *   The `$tag` key is the full tag name, e.g., foo:bar. The `$localName` is the local portion of
   * the name (everything after the colon `:` character). If a namespace is given (everything before
   * the colon `:` character), it is provided in `$namespace` and the corresponding URI is given
   * in `$namespaceUri`. The attributes are stored in a dictionary in `$attributes` and the
   * child nodes are given as a list under `$content`.
   *
   *   **More Information on Special Keys**
   *
   *   Consider the following XML document:
   *
   *   ```xml
   *     <ex:Book xmlns:ex="http://example.com/">
   *       <ex:title xml:lang="en">On the Origin of Species</ex:title>
   *       <ex:author>Charles Darwin</ex:author>
   *     </ex:Book>
   *   ```
   *
   *   When parsed, the `$tag` key will be `"ex:Book"`, the `$localName` key will be `"Book"`, the
   * `$namespace` key will be `"ex"`, `$namespaceUri` will be `"http://example.com/"`, the
   * `$attributes` key will be a dictionary `{}` (xmlns is removed for the namespace), and the
   * `$content` will be a list of two items representing the decoded `<ex:title>` and `<ex:author>`
   * elements. The first item, which corresponds to the `<ex:title>` element, will have an
   * `$attributes` key containing the dictionary `{"xml:lang": "en"}`. For each `name=value`
   * attribute on an element, a key-value pair mapping `name` to `value` will exist in the
   * `$attributes` dictionary. In addition to these special keys, there will also be `"ex:title"`
   * and `"ex:author"` to allow lookups faster than having to traverse the `$content` list.
   *
   * @param XmlText the JSON text to decode
   * @return the decoded text
   */
  @SimpleFunction(description = "Decodes the given XML into a set of nested dictionaries that " +
      "capture the structure and data contained in the XML. See the help for more details.")  public Object XMLTextDecodeAsDictionary(String XmlText) {
    try {
      XmlParser p = new XmlParser();
      SAXParser parser = SAXParserFactory.newInstance().newSAXParser();
      InputSource is = new InputSource(new StringReader(XmlText));
      is.setEncoding("UTF-8");
      parser.parse(is, p);
      return p.getRoot();
    } catch (Exception e) {
      Log.e(LOG_TAG, e.getMessage());
      form.dispatchErrorOccurredEvent(this, "XMLTextDecodeAsDictionary",
          ErrorMessages.ERROR_WEB_JSON_TEXT_DECODE_FAILED, e.getMessage());
      return new YailDictionary();
    }
  }

  /**
   * Decodes the given XML string to produce a list structure. `<tag>string</tag>` decodes to
   * a list that contains a pair of tag and string.  More generally, if obj1, obj2, ...
   * are tag-delimited XML strings, then `<tag>obj1 obj2 ...</tag>` decodes to a list
   * that contains a pair whose first element is tag and whose second element is the
   * list of the decoded obj's, ordered alphabetically by tags.
   *
   *   Examples:
   *   * `<foo><123/foo>` decodes to a one-item list containing the pair `(foo 123)`
   *   * `<foo>1 2 3</foo>` decodes to a one-item list containing the pair `(foo "1 2 3")`
   *   * `<a><foo>1 2 3</foo><bar>456</bar></a>` decodes to a list containing the pair `(a X)`
   *     where X is a 2-item list that contains the pair `(bar 123)` and the pair `(foo "1 2 3")`.
   *
   *   If the sequence of obj's mixes tag-delimited and non-tag-delimited items, then the
   * non-tag-delimited items are pulled out of the sequence and wrapped with a "content" tag.
   * For example, decoding `<a><bar>456</bar>many<foo>1 2 3</foo>apples<a></code>`
   * is similar to above, except that the list X is a 3-item list that contains the additional pair
   * whose first item is the string "content", and whose second item is the list (many, apples).
   * This method signals an error and returns the empty list if the result is not well-formed XML.
   *
   * @param XmlText the XML text to decode
   * @return the decoded text
   */
  // This method works by by first converting the XML to JSON and then decoding the JSON.
  @SimpleFunction(description = "Decodes the given XML string to produce a dictionary structure. " +
      "See the App Inventor documentation on \"Other topics, notes, and details\" for information.")
  // The above description string is punted because I can't figure out how to write the
  // documentation string in a way that will look work both as a tooltip and in the autogenerated
  // HTML for the component documentation on the Web.  It's too long for a tooltip, anyway.
  public Object XMLTextDecode(String XmlText) {
    try {
      return JsonTextDecode(XML.toJSONObject(XmlText).toString());
    } catch (com.google.appinventor.components.runtime.repackaged.org.json.JSONException e) {
      // We could be more precise and signal different errors for the conversion to JSON
      // versus the decoding of that JSON, but showing the actual error message should
      // be good enough.
      Log.e(LOG_TAG, e.getMessage());
      form.dispatchErrorOccurredEvent(this, "XMLTextDecode",
          ErrorMessages.ERROR_WEB_JSON_TEXT_DECODE_FAILED, e.getMessage());
      // This XMLTextDecode should always return a list, even in the case of an error
      return YailList.makeEmptyList();
    }
  }

  /**
   * Decodes the given HTML text value.
   *
   *   HTML Character Entities such as `&amp;`, `&lt;`, `&gt;`, `&apos;`, and `&quot;` are
   * changed to `&`, `<`, `>`, `'`, and `"`.
   * Entities such as `&#xhhhh;`, and `&#nnnn;` are changed to the appropriate characters.
   *
   * @param htmlText the HTML text to decode
   * @return the decoded text
   */
  @SimpleFunction(description = "Decodes the given HTML text value. HTML character entities " +
      "such as &amp;amp;, &amp;lt;, &amp;gt;, &amp;apos;, and &amp;quot; are changed to " +
      "&amp;, &lt;, &gt;, &#39;, and &quot;. Entities such as &amp;#xhhhh, and &amp;#nnnn " +
      "are changed to the appropriate characters.")
  public String HtmlTextDecode(String htmlText) {
    try {
      return HtmlEntities.decodeHtmlText(htmlText);
    } catch (IllegalArgumentException e) {
      form.dispatchErrorOccurredEvent(this, "HtmlTextDecode",
          ErrorMessages.ERROR_WEB_HTML_TEXT_DECODE_FAILED, htmlText);
      return "";
    }
  }

  /*
   * Perform a HTTP GET or POST request.
   * This method is always run on a different thread than the event thread. It does not use any
   * property value fields because the properties may be changed while it is running. Instead, it
   * uses the parameters.
   * If either postData or postFile is non-null, then a post request is performed.
   * If both postData and postFile are non-null, postData takes precedence over postFile.
   * If postData and postFile are both null, then a get request is performed.
   * If saveResponse is true, the response will be saved in a file and the GotFile event will be
   * triggered. responseFileName specifies the name of the  file.
   * If saveResponse is false, the GotText event will be triggered.
   *
   * This method can throw an IOException. The caller is responsible for catching it and
   * triggering the appropriate error event.
   *
   * @param webProps the captured property values needed for the request
   * @param postData the data for the post request if it is not coming from a file, can be null
   * @param postFile the path of the file containing data for the post request if it is coming from
   *                 a file, can be null
   *
   * @throws IOException
   */
  private void performRequest(final CapturedProperties webProps, final byte[] postData,
    final String postFile, final String httpVerb, final String method) {

    // Make sure we have permissions we may need
    if (saveResponse & !havePermission) {
      final Web me = this;
      form.askPermission(new BulkPermissionRequest(this, "Web",
          Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE) {
          @Override
          public void onGranted() {
            me.havePermission = true;
            // onGranted is running on the UI thread, and we are about to do network i/o, so
            // we have to run this asynchronously to get off the UI thread!
            AsynchUtil.runAsynchronously(new Runnable() {
                @Override
                public void run() {
                  me.performRequest(webProps, postData, postFile, httpVerb, method);
                }
              });
          }
        });
      return;
    }

    try {
      // Open the connection.
      HttpURLConnection connection = openConnection(webProps, httpVerb);
      if (connection != null) {
        try {
          if (postData != null) {
            writeRequestData(connection, postData);
          } else if (postFile != null) {
            writeRequestFile(connection, postFile);
          }

          // Get the response.
          final int responseCode = connection.getResponseCode();
          final String responseType = getResponseType(connection);
          processResponseCookies(connection);

          if (saveResponse) {
            final String path = saveResponseContent(connection, webProps.responseFileName,
              responseType);

            // Dispatch the event.
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                  GotFile(webProps.urlString, responseCode, responseType, path);
                }
              });
          } else {
            final String responseContent = getResponseContent(connection);

            // Dispatch the event.
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                  GotText(webProps.urlString, responseCode, responseType, responseContent);
                }
              });
          }

        } catch (SocketTimeoutException e) {
          // Dispatch timeout event.
          activity.runOnUiThread(new Runnable() {
              @Override
              public void run() {
                TimedOut(webProps.urlString);
              }
            });
          throw new RequestTimeoutException();
        } finally {
          connection.disconnect();
        }
      }
    } catch (PermissionException e) {
      form.dispatchPermissionDeniedEvent(Web.this, method, e);
    } catch (FileUtil.FileException e) {
      form.dispatchErrorOccurredEvent(Web.this, method,
          e.getErrorMessageNumber());
    } catch (RequestTimeoutException e) {
      form.dispatchErrorOccurredEvent(Web.this, method,
          ErrorMessages.ERROR_WEB_REQUEST_TIMED_OUT, webProps.urlString);
    } catch (Exception e) {
      int message;
      String[] args;
      //noinspection IfCanBeSwitch
      if (method.equals("Get")) {
        message = ErrorMessages.ERROR_WEB_UNABLE_TO_GET;
        args = new String[] { webProps.urlString };
      } else if (method.equals("Delete")) {
        message = ErrorMessages.ERROR_WEB_UNABLE_TO_DELETE;
        args = new String[] { webProps.urlString };
      } else if (method.equals("PostFile") || method.equals("PutFile")) {
        message = ErrorMessages.ERROR_WEB_UNABLE_TO_POST_OR_PUT_FILE;
        args = new String[] { postFile, webProps.urlString };
      } else {
        message = ErrorMessages.ERROR_WEB_UNABLE_TO_POST_OR_PUT;
        String content = "";
        try {
          if (postData != null) {
            //noinspection CharsetObjectCanBeUsed
            content = new String(postData, "UTF-8");
          }
        } catch (UnsupportedEncodingException e1) {
          Log.e(LOG_TAG, "UTF-8 is the default charset for Android but not available???");
        }
        args = new String[] { content, webProps.urlString };
      }
      form.dispatchErrorOccurredEvent(Web.this, method,
          message, (Object[]) args);
    }
  }

  /**
   * Open a connection to the resource and set the HTTP action to PUT or DELETE if it is one of
   * them. GET would be the default, and POST is set in writeRequestData or writeRequestFile
   * @param webProps the properties of the connection, set as properties in the component
   * @param httpVerb One of GET/POST/PUT/DELETE
   * @return a HttpURL Connection
   * @throws IOException
   * @throws ClassCastException
   * @throws ProtocolException thrown if the method in setRequestMethod is not correct
   */
  private static HttpURLConnection openConnection(CapturedProperties webProps, String httpVerb)
      throws IOException, ClassCastException, ProtocolException {

    HttpURLConnection connection = (HttpURLConnection) webProps.url.openConnection();
    connection.setConnectTimeout(webProps.timeout);
    connection.setReadTimeout(webProps.timeout);

    if (httpVerb.equals("PUT") || httpVerb.equals("DELETE")){
      // Set the Request Method; GET is the default, and if it is a POST, it will be marked as such
      // with setDoOutput in writeRequestFile or writeRequestData
      connection.setRequestMethod(httpVerb);
    }

    // Request Headers
    for (Map.Entry<String, List<String>> header : webProps.requestHeaders.entrySet()) {
      String name = header.getKey();
      for (String value : header.getValue()) {
        connection.addRequestProperty(name, value);
      }
    }

    // Cookies
    if (webProps.cookies != null) {
      for (Map.Entry<String, List<String>> cookie : webProps.cookies.entrySet()) {
        String name = cookie.getKey();
        for (String value : cookie.getValue()) {
          connection.addRequestProperty(name, value);
        }
      }
    }

    return connection;
  }

  private static void writeRequestData(HttpURLConnection connection, byte[] postData)
      throws IOException {
    // According to the documentation at
    // http://developer.android.com/reference/java/net/HttpURLConnection.html
    // HttpURLConnection uses the GET method by default. It will use POST if setDoOutput(true) has
    // been called.
    connection.setDoOutput(true); // This makes it something other than a HTTP GET.
    // Write the data.
    connection.setFixedLengthStreamingMode(postData.length);
    BufferedOutputStream out = new BufferedOutputStream(connection.getOutputStream());
    try {
      out.write(postData, 0, postData.length);
      out.flush();
    } finally {
      out.close();
    }
  }

  private void writeRequestFile(HttpURLConnection connection, String path)
      throws IOException {
    // Use MediaUtil.openMedia to open the file. This means that path could be file on the SD card,
    // an asset, a contact picture, etc.
    BufferedInputStream in = new BufferedInputStream(MediaUtil.openMedia(form, path));
    try {
      // Write the file's data.
      // According to the documentation at
      // http://developer.android.com/reference/java/net/HttpURLConnection.html
      // HttpURLConnection uses the GET method by default. It will use POST if setDoOutput(true) has
      // been called.
      connection.setDoOutput(true); // This makes it something other than a HTTP GET.
      connection.setChunkedStreamingMode(0);
      BufferedOutputStream out = new BufferedOutputStream(connection.getOutputStream());
      try {
        while (true) {
          int b = in.read();
          if (b == -1) {
            break;
          }
          out.write(b);
        }
        out.flush();
      } finally {
        out.close();
      }
    } finally {
      in.close();
    }
  }

  private static String getResponseType(HttpURLConnection connection) {
    String responseType = connection.getContentType();
    return (responseType != null) ? responseType : "";
  }

  private void processResponseCookies(HttpURLConnection connection) {
    if (allowCookies && cookieHandler != null) {
      try {
        Map<String, List<String>> headerFields = connection.getHeaderFields();
        cookieHandler.put(connection.getURL().toURI(), headerFields);
      } catch (URISyntaxException e) {
        // Can't convert the URL to a URI; no cookies for you.
      } catch (IOException e) {
        // Sorry, no cookies for you.
      }
    }
  }

  private static String getResponseContent(HttpURLConnection connection) throws IOException {
    // Use the content encoding to convert bytes to characters.
    String encoding = connection.getContentEncoding();
    if (encoding == null) {
      encoding = "UTF-8";
    }
    InputStreamReader reader = new InputStreamReader(getConnectionStream(connection), encoding);
    try {
      int contentLength = connection.getContentLength();
      StringBuilder sb = (contentLength != -1)
          ? new StringBuilder(contentLength)
          : new StringBuilder();
      char[] buf = new char[1024];
      int read;
      while ((read = reader.read(buf)) != -1) {
        sb.append(buf, 0, read);
      }
      return sb.toString();
    } finally {
      reader.close();
    }
  }

  private String saveResponseContent(HttpURLConnection connection,
      String responseFileName, String responseType) throws IOException {
    File file = createFile(responseFileName, responseType);

    BufferedInputStream in = new BufferedInputStream(getConnectionStream(connection), 0x1000);
    try {
      BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(file), 0x1000);
      try {
        // Copy the contents from the input stream to the output stream.
        while (true) {
          int b = in.read();
          if (b == -1) {
            break;
          }
          out.write(b);
        }
        out.flush();
      } finally {
        out.close();
      }
    } finally {
      in.close();
    }

    return file.getAbsolutePath();
  }

  private static InputStream getConnectionStream(HttpURLConnection connection) throws SocketTimeoutException {
    // According to the Android reference documentation for HttpURLConnection: If the HTTP response
    // indicates that an error occurred, getInputStream() will throw an IOException. Use
    // getErrorStream() to read the error response.
    try {
      return connection.getInputStream();
    } catch (SocketTimeoutException e) {
      throw e; //Rethrow exception - should not attempt to read stream for timeouts
    } catch (IOException e1) {
      // Use the error response for all other IO Exceptions.
      return connection.getErrorStream();
    }
  }

  private File createFile(String fileName, String responseType)
      throws IOException, FileUtil.FileException {
    // If a fileName was specified, use it.
    if (!TextUtils.isEmpty(fileName)) {
      return FileUtil.getExternalFile(form, fileName);
    }

    // Otherwise, try to determine an appropriate file extension from the responseType.
    // The response type could contain extra information that we don't need. For example, it might
    // be "text/html; charset=ISO-8859-1". We just want to look at the part before the semicolon.
    int indexOfSemicolon = responseType.indexOf(';');
    if (indexOfSemicolon != -1) {
      responseType = responseType.substring(0, indexOfSemicolon);
    }
    String extension = mimeTypeToExtension.get(responseType);
    if (extension == null) {
      extension = "tmp";
    }
    return FileUtil.getDownloadFile(form, extension);
  }

  /*
   * Converts request headers (a YailList) into the structure that can be used with the Java API
   * (a Map<String, List<String>>). If the request headers contains an invalid element, an
   * InvalidRequestHeadersException will be thrown.
   */
  private static Map<String, List<String>> processRequestHeaders(YailList list)
      throws InvalidRequestHeadersException {
    Map<String, List<String>> requestHeadersMap = Maps.newHashMap();
    for (int i = 0; i < list.size(); i++) {
      Object item = list.getObject(i);
      // Each item must be a two-element sublist.
      if (item instanceof YailList) {
        YailList sublist = (YailList) item;
        if (sublist.size() == 2) {
          // The first element is the request header field name.
          String fieldName = sublist.getObject(0).toString();
          // The second element contains the request header field values.
          Object fieldValues = sublist.getObject(1);

          // Build an entry (key and values) for the requestHeadersMap.
          String key = fieldName;
          List<String> values = Lists.newArrayList();

          // If there is just one field value, it is specified as a single non-list item (for
          // example, it can be a text value). If there are multiple field values, they are
          // specified as a list.
          if (fieldValues instanceof YailList) {
            // It's a list. There are multiple field values.
            YailList multipleFieldsValues = (YailList) fieldValues;
            for (int j = 0; j < multipleFieldsValues.size(); j++) {
              Object value = multipleFieldsValues.getObject(j);
              values.add(value.toString());
            }
          } else {
            // It's a single non-list item. There is just one field value.
            Object singleFieldValue = fieldValues;
            values.add(singleFieldValue.toString());
          }
          // Put the entry into the requestHeadersMap.
          requestHeadersMap.put(key, values);
        } else {
          // The sublist doesn't contain two elements.
          throw new InvalidRequestHeadersException(
              ErrorMessages.ERROR_WEB_REQUEST_HEADER_NOT_TWO_ELEMENTS, i + 1);
        }
      } else {
        // The item isn't a sublist.
        throw new InvalidRequestHeadersException(
            ErrorMessages.ERROR_WEB_REQUEST_HEADER_NOT_LIST, i + 1);
      }
    }
    return requestHeadersMap;
  }

  /*
   * Captures the current property values that are needed for an HTTP request. If an error occurs
   * while validating the Url or RequestHeaders property values, this method calls
   * form.dispatchErrorOccurredEvent and returns null.
   *
   * @param functionName the name of the function, used when dispatching errors
   */
  private CapturedProperties capturePropertyValues(String functionName) {
    try {
      return new CapturedProperties(this);
    } catch (MalformedURLException e) {
      form.dispatchErrorOccurredEvent(this, functionName,
          ErrorMessages.ERROR_WEB_MALFORMED_URL, urlString);
    } catch (InvalidRequestHeadersException e) {
      form.dispatchErrorOccurredEvent(this, functionName, e.errorNumber, e.index);
    }
    return null;
  }
}
