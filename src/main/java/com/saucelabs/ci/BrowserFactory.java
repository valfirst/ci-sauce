package com.saucelabs.ci;

import com.saucelabs.saucerest.DataCenter;
import com.saucelabs.saucerest.SauceREST;
import com.saucelabs.saucerest.api.PlatformEndpoint;
import com.saucelabs.saucerest.model.platform.Platform;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.Timestamp;
import java.util.*;

/**
 * Handles invoking the Sauce REST API to retrieve the list of valid Browsers. The list of browser
 * is cached for an hour.
 *
 * @author Ross Rowe
 */
public class BrowserFactory {

  private static final Logger LOGGER = LoggerFactory.getLogger(BrowserFactory.class);

  public static final int ONE_HOUR_IN_MILLIS = 1000 * 60 * 60;

  private SauceREST sauceREST;

  private Map<String, Browser> seleniumLookup = new HashMap<>();
  private Map<String, Browser> appiumLookup = new HashMap<>();
  private Map<String, Browser> webDriverLookup = new HashMap<>();
  protected Timestamp lastLookup = null;
  private static final String IEHTA = "iehta";
  private static final String CHROME = "chrome";
  private static BrowserFactory instance;

  public BrowserFactory() {
    this(null);
  }

  public BrowserFactory(SauceREST sauceREST) {
    if (sauceREST == null) {
      this.sauceREST = new SauceREST(null, null, DataCenter.US_WEST);
    } else {
      this.sauceREST = sauceREST;
    }
    try {
      initializeWebDriverBrowsers();
      initializeAppiumBrowsers();
    } catch (JSONException | IOException e) {
      LOGGER.warn("Error retrieving browsers, attempting to continue", e);
    }
  }

  public List<Browser> getAppiumBrowsers() throws JSONException, IOException {
    List<Browser> browsers;
    if (shouldRetrieveBrowsers()) {
      browsers = initializeAppiumBrowsers();
    } else {
      browsers = new ArrayList<>(appiumLookup.values());
    }
    Collections.sort(browsers);

    return browsers;
  }

  public List<Browser> getWebDriverBrowsers() throws JSONException, IOException {
    List<Browser> browsers;
    if (shouldRetrieveBrowsers()) {
      browsers = initializeWebDriverBrowsers();
    } else {
      browsers = new ArrayList<>(webDriverLookup.values());
    }
    Collections.sort(browsers);

    return browsers;
  }

  public boolean shouldRetrieveBrowsers() {
    return lastLookup == null
        || CacheTimeUtil.pastAcceptableDuration(lastLookup, ONE_HOUR_IN_MILLIS);
  }

  private List<Browser> initializeAppiumBrowsers() throws JSONException, IOException {
    List<Browser> browsers = getAppiumBrowsersFromSauceLabs();
    appiumLookup = new HashMap<>();
    for (Browser browser : browsers) {
      appiumLookup.put(browser.getKey(), browser);
    }
    lastLookup = new Timestamp(new Date().getTime());
    return browsers;
  }

  private List<Browser> initializeWebDriverBrowsers() throws JSONException, IOException {
    List<Browser> browsers = getWebDriverBrowsersFromSauceLabs();
    webDriverLookup = new HashMap<>();
    for (Browser browser : browsers) {
      webDriverLookup.put(browser.getKey(), browser);
    }
    lastLookup = new Timestamp(new Date().getTime());
    return browsers;
  }

  private List<Browser> getWebDriverBrowsersFromSauceLabs() throws IOException {
    PlatformEndpoint pe = sauceREST.getPlatformEndpoint();
    List<Platform> platforms = pe.getSupportedPlatforms("webdriver").getPlatforms();
    return getBrowserListFromPlatforms(platforms);
  }

  private List<Browser> getAppiumBrowsersFromSauceLabs() throws IOException {
    PlatformEndpoint pe = sauceREST.getPlatformEndpoint();
    List<Platform> platforms = pe.getSupportedPlatforms("appium").getPlatforms();
    return getBrowserListFromPlatforms(platforms);
  }

  public List<Browser> getBrowserListFromPlatforms(List<Platform> platforms) {
    List<Browser> browsers = new ArrayList<Browser>();

    for (Platform platform : platforms) {
      String seleniumName = platform.apiName;

      if (IEHTA.equals(seleniumName)) {
        // exclude these browsers from the list, as they replicate iexplore and firefox
        continue;
      }

      String longName = platform.longName;
      String longVersion = platform.longVersion;
      String shortVersion = platform.shortVersion;
      String osName = platform.os;

      if (platform.device != null && !platform.device.isEmpty()) {
        // Appium
        String device = longName;
        String deviceType = null;
        osName = platform.apiName; // use api_name instead of os, as os was returning Linux/Mac OS

        browsers.add(
            createDeviceBrowser(
                seleniumName,
                longName,
                longVersion,
                osName,
                device,
                deviceType,
                shortVersion,
                "portrait"));
        browsers.add(
            createDeviceBrowser(
                seleniumName,
                longName,
                longVersion,
                osName,
                device,
                deviceType,
                shortVersion,
                "landscape"));
        continue;
      }

      // Webdriver
      browsers.add(createBrowserBrowser(seleniumName, longName, "latest", osName, "latest"));
      browsers.add(createBrowserBrowser(seleniumName, longName, longVersion, osName, shortVersion));
    }

    return browsers;
  }

  /**
   * Parses the JSON response and constructs a List of {@link Browser} instances.
   *
   * @param browserListJson JSON response with all browsers
   * @return List of browser objects
   * @throws JSONException Invalid JSON
   */
  public List<Browser> getBrowserListFromJson(String browserListJson) throws JSONException {
    HashMap<String, Browser> browsers = new HashMap<>();

    JSONArray browserArray = new JSONArray(browserListJson);
    for (int i = 0; i < browserArray.length(); i++) {
      JSONObject browserObject = browserArray.getJSONObject(i);
      // Empty object, not normal use case
      if (browserObject.length() == 0) {
        continue;
      }

      String seleniumName = browserObject.getString("api_name");
      if (seleniumName.equals(IEHTA)) {
        // exclude these browsers from the list, as they replicate iexplore and firefox
        continue;
      }
      if (browserObject.has("device")) {
        // appium browser
        String longName = browserObject.getString("long_name");
        String longVersion = browserObject.getString("long_version");
        String osName =
            browserObject.getString(
                "api_name"); // use api_name instead of os, as os was returning Linux/Mac OS
        String shortVersion = browserObject.getString("short_version");
        // set value used for device to be the long name (ie. if device value is 'Nexus7HD', then
        // actually use 'Google Nexus 7 HD Emulator' ​
        String device = longName;

        String deviceType = null;

        if (browserObject.has("device-type")) {
          deviceType = browserObject.getString("device-type");
        }
        // iOS devices should include 'Simulator' in the device name (not currently included in the
        // Sauce REST API response.  The platform should also be set to iOS (as per instructions at
        // https://docs.saucelabs.com/reference/platforms-configurator
        if (device.equalsIgnoreCase("ipad") || device.equalsIgnoreCase("iphone")) {
          device = device + " Simulator";
          osName = "iOS";
          // JENKINS-29047 set the browserName to 'Safari'
          seleniumName = "Safari";
        }
        Browser browser;
        browser =
            createDeviceBrowser(
                seleniumName,
                longName,
                longVersion,
                osName,
                device,
                deviceType,
                shortVersion,
                "portrait");
        browsers.put(browser.getKey(), browser);
        browser =
            createDeviceBrowser(
                seleniumName,
                longName,
                longVersion,
                osName,
                device,
                deviceType,
                shortVersion,
                "landscape");
        browsers.put(browser.getKey(), browser);

      } else {
        // webdriver/selenium browser
        String longName = browserObject.getString("long_name");
        String longVersion = browserObject.getString("long_version");
        String osName = browserObject.getString("os");
        String shortVersion = browserObject.getString("short_version");

        Browser browser;

        browser = createBrowserBrowser(seleniumName, longName, "latest", osName, "latest");
        browsers.put(browser.getKey(), browser);
        browser = createBrowserBrowser(seleniumName, longName, longVersion, osName, shortVersion);
        browsers.put(browser.getKey(), browser);
      }
    }

    return new ArrayList<>(browsers.values());
  }

  private Browser createDeviceBrowser(
      String seleniumName,
      String longName,
      String longVersion,
      String osName,
      String device,
      String deviceType,
      String shortVersion,
      String orientation) {
    String browserKey = device + orientation + seleniumName + longVersion;
    // replace any spaces with _s
    browserKey = browserKey.replaceAll(" ", "_");
    // replace any . with _
    browserKey = browserKey.replaceAll("\\.", "_");
    StringBuilder label = new StringBuilder();
    label.append(longName).append(' ');
    if (deviceType != null) {
      label.append(deviceType).append(' ');
    }
    label.append(shortVersion);
    label.append(" (").append(orientation).append(')');
    // add browser for both landscape and portrait orientation
    Browser browser =
        new Browser(
            browserKey,
            osName,
            seleniumName,
            longName,
            shortVersion,
            longVersion,
            label.toString());
    browser.setDevice(device);
    browser.setDeviceType(deviceType);
    browser.setDeviceOrientation(orientation);
    return browser;
  }

  private Browser createBrowserBrowser(
      String seleniumName,
      String longName,
      String longVersion,
      String osName,
      String shortVersion) {
    String browserKey = osName + seleniumName + shortVersion;
    // replace any spaces with _s
    browserKey = browserKey.replaceAll(" ", "_");
    // replace any . with _
    browserKey = browserKey.replaceAll("\\.", "_");
    String label =
        OperatingSystemDescription.getOperatingSystemName(osName)
            + " "
            + longName
            + " "
            + shortVersion;
    return new Browser(
        browserKey, osName, seleniumName, longName, shortVersion, longVersion, label);
  }

  /**
   * Return the selenium rc browser which matches the key.
   *
   * @param key the key
   * @return the selenium rc browser which matches the key.
   */
  public Browser seleniumBrowserForKey(String key) {
    return seleniumLookup.get(key);
  }

  public Browser seleniumBrowserForKey(String key, boolean useLatestVersion) {
    Browser browser = webDriverBrowserForKey(key);
    if (useLatestVersion) {
      return getLatestSeleniumBrowserVersion(browser);
    } else {
      return browser;
    }
  }

  private Browser getLatestSeleniumBrowserVersion(Browser originalBrowser) {
    Browser candidateBrowser = originalBrowser;
    for (Browser browser : seleniumLookup.values()) {
      try {
        if (browser.getBrowserName().equals(originalBrowser.getBrowserName())
            && browser.getOs().equals(originalBrowser.getOs())
            && Integer.parseInt(browser.getLongVersion())
                > Integer.parseInt(candidateBrowser.getLongVersion())) {
          candidateBrowser = browser;
        }
      } catch (NumberFormatException e) {
        continue;
      }
    }
    return candidateBrowser;
  }

  /**
   * Return the web driver browser which matches the key.
   *
   * @param key the key
   * @return the web driver browser which matches the key.
   */
  public Browser webDriverBrowserForKey(String key) {
    return webDriverLookup.get(key);
  }

  public Browser webDriverBrowserForKey(String key, boolean useLatestVersion) {
    Browser browser = webDriverBrowserForKey(key);
    if (useLatestVersion) {
      return getLatestWebDriverBrowserVersion(browser);
    } else {
      return browser;
    }
  }

  private Browser getLatestWebDriverBrowserVersion(Browser originalBrowser) {
    Browser candidateBrowser = originalBrowser;
    for (Browser browser : webDriverLookup.values()) {

      try {
        if (browser.getBrowserName().equals(originalBrowser.getBrowserName())
            && browser.getOs().equals(originalBrowser.getOs())
            && Integer.parseInt(browser.getLongVersion())
                > Integer.parseInt(candidateBrowser.getLongVersion())) {
          candidateBrowser = browser;
        }
      } catch (NumberFormatException e) {
        continue;
      }
    }
    return candidateBrowser;
  }

  /**
   * Return the appium browser which matches the key.
   *
   * @param key the key
   * @return the appium browser which matches the key.
   */
  public Browser appiumBrowserForKey(String key) {
    return appiumLookup.get(key);
  }

  /**
   * Returns a singleton instance of SauceFactory. This is required because remote agents don't have
   * the Bamboo component plugin available, so the Spring auto-wiring doesn't work.
   *
   * @return the Browser Factory
   */
  public static BrowserFactory getInstance() {
    return getInstance(null);
  }

  public static BrowserFactory getInstance(SauceREST sauceREST) {
    if (instance == null) {
      instance = new BrowserFactory(sauceREST);
    }
    return instance;
  }
}
