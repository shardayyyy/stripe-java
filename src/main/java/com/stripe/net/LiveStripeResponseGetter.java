package com.stripe.net;

import com.stripe.Stripe;
import com.stripe.exception.APIConnectionException;
import com.stripe.exception.APIException;
import com.stripe.exception.AuthenticationException;
import com.stripe.exception.CardException;
import com.stripe.exception.InvalidRequestException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.net.URLStreamHandler;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

public class LiveStripeResponseGetter implements StripeResponseGetter {
	private static final String DNS_CACHE_TTL_PROPERTY_NAME = "networkaddress.cache.ttl";

	/*
	 * Set this property to override your environment's default
	 * URLStreamHandler; Settings the property should not be needed in most
	 * environments.
	 */
	private static final String CUSTOM_URL_STREAM_HANDLER_PROPERTY_NAME = "com.stripe.net.customURLStreamHandler";

	public <T> T request(
			APIResource.RequestMethod method,
			String url,
			Map<String, Object> params,
			Class<T> clazz,
			APIResource.RequestType type,
			RequestOptions options) throws AuthenticationException, InvalidRequestException, APIConnectionException, CardException, APIException {
		return _request(method, url, params, clazz, type, options);
	}

	private static String urlEncodePair(String k, String v)
			throws UnsupportedEncodingException {
		return String.format("%s=%s", APIResource.urlEncode(k), APIResource.urlEncode(v));
	}

	static Map<String, String> getHeaders(RequestOptions options) {
		Map<String, String> headers = new HashMap<String, String>();
		String apiVersion = options.getStripeVersion();
		headers.put("Accept-Charset", APIResource.CHARSET);
		headers.put("Accept", "application/json");
		headers.put("User-Agent",
				String.format("Stripe/v1 JavaBindings/%s", Stripe.VERSION));

		headers.put("Authorization", String.format("Bearer %s", options.getApiKey()));

		// debug headers
		String[] propertyNames = { "os.name", "os.version", "os.arch",
				"java.version", "java.vendor", "java.vm.version",
				"java.vm.vendor" };
		Map<String, String> propertyMap = new HashMap<String, String>();
		for (String propertyName : propertyNames) {
			propertyMap.put(propertyName, System.getProperty(propertyName));
		}
		propertyMap.put("bindings.version", Stripe.VERSION);
		propertyMap.put("lang", "Java");
		propertyMap.put("publisher", "Stripe");
		headers.put("X-Stripe-Client-User-Agent", APIResource.GSON.toJson(propertyMap));
		if (apiVersion != null) {
			headers.put("Stripe-Version", apiVersion);
		}
		if (options.getIdempotencyKey() != null) {
			headers.put("Idempotency-Key", options.getIdempotencyKey());
		}
		if (options.getStripeAccount() != null) {
			headers.put("Stripe-Account", options.getStripeAccount());
		}
		return headers;
	}

	private static java.net.HttpURLConnection createStripeConnection(
			String url, RequestOptions options) throws IOException {
		URL stripeURL;
		String customURLStreamHandlerClassName = System.getProperty(
				CUSTOM_URL_STREAM_HANDLER_PROPERTY_NAME, null);
		if (customURLStreamHandlerClassName != null) {
			// instantiate the custom handler provided
			try {
				Class<URLStreamHandler> clazz = (Class<URLStreamHandler>) Class
						.forName(customURLStreamHandlerClassName);
				Constructor<URLStreamHandler> constructor = clazz
						.getConstructor();
				URLStreamHandler customHandler = constructor.newInstance();
				stripeURL = new URL(null, url, customHandler);
			} catch (ClassNotFoundException e) {
				throw new IOException(e);
			} catch (SecurityException e) {
				throw new IOException(e);
			} catch (NoSuchMethodException e) {
				throw new IOException(e);
			} catch (IllegalArgumentException e) {
				throw new IOException(e);
			} catch (InstantiationException e) {
				throw new IOException(e);
			} catch (IllegalAccessException e) {
				throw new IOException(e);
			} catch (InvocationTargetException e) {
				throw new IOException(e);
			}
		} else {
			stripeURL = new URL(url);
		}
		java.net.HttpURLConnection conn = (java.net.HttpURLConnection) stripeURL.openConnection();
		conn.setConnectTimeout(30 * 1000);
		conn.setReadTimeout(80 * 1000);
		conn.setUseCaches(false);
		for (Map.Entry<String, String> header : getHeaders(options).entrySet()) {
			conn.setRequestProperty(header.getKey(), header.getValue());
		}

		return conn;
	}

	private static String formatURL(String url, String query) {
		if (query == null || query.isEmpty()) {
			return url;
		} else {
			// In some cases, URL can already contain a question mark (eg, upcoming invoice lines)
			String separator = url.contains("?") ? "&" : "?";
			return String.format("%s%s%s", url, separator, query);
		}
	}

	private static java.net.HttpURLConnection createGetConnection(
			String url, String query, RequestOptions options) throws IOException  {
		String getURL = formatURL(url, query);
		java.net.HttpURLConnection conn = createStripeConnection(getURL, options);
		conn.setRequestMethod("GET");

		return conn;
	}

	private static java.net.HttpURLConnection createPostConnection(
			String url, String query, RequestOptions options) throws IOException {
		java.net.HttpURLConnection conn = createStripeConnection(url, options);

		conn.setDoOutput(true);
		conn.setRequestMethod("POST");
		conn.setRequestProperty("Content-Type", String.format(
				"application/x-www-form-urlencoded;charset=%s", APIResource.CHARSET));

		OutputStream output = null;
		try {
			output = conn.getOutputStream();
			output.write(query.getBytes(APIResource.CHARSET));
		} finally {
			if (output != null) {
				output.close();
			}
		}
		return conn;
	}

	private static java.net.HttpURLConnection createDeleteConnection(
			String url, String query, RequestOptions options) throws IOException {
		String deleteUrl = formatURL(url, query);
		java.net.HttpURLConnection conn = createStripeConnection(
				deleteUrl, options);
		conn.setRequestMethod("DELETE");

		return conn;
	}

	static String createQuery(Map<String, Object> params)
			throws UnsupportedEncodingException, InvalidRequestException {
		Map<String, String> flatParams = flattenParams(params);
		StringBuilder queryStringBuffer = new StringBuilder();
		for (Map.Entry<String, String> entry : flatParams.entrySet()) {
			if (queryStringBuffer.length() > 0) {
				queryStringBuffer.append("&");
			}
			queryStringBuffer.append(urlEncodePair(entry.getKey(),
					entry.getValue()));
		}
		return queryStringBuffer.toString();
	}


	private static Map<String, String> flattenParams(Map<String, Object> params)
			throws InvalidRequestException {
		if (params == null) {
			return new HashMap<String, String>();
		}
		Map<String, String> flatParams = new LinkedHashMap<String, String>();
		for (Map.Entry<String, Object> entry : params.entrySet()) {
			String key = entry.getKey();
			Object value = entry.getValue();
			if (value instanceof Map<?, ?>) {
				Map<String, Object> flatNestedMap = new LinkedHashMap<String, Object>();
				Map<?, ?> nestedMap = (Map<?, ?>) value;
				for (Map.Entry<?, ?> nestedEntry : nestedMap.entrySet()) {
					flatNestedMap.put(
							String.format("%s[%s]", key, nestedEntry.getKey()),
							nestedEntry.getValue());
				}
				flatParams.putAll(flattenParams(flatNestedMap));
			} else if (value instanceof List<?>) {
				Map<String, Object> flatNestedMap = new LinkedHashMap<String, Object>();
				Iterator<?> it = ((List<?>)value).iterator();
				for (int index = 0; it.hasNext(); ++index) {
					flatNestedMap.put(String.format("%s[%s]", key, index), it.next());
				}
				flatParams.putAll(flattenParams(flatNestedMap));
			} else if ("".equals(value)) {
					throw new InvalidRequestException("You cannot set '"+key+"' to an empty string. "+
										"We interpret empty strings as null in requests. "+
										"You may set '"+key+"' to null to delete the property.",
										key, null, null);
			} else if (value == null) {
				flatParams.put(key, "");
			} else {
				flatParams.put(key, value.toString());
			}
		}
		return flatParams;
	}

	// represents Errors returned as JSON
	private static class ErrorContainer {
		private LiveStripeResponseGetter.Error error;
	}

	private static class Error {
		@SuppressWarnings("unused")
		String type;

		String message;

		String code;

		String param;

		String decline_code;

		String charge;
	}

	private static String getResponseBody(InputStream responseStream)
			throws IOException {
		//\A is the beginning of
		// the stream boundary
		String rBody = new Scanner(responseStream, APIResource.CHARSET)
												.useDelimiter("\\A")
												.next(); //

		responseStream.close();
		return rBody;
	}

	private static StripeResponse makeURLConnectionRequest(
			APIResource.RequestMethod method, String url, String query,
			RequestOptions options) throws APIConnectionException {
		java.net.HttpURLConnection conn = null;
		try {
			switch (method) {
			case GET:
				conn = createGetConnection(url, query, options);
				break;
			case POST:
				conn = createPostConnection(url, query, options);
				break;
			case DELETE:
				conn = createDeleteConnection(url, query, options);
				break;
			default:
				throw new APIConnectionException(
						String.format(
								"Unrecognized HTTP method %s. "
										+ "This indicates a bug in the Stripe bindings. Please contact "
										+ "support@stripe.com for assistance.",
								method));
			}
			// trigger the request
			int rCode = conn.getResponseCode();
			String rBody;
			Map<String, List<String>> headers;

			if (rCode >= 200 && rCode < 300) {
				rBody = getResponseBody(conn.getInputStream());
			} else {
				rBody = getResponseBody(conn.getErrorStream());
			}
			headers = conn.getHeaderFields();
			return new StripeResponse(rCode, rBody, headers);

		} catch (IOException e) {
			throw new APIConnectionException(
					String.format(
							"IOException during API request to Stripe (%s): %s "
									+ "Please check your internet connection and try again. If this problem persists,"
									+ "you should check Stripe's service status at https://twitter.com/stripestatus,"
									+ " or let us know at support@stripe.com.",
							Stripe.getApiBase(), e.getMessage()), e);
		} finally {
			if (conn != null) {
				conn.disconnect();
			}
		}
	}

	private static <T> T _request(APIResource.RequestMethod method,
			String url, Map<String, Object> params, Class<T> clazz,
			APIResource.RequestType type, RequestOptions options)
			throws AuthenticationException, InvalidRequestException,
			APIConnectionException, CardException, APIException {
		if (options == null) {
			options = RequestOptions.getDefault();
		}
		String originalDNSCacheTTL = null;
		Boolean allowedToSetTTL = true;

		try {
			originalDNSCacheTTL = java.security.Security
					.getProperty(DNS_CACHE_TTL_PROPERTY_NAME);
			// disable DNS cache
			java.security.Security
					.setProperty(DNS_CACHE_TTL_PROPERTY_NAME, "0");
		} catch (SecurityException se) {
			allowedToSetTTL = false;
		}

		String apiKey = options.getApiKey();
		if (apiKey == null || apiKey.trim().isEmpty()) {
			throw new AuthenticationException(
					"No API key provided. (HINT: set your API key using 'Stripe.apiKey = <API-KEY>'. "
							+ "You can generate API keys from the Stripe web interface. "
							+ "See https://stripe.com/api for details or email support@stripe.com if you have questions.",
					null);
		}

		try {
			StripeResponse response;
			switch (type) {
			case NORMAL:
				response = getStripeResponse(method, url, params, options);
				break;
			case MULTIPART:
				response = getMultipartStripeResponse(method, url, params,
						options);
				break;
			default:
				throw new RuntimeException(
						"Invalid APIResource request type. "
								+ "This indicates a bug in the Stripe bindings. Please contact "
								+ "support@stripe.com for assistance.");
			}
			int rCode = response.responseCode;
			String rBody = response.responseBody;

			String requestId = null;
			Map<String, List<String>> headers = response.getResponseHeaders();
			List<String> requestIdList = headers == null ? null : headers.get("Request-Id");
			if (requestIdList != null && requestIdList.size() > 0) {
				requestId = requestIdList.get(0);
			}

			if (rCode < 200 || rCode >= 300) {
				handleAPIError(rBody, rCode, requestId);
			}
			return APIResource.GSON.fromJson(rBody, clazz);
		} finally {
			if (allowedToSetTTL) {
				if (originalDNSCacheTTL == null) {
					// value unspecified by implementation
					// DNS_CACHE_TTL_PROPERTY_NAME of -1 = cache forever
					java.security.Security.setProperty(
							DNS_CACHE_TTL_PROPERTY_NAME, "-1");
				} else {
					java.security.Security.setProperty(
							DNS_CACHE_TTL_PROPERTY_NAME, originalDNSCacheTTL);
				}
			}
		}
	}

	private static StripeResponse getStripeResponse(
			APIResource.RequestMethod method, String url,
			Map<String, Object> params, RequestOptions options)
			throws InvalidRequestException, APIConnectionException,
			APIException {
		String query;
		try {
			query = createQuery(params);
		} catch (UnsupportedEncodingException e) {
			throw new InvalidRequestException("Unable to encode parameters to "
					+ APIResource.CHARSET
					+ ". Please contact support@stripe.com for assistance.",
					null, null, e);
		}

		try {
			// HTTPSURLConnection verifies SSL cert by default
			return makeURLConnectionRequest(method, url, query, options);
		} catch (ClassCastException ce) {
			// appengine doesn't have HTTPSConnection, use URLFetch API
			String appEngineEnv = System.getProperty(
					"com.google.appengine.runtime.environment", null);
			if (appEngineEnv != null) {
				return makeAppEngineRequest(method, url, query, options);
			} else {
				// non-appengine ClassCastException
				throw ce;
			}
		}
	}

	private static StripeResponse getMultipartStripeResponse(
			APIResource.RequestMethod method, String url,
			Map<String, Object> params, RequestOptions options)
			throws InvalidRequestException, APIConnectionException,
			APIException {

		if (method != APIResource.RequestMethod.POST) {
			throw new InvalidRequestException(
					"Multipart requests for HTTP methods other than POST "
							+ "are currently not supported.", null, null, null);
		}

		java.net.HttpURLConnection conn = null;
		try {
			conn = createStripeConnection(url, options);

			String boundary = MultipartProcessor.getBoundary();
			conn.setDoOutput(true);
			conn.setRequestMethod("POST");
			conn.setRequestProperty("Content-Type", String.format(
					"multipart/form-data; boundary=%s", boundary));

			MultipartProcessor multipartProcessor = null;
			try {
				multipartProcessor = new MultipartProcessor(
						conn, boundary, APIResource.CHARSET);

				for (Map.Entry<String, Object> entry : params.entrySet()) {
					String key = entry.getKey();
					Object value = entry.getValue();

					if (value instanceof File) {
						File currentFile = (File) value;
						if (!currentFile.exists()) {
							throw new InvalidRequestException("File for key "
									+ key + " must exist.", null, null, null);
						} else if (!currentFile.isFile()) {
							throw new InvalidRequestException("File for key "
									+ key
									+ " must be a file and not a directory.",
									null, null, null);
						} else if (!currentFile.canRead()) {
							throw new InvalidRequestException(
									"Must have read permissions on file for key "
									+ key + ".", null, null, null);
						}
						multipartProcessor.addFileField(key, currentFile);
					} else {
						// We only allow a single level of nesting for params
						// for multipart
						multipartProcessor.addFormField(key, (String) value);
					}
				}

			} finally {
				if (multipartProcessor != null) {
					multipartProcessor.finish();
				}
			}

			// trigger the request
			int rCode = conn.getResponseCode();
			String rBody;
			Map<String, List<String>> headers;

			if (rCode >= 200 && rCode < 300) {
				rBody = getResponseBody(conn.getInputStream());
			} else {
				rBody = getResponseBody(conn.getErrorStream());
			}
			headers = conn.getHeaderFields();
			return new StripeResponse(rCode, rBody, headers);

		} catch (IOException e) {
			throw new APIConnectionException(
					String.format(
							"IOException during API request to Stripe (%s): %s "
									+ "Please check your internet connection and try again. If this problem persists,"
									+ "you should check Stripe's service status at https://twitter.com/stripestatus,"
									+ " or let us know at support@stripe.com.",
							Stripe.getApiBase(), e.getMessage()), e);
		} finally {
			if (conn != null) {
				conn.disconnect();
			}
		}

	}

	private static void handleAPIError(String rBody, int rCode, String requestId)
			throws InvalidRequestException, AuthenticationException,
			CardException, APIException {
		LiveStripeResponseGetter.Error error = APIResource.GSON.fromJson(rBody,
				LiveStripeResponseGetter.ErrorContainer.class).error;
		switch (rCode) {
		case 400:
			throw new InvalidRequestException(error.message, error.param, requestId, null);
		case 404:
			throw new InvalidRequestException(error.message, error.param, requestId, null);
		case 401:
			throw new AuthenticationException(error.message, requestId);
		case 402:
			throw new CardException(error.message, requestId, error.code, error.param, error.decline_code, error.charge, null);
		default:
			throw new APIException(error.message, requestId, null);
		}
	}

	/*
	 * This is slower than usual because of reflection but avoids having to
	 * maintain AppEngine-specific JAR
	 */
	private static StripeResponse makeAppEngineRequest(APIResource.RequestMethod method,
			String url, String query, RequestOptions options) throws APIException {
		String unknownErrorMessage = "Sorry, an unknown error occurred while trying to use the "
				+ "Google App Engine runtime. Please contact support@stripe.com for assistance.";
		try {
			if (method == APIResource.RequestMethod.GET || method == APIResource.RequestMethod.DELETE) {
				url = String.format("%s?%s", url, query);
			}
			URL fetchURL = new URL(url);

			Class<?> requestMethodClass = Class
					.forName("com.google.appengine.api.urlfetch.HTTPMethod");
			Object httpMethod = requestMethodClass.getDeclaredField(
					method.name()).get(null);

			Class<?> fetchOptionsBuilderClass = Class
					.forName("com.google.appengine.api.urlfetch.FetchOptions$Builder");
			Object fetchOptions;
			try {
				fetchOptions = fetchOptionsBuilderClass.getDeclaredMethod(
						"validateCertificate").invoke(null);
			} catch (NoSuchMethodException e) {
				System.err
						.println("Warning: this App Engine SDK version does not allow verification of SSL certificates;"
								+ "this exposes you to a MITM attack. Please upgrade your App Engine SDK to >=1.5.0. "
								+ "If you have questions, contact support@stripe.com.");
				fetchOptions = fetchOptionsBuilderClass.getDeclaredMethod(
						"withDefaults").invoke(null);
			}

			Class<?> fetchOptionsClass = Class
					.forName("com.google.appengine.api.urlfetch.FetchOptions");

			// GAE requests can time out after 60 seconds, so make sure we leave
			// some time for the application to handle a slow Stripe
			fetchOptionsClass.getDeclaredMethod("setDeadline",
					java.lang.Double.class)
					.invoke(fetchOptions, new Double(55));

			Class<?> requestClass = Class
					.forName("com.google.appengine.api.urlfetch.HTTPRequest");

			Object request = requestClass.getDeclaredConstructor(URL.class,
					requestMethodClass, fetchOptionsClass).newInstance(
					fetchURL, httpMethod, fetchOptions);

			if (method == APIResource.RequestMethod.POST) {
				requestClass.getDeclaredMethod("setPayload", byte[].class)
						.invoke(request, query.getBytes());
			}

			for (Map.Entry<String, String> header : getHeaders(options)
					.entrySet()) {
				Class<?> httpHeaderClass = Class
						.forName("com.google.appengine.api.urlfetch.HTTPHeader");
				Object reqHeader = httpHeaderClass.getDeclaredConstructor(
						String.class, String.class).newInstance(
						header.getKey(), header.getValue());
				requestClass.getDeclaredMethod("setHeader", httpHeaderClass)
						.invoke(request, reqHeader);
			}

			Class<?> urlFetchFactoryClass = Class
					.forName("com.google.appengine.api.urlfetch.URLFetchServiceFactory");
			Object urlFetchService = urlFetchFactoryClass.getDeclaredMethod(
					"getURLFetchService").invoke(null);

			Method fetchMethod = urlFetchService.getClass().getDeclaredMethod(
					"fetch", requestClass);
			fetchMethod.setAccessible(true);
			Object response = fetchMethod.invoke(urlFetchService, request);

			int responseCode = (Integer) response.getClass()
					.getDeclaredMethod("getResponseCode").invoke(response);
			String body = new String((byte[]) response.getClass()
					.getDeclaredMethod("getContent").invoke(response), APIResource.CHARSET);
			return new StripeResponse(responseCode, body);
		} catch (InvocationTargetException e) {
			throw new APIException(unknownErrorMessage, null, e);
		} catch (MalformedURLException e) {
			throw new APIException(unknownErrorMessage, null, e);
		} catch (NoSuchFieldException e) {
			throw new APIException(unknownErrorMessage, null, e);
		} catch (SecurityException e) {
			throw new APIException(unknownErrorMessage, null, e);
		} catch (NoSuchMethodException e) {
			throw new APIException(unknownErrorMessage, null, e);
		} catch (ClassNotFoundException e) {
			throw new APIException(unknownErrorMessage, null, e);
		} catch (IllegalArgumentException e) {
			throw new APIException(unknownErrorMessage, null, e);
		} catch (IllegalAccessException e) {
			throw new APIException(unknownErrorMessage, null, e);
		} catch (InstantiationException e) {
			throw new APIException(unknownErrorMessage, null, e);
		} catch (UnsupportedEncodingException e) {
			throw new APIException(unknownErrorMessage, null, e);
		}
	}
}
