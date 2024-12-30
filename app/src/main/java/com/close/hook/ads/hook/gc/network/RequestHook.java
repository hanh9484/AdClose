package com.close.hook.ads.hook.gc.network;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.Nullable;

import android.webkit.WebView;

import com.close.hook.ads.data.model.BlockedRequest;
import com.close.hook.ads.data.model.RequestDetails;
import com.close.hook.ads.data.model.Url;
import com.close.hook.ads.hook.util.ContextUtil;
import com.close.hook.ads.hook.util.DexKitUtil;
import com.close.hook.ads.hook.util.HookUtil;
import com.close.hook.ads.hook.util.StringFinderKit;
import com.close.hook.ads.provider.UrlContentProvider;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import org.luckypray.dexkit.result.MethodData;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLDecoder;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;

import kotlin.Triple;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class RequestHook {
    private static final String LOG_PREFIX = "[RequestHook] ";
    private static final Context APPLICATION_CONTEXT = ContextUtil.applicationContext;

    private static final Object EMPTY_WEB_RESPONSE = createEmptyWebResourceResponse();

    private static final ConcurrentHashMap<String, Boolean> dnsHostCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> urlStringCache = new ConcurrentHashMap<>();

    private static final Cache<String, Triple<Boolean, String, String>> queryCache = CacheBuilder.newBuilder()
        .maximumSize(12948)
        .expireAfterWrite(12, TimeUnit.HOURS)
        .build();

    public static void init() {
        try {
            setupDNSRequestHook();
            setupHttpRequestHook();
            setupOkHttpRequestHook();
            setWebViewRequestHook();
        } catch (Exception e) {
            XposedBridge.log(LOG_PREFIX + "Error while hooking: " + e.getMessage());
        }
    }

    private static String calculateCidrNotation(InetAddress inetAddress) {
        byte[] addressBytes = inetAddress.getAddress();
        int prefixLength = 0;

        for (byte b : addressBytes) {
            prefixLength += Integer.bitCount(b & 0xFF);
        }

        return inetAddress.getHostAddress() + "/" + prefixLength;
    }

    private static String formatUrlWithoutQuery(Object urlObject) {
        try {
            String formattedUrl = null;
            if (urlObject instanceof URL) {
                URL url = (URL) urlObject;
                String decodedPath = URLDecoder.decode(url.getPath(), StandardCharsets.UTF_8.name());
                URL formattedUrlObj = new URL(url.getProtocol(), url.getHost(), url.getPort(), decodedPath);
                formattedUrl = formattedUrlObj.toExternalForm();
            } else if (urlObject instanceof Uri) {
                Uri uri = (Uri) urlObject;
                String path = uri.getPath();
                String decodedPath = URLDecoder.decode(path, StandardCharsets.UTF_8.name());
                formattedUrl = new Uri.Builder()
                        .scheme(uri.getScheme())
                        .authority(uri.getAuthority())
                        .path(decodedPath)
                        .build()
                        .toString();
            }
            return formattedUrl;
        } catch (Exception e) {
            XposedBridge.log(LOG_PREFIX + "Error formatting URL: " + e.getMessage());
            return null;
        }
    }

    private static boolean shouldBlockDnsRequest(final String host, final RequestDetails details) {
        return checkShouldBlockRequest(host, details, " DNS", "host");
    }

    private static boolean shouldBlockHttpsRequest(final URL url, final RequestDetails details) {
        String formattedUrl = formatUrlWithoutQuery(url);
        return checkShouldBlockRequest(formattedUrl, details, " HTTP", "url");
    }

    private static boolean shouldBlockOkHttpsRequest(final URL url, final RequestDetails details) {
        String formattedUrl = formatUrlWithoutQuery(url);
        return checkShouldBlockRequest(formattedUrl, details, " OKHTTP", "url");
    }

    private static boolean shouldBlockWebRequest(final String url, final RequestDetails details) {
        Uri parsedUri = Uri.parse(url);
        String formattedUrl = formatUrlWithoutQuery(parsedUri);
        return checkShouldBlockRequest(formattedUrl, details, " Web", "url");
    }

    private static boolean checkShouldBlockRequest(final String queryValue, final RequestDetails details, final String requestType, final String queryType) {
        Triple<Boolean, String, String> triple = queryContentProvider(queryType, queryValue);
        boolean shouldBlock = triple.getFirst();
        String blockType = triple.getSecond();
        String url = triple.getThird();

        sendBroadcast(requestType, shouldBlock, blockType, url, queryValue, details);
        return shouldBlock;
    }

    private static Triple<Boolean, String, String> queryContentProvider(String queryType, String queryValue) {
        String cacheKey = queryType + ":" + queryValue;
        Triple<Boolean, String, String> result = queryCache.getIfPresent(cacheKey);
        if (result != null) {
            return result;
        }

        ContentResolver contentResolver = APPLICATION_CONTEXT.getContentResolver();
        Uri uri = new Uri.Builder()
            .scheme("content")
            .authority(UrlContentProvider.AUTHORITY)
            .appendPath(UrlContentProvider.URL_TABLE_NAME)
            .build();

        String[] projection = {Url.URL_TYPE, Url.URL_ADDRESS};
        String[] selectionArgs = {queryType, queryValue};

        try (Cursor cursor = contentResolver.query(uri, projection, null, selectionArgs, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String urlType = cursor.getString(cursor.getColumnIndexOrThrow(Url.URL_TYPE));
                String urlValue = cursor.getString(cursor.getColumnIndexOrThrow(Url.URL_ADDRESS));

                result = new Triple<>(true, urlType, urlValue);
                queryCache.put(cacheKey, result);
                return result;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        result = new Triple<>(false, null, null);
        queryCache.put(cacheKey, result);
        return result;
    }

    private static void setupDNSRequestHook() {
        HookUtil.findAndHookMethod(
            InetAddress.class,
            "getByName",
            new Object[]{String.class},
            "after",
            param -> processDnsRequest(param.args, param.getResult(), "getByName")
        );

        HookUtil.findAndHookMethod(
            InetAddress.class,
            "getAllByName",
            new Object[]{String.class},
            "after",
            param -> processDnsRequest(param.args, param.getResult(), "getAllByName")
        );
    }

    private static void processDnsRequest(Object[] args, Object result, String methodName) {
        try {
            String host = (String) args[0];
            if (host == null) return;

            String stackTrace = HookUtil.getFormattedStackTrace();
            String cidr = null;
            String fullAddress = null;

            if (methodName.equals("getByName")) {
                InetAddress inetAddress = (InetAddress) result;
                if (inetAddress == null) return;
                cidr = calculateCidrNotation(inetAddress);
                fullAddress = inetAddress.getHostAddress();
            } else if (methodName.equals("getAllByName")) {
                InetAddress[] inetAddresses = (InetAddress[]) result;
                if (inetAddresses == null || inetAddresses.length == 0) return;

                StringBuilder cidrBuilder = new StringBuilder();
                StringBuilder fullAddressBuilder = new StringBuilder();

                for (InetAddress inetAddress : inetAddresses) {
                    if (cidrBuilder.length() > 0) {
                        cidrBuilder.append(", ");
                        fullAddressBuilder.append(", ");
                    }
                    cidrBuilder.append(calculateCidrNotation(inetAddress));
                    fullAddressBuilder.append(inetAddress.getHostAddress());
                }

                cidr = cidrBuilder.toString();
                fullAddress = fullAddressBuilder.toString();
            }

            RequestDetails details = new RequestDetails(host, cidr, fullAddress, stackTrace);

            if (shouldBlockDnsRequest(host, details)) {
                result = methodName.equals("getByName") ? null : new InetAddress[0];
            }

        } catch (Exception e) {
            XposedBridge.log(LOG_PREFIX + " - Error processing dns request: " + e.getMessage());
        }
    }

    private static void setupHttpRequestHook() {
        try {
            HookUtil.hookAllMethods("com.android.okhttp.internal.huc.HttpURLConnectionImpl", "getResponse", "after", param -> {
                try {
                    Object httpEngine = XposedHelpers.getObjectField(param.thisObject, "httpEngine");

                    boolean isResponseAvailable = (boolean) XposedHelpers.callMethod(httpEngine, "hasResponse");
                    if (!isResponseAvailable) {
                        return;
                    }

                    Object response = XposedHelpers.callMethod(httpEngine, "getResponse");
                    Object request = XposedHelpers.callMethod(httpEngine, "getRequest");
                    Object httpUrl = XposedHelpers.callMethod(request, "urlString");
                    URL url = new URL(httpUrl.toString());

                    String stackTrace = HookUtil.getFormattedStackTrace();

                    RequestDetails details = processHttpRequest(request, response, url, stackTrace);

                    if (shouldBlockHttpsRequest(url, details)) {
                        Object emptyResponse = createEmptyResponseForHttp(response);

                        Field userResponseField = httpEngine.getClass().getDeclaredField("userResponse");
                        userResponseField.setAccessible(true);
                        userResponseField.set(httpEngine, emptyResponse);
                    }
                } catch (Exception e) {
                    XposedBridge.log(LOG_PREFIX + "Exception in HTTP connection hook: " + e.getMessage());
                }
            }, ContextUtil.applicationContext.getClassLoader());
        } catch (Exception e) {
            XposedBridge.log(LOG_PREFIX + "Error setting up HTTP connection hook: " + e.getMessage());
        }
    }

    private static Object createEmptyResponseForHttp(Object response) throws Exception {
        if (response.getClass().getName().equals("com.android.okhttp.Response")) {
            Class<?> responseClass = response.getClass();

            Class<?> builderClass = Class.forName("com.android.okhttp.Response$Builder");
            Class<?> requestClass = Class.forName("com.android.okhttp.Request");
            Class<?> protocolClass = Class.forName("com.android.okhttp.Protocol");
            Class<?> responseBodyClass = Class.forName("com.android.okhttp.ResponseBody");

            Object request = XposedHelpers.callMethod(response, "request");

            Object builder = builderClass.newInstance();

            builderClass.getMethod("request", requestClass).invoke(builder, request);

            Object protocolHTTP11 = protocolClass.getField("HTTP_1_1").get(null);
            builderClass.getMethod("protocol", protocolClass).invoke(builder, protocolHTTP11);

            builderClass.getMethod("code", int.class).invoke(builder, 204); // 204 No Content
            builderClass.getMethod("message", String.class).invoke(builder, "No Content");

            Method createMethod = responseBodyClass.getMethod("create", Class.forName("com.android.okhttp.MediaType"), String.class);
            Object emptyResponseBody = createMethod.invoke(null, null, "");

            builderClass.getMethod("body", responseBodyClass).invoke(builder, emptyResponseBody);

            return builderClass.getMethod("build").invoke(builder);
        }
        return null;
    }

    public static void setupOkHttpRequestHook() {
        hookMethod("setupOkHttpRequestHook", "Already Executed", "execute");  // okhttp3.Call.execute -overload method
        hookMethod("setupOkHttp2RequestHook", "Canceled", "intercept");  // okhttp3.internal.http.RetryAndFollowUpInterceptor.intercept
    }

    private static void hookMethod(String cacheKeySuffix, String methodDescription, String methodName) {
        String cacheKey = ContextUtil.applicationContext.getPackageName() + ":" + cacheKeySuffix;
        List<MethodData> foundMethods = StringFinderKit.INSTANCE.findMethodsWithString(cacheKey, methodDescription, methodName);

        if (foundMethods != null) {
            for (MethodData methodData : foundMethods) {
                try {
                    Method method = methodData.getMethodInstance(DexKitUtil.INSTANCE.getContext().getClassLoader());
                    XposedBridge.log(LOG_PREFIX+ "setupOkHttpRequestHook" + methodData);
                    HookUtil.hookMethod(method, "after", param -> {
                        try {
                            Object request = param.args[1];

                            Object response = param.getResult();

                            Object okhttpUrl = XposedHelpers.callMethod(request, "url");
                            URL url = new URL(okhttpUrl.toString());

                            String stackTrace = HookUtil.getFormattedStackTrace();
                            RequestDetails details = processHttpRequest(request, response, url, stackTrace);

                            if (shouldBlockOkHttpsRequest(url, details)) {
                                throw new IOException("Request blocked");
                            }
                        } catch (IOException e) {
                            param.setThrowable(e);
                        } catch (Exception e) {
                            XposedBridge.log("Error processing method hook: " + methodData + ", " + e.getMessage());
                        }
                    });
                } catch (Exception e) {
                    XposedBridge.log("Error hooking method: " + methodData + ", " + e.getMessage());
                }
            }
        }
    }

    private static RequestDetails processHttpRequest(Object request, Object response, URL url, String stack) {
        try {
            String method = (String) XposedHelpers.callMethod(request, "method");
            String urlString = url.toString();
            Object requestHeaders = XposedHelpers.callMethod(request, "headers");

            int code = (int) XposedHelpers.callMethod(response, "code");
            String message = (String) XposedHelpers.callMethod(response, "message");
            Object responseHeaders = XposedHelpers.callMethod(response, "headers");

            return new RequestDetails(method, urlString, requestHeaders, code, message, responseHeaders, stack);
        } catch (Exception e) {
            XposedBridge.log(LOG_PREFIX + " - Error processing http request: " + e.getMessage());
            return null;
        }
    }

    public static void setWebViewRequestHook() {
        try {
            HookUtil.findAndHookMethod(
                WebView.class,
                "setWebViewClient",
                new Object[]{"android.webkit.WebViewClient"},
                "before",
                param -> {
                    Object client = param.args[0];
                    if (client != null) {
                        String clientClassName = client.getClass().getName();
                        XposedBridge.log(LOG_PREFIX + " - WebViewClient set: " + clientClassName);

                        hookClientMethods(clientClassName);
                    }
                }
            );
        } catch (Exception e) {
            XposedBridge.log(LOG_PREFIX + " - Error hooking WebViewClient methods: " + e.getMessage());
        }
    }

    private static void hookClientMethods(String clientClassName) {
        try {
            HookUtil.findAndHookMethod(
                clientClassName,
                "shouldInterceptRequest",
                new Object[]{"android.webkit.WebView", "android.webkit.WebResourceRequest"},
                "after",
                param -> {
                    Object response = param.getResult();
                    if (response == null) {
                        return;
                    }

                    Object request = param.args[1];
                    if (request == null) {
                        return;
                    }

                    Object webUrl = XposedHelpers.callMethod(request, "getUrl");
                    String url = webUrl.toString();

                    String stackTrace = HookUtil.getFormattedStackTrace();

                    RequestDetails details = processWebRequest(request, response, url, stackTrace);

                    if (shouldBlockWebRequest(url, details)) {
                        try {
                            param.setResult(EMPTY_WEB_RESPONSE);
                        } catch (Exception e) {
                            XposedBridge.log(LOG_PREFIX + " Error creating WebResourceResponse: " + e.getMessage());
                        }
                    }
                }, ContextUtil.applicationContext.getClassLoader()
            );
        } catch (Exception e) {
            XposedBridge.log(LOG_PREFIX + " - Error hooking WebViewClient method: " + e.getMessage());
        }
    }

    private static RequestDetails processWebRequest(Object request, Object response, String url, String stack) {
        try {
            String method = (String) XposedHelpers.callMethod(request, "getMethod");
            Object requestHeaders = XposedHelpers.callMethod(request, "getRequestHeaders");

            Object responseHeaders = XposedHelpers.callMethod(response, "getResponseHeaders");
            int responseCode = (int) XposedHelpers.callMethod(response, "getStatusCode");
            String responseMessage = (String) XposedHelpers.callMethod(response, "getReasonPhrase");

            return new RequestDetails(method, url, requestHeaders, responseCode, responseMessage, responseHeaders, stack);
        } catch (Exception e) {
            XposedBridge.log(LOG_PREFIX + " - Error processing web request: " + e.getMessage());
            return null;
        }
    }

    private static Object createEmptyWebResourceResponse() {
        try {
            Class<?> webResourceResponseClass = Class.forName("android.webkit.WebResourceResponse");
            return webResourceResponseClass
                    .getConstructor(String.class, String.class, int.class, String.class, java.util.Map.class, java.io.InputStream.class)
                    .newInstance("text/plain", "UTF-8", 204, "No Content", null, null);
        } catch (Exception e) {
            XposedBridge.log("Error creating empty WebResourceResponse: " + e.getMessage());
            return null;
        }
    }

    private static void sendBroadcast(
        String requestType, boolean shouldBlock, String blockType, String ruleUrl,
        String url, RequestDetails details) {
        sendBlockedRequestBroadcast("all", requestType, shouldBlock, ruleUrl, blockType, url, details);
        sendBlockedRequestBroadcast(shouldBlock ? "block" : "pass", requestType, shouldBlock, ruleUrl, blockType, url, details);
    }

    private static void sendBlockedRequestBroadcast(
        String type, @Nullable String requestType, @Nullable Boolean isBlocked,
        @Nullable String url, @Nullable String blockType, String request,
        RequestDetails details) {
        if (details == null) return;

        String dnsHost = details.getDnsHost();
        String urlString = details.getUrlString();

        if (dnsHost != null && dnsHostCache.putIfAbsent(dnsHost, true) != null) {
            return;
        }

        if (urlString != null && urlStringCache.putIfAbsent(urlString, true) != null) {
            return;
        }

        Intent intent = new Intent("com.rikkati.REQUEST");

        try {
            String appName = APPLICATION_CONTEXT.getApplicationInfo().loadLabel(APPLICATION_CONTEXT.getPackageManager()).toString() + requestType;
            String packageName = APPLICATION_CONTEXT.getPackageName();

            String method = details.getMethod();
            String requestHeaders = details.getRequestHeaders() != null ? details.getRequestHeaders().toString() : null;
            int responseCode = details.getResponseCode();
            String responseMessage = details.getResponseMessage();
            String responseHeaders = details.getResponseHeaders() != null ? details.getResponseHeaders().toString() : null;
            String stackTrace = details.getStack();
            String dnsCidr = details.getDnsCidr();
            String fullAddress = details.getFullAddress();

            BlockedRequest blockedRequest = new BlockedRequest(
                appName,
                packageName,
                request,
                System.currentTimeMillis(),
                type,
                isBlocked,
                url,
                blockType,
                method,
                urlString,
                requestHeaders,
                responseCode,
                responseMessage,
                responseHeaders,
                stackTrace,
                dnsHost,
                dnsCidr,
                fullAddress
            );

            intent.putExtra("request", blockedRequest);
            APPLICATION_CONTEXT.sendBroadcast(intent);
        } catch (Exception e) {
            Log.w("RequestHook", "sendBlockedRequestBroadcast: Error broadcasting request", e);
        }
    }
}
